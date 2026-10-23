package com.bencodez.mcperks.userapi;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Ordered durable personal charge ledger. No Bukkit or SQL access occurs on its worker. */
public final class UsageCharges implements AutoCloseable {
    public enum Kind { AutoBlockRefill, VeinMining, RightClickHarvest }
    public record Reservation(UUID id, UUID owner, Kind kind, int units) {}
    private record Account(int[] remaining, long[] successful) {
        Account copy() { return new Account(remaining.clone(), successful.clone()); }
    }
    private static final int MAX_ACCOUNTS = 10000, MAX_RESERVATIONS = 256, MAX_ORDINARY_OPERATIONS = 257, MAGIC = 0x4d435031;
    private final Path file;
    private final com.bencodez.mcperks.data.DurableFeatureFiles.Publisher publisher;
    private final com.bencodez.mcperks.data.FeatureFileOwnership ownership;
    private final ThreadPoolExecutor worker;
    private volatile Map<UUID, Account> accounts = Map.of();
    private volatile Map<UUID, Reservation> reservations = Map.of();
    // Keep the existing ordinary queue budget (one running + 256 waiting). Existing
    // durable identities each own a separate settlement slot on the same worker.
    private final Semaphore ordinarySlots = new Semaphore(MAX_ORDINARY_OPERATIONS);
    private final Map<UUID, CompletableFuture<Void>> settlements = new HashMap<>();
    private volatile boolean failed;
    private final CompletableFuture<Void> ready;

    public UsageCharges(Path file) { this(file, com.bencodez.mcperks.data.DurableFeatureFiles::publish); }
    UsageCharges(Path file, com.bencodez.mcperks.data.DurableFeatureFiles.Publisher publisher) {
        this.file = file; this.publisher = publisher;
        ownership = new com.bencodez.mcperks.data.FeatureFileOwnership(file);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_ORDINARY_OPERATIONS + MAX_RESERVATIONS - 1), r -> {
            Thread t = new Thread(r, "MCPerks-charge-store"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { ownership.drained(); }
        };
        ready = submit(() -> {
            try { ownership.awaitPredecessor(); }
            catch (RuntimeException | Error failure) { failed = true; throw failure; }
            read(); return null;
        });
    }
    public CompletableFuture<Void> ready() { return ready; }
    public boolean available() { return ready.isDone() && !ready.isCompletedExceptionally() && !failed; }
    public int get(UUID owner, Kind kind) {
        Account account = accounts.get(owner); return account == null ? 0 : account.remaining[kind.ordinal()];
    }
    public long successful(UUID owner, Kind kind) {
        Account account = accounts.get(owner); return account == null ? 0 : account.successful[kind.ordinal()];
    }
    public CompletableFuture<Integer> set(UUID owner, Kind kind, int amount) {
        if (amount < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Negative charge balance"));
        return mutate(owner, kind, amount, false);
    }
    public CompletableFuture<Integer> add(UUID owner, Kind kind, int amount) {
        if (amount < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Negative charge amount"));
        return mutate(owner, kind, amount, true);
    }
    private CompletableFuture<Integer> mutate(UUID owner, Kind kind, int amount, boolean add) {
        return submit(() -> {
            Map<UUID, Account> next = copyAccounts(); Account a = account(next, owner);
            a.remaining[kind.ordinal()] = add ? (int) Math.min(Integer.MAX_VALUE,
                    (long) a.remaining[kind.ordinal()] + amount) : amount;
            publish(next, reservations); return a.remaining[kind.ordinal()];
        });
    }
    /** Reserves up to the available budget, durably, before any irreversible effect. */
    public CompletableFuture<Reservation> reserve(UUID owner, Kind kind, int requested) {
        if (requested < 1 || requested > 64) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid budget"));
        return submit(() -> {
            if (reservations.size() >= MAX_RESERVATIONS) throw new IllegalStateException("Charge reservation capacity reached");
            int units = Math.min(get(owner, kind), requested);
            if (units == 0) return null;
            Map<UUID, Account> next = copyAccounts(); Account a = account(next, owner);
            a.remaining[kind.ordinal()] -= units;
            Reservation reservation = new Reservation(UUID.randomUUID(), owner, kind, units);
            Map<UUID, Reservation> pending = new HashMap<>(reservations); pending.put(reservation.id(), reservation);
            publish(next, pending); return reservation;
        });
    }
    /** Refund only units proved not to have had an effect. Uncertain units stay spent. */
    public CompletableFuture<Void> finish(Reservation reservation, int successful, int unused) {
        if (reservation == null || successful < 0 || unused < 0 || (long) successful + unused > reservation.units())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid completion"));
        synchronized (settlements) {
            CompletableFuture<Void> existing = settlements.get(reservation.id());
            // Only an exact live durable token can admit settlement. Duplicate callbacks
            // share the first completion rather than occupying another queue/waiter slot.
            if (!reservation.equals(reservations.get(reservation.id()))) return CompletableFuture.completedFuture(null);
            if (existing != null) return existing;
            CompletableFuture<Void> future = new CompletableFuture<>();
            settlements.put(reservation.id(), future);
            try { worker.execute(() -> {
                try {
                    if (failed) throw new IllegalStateException("Charge store unavailable until restart");
                    if (reservation.equals(reservations.get(reservation.id()))) {
                        Map<UUID, Account> next = copyAccounts(); Account a = account(next, reservation.owner());
                        int index = reservation.kind().ordinal();
                        a.remaining[index] = (int) Math.min(Integer.MAX_VALUE, (long) a.remaining[index] + unused);
                        a.successful[index] = saturatingAdd(a.successful[index], successful);
                        Map<UUID, Reservation> pending = new HashMap<>(reservations); pending.remove(reservation.id());
                        publish(next, pending);
                    }
                    future.complete(null);
                } catch (Throwable error) { future.completeExceptionally(error); }
                finally { synchronized (settlements) { settlements.remove(reservation.id(), future); } }
            }); } catch (RejectedExecutionException error) {
                settlements.remove(reservation.id(), future); future.completeExceptionally(error);
            }
            return future;
        }
    }
    public CompletableFuture<Void> record(UUID owner, Kind kind, int successful) {
        if (successful < 0 || successful > 64) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid count"));
        if (successful == 0) return CompletableFuture.completedFuture(null);
        return submit(() -> {
            Map<UUID, Account> next = copyAccounts(); Account a = account(next, owner);
            a.successful[kind.ordinal()] = saturatingAdd(a.successful[kind.ordinal()], successful);
            publish(next, reservations); return null;
        });
    }
    private static long saturatingAdd(long old, int amount) { return old > Long.MAX_VALUE - amount ? Long.MAX_VALUE : old + amount; }
    private Account account(Map<UUID, Account> next, UUID owner) {
        Account a = next.get(Objects.requireNonNull(owner));
        if (a == null) {
            if (next.size() >= MAX_ACCOUNTS) throw new IllegalStateException("Charge account capacity reached");
            a = new Account(new int[Kind.values().length], new long[Kind.values().length]); next.put(owner, a);
        }
        return a;
    }
    private Map<UUID, Account> copyAccounts() {
        Map<UUID, Account> result = new HashMap<>(); accounts.forEach((id, a) -> result.put(id, a.copy())); return result;
    }
    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!ordinarySlots.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Charge operation capacity reached"));
        try { worker.execute(() -> {
            try {
                if (failed) throw new IllegalStateException("Charge store unavailable until restart");
                future.complete(operation.get());
            } catch (Throwable error) { future.completeExceptionally(error); }
            finally { ordinarySlots.release(); }
        }); } catch (RejectedExecutionException e) { ordinarySlots.release(); future.completeExceptionally(e); }
        return future;
    }
    private void publish(Map<UUID, Account> next, Map<UUID, Reservation> pending) {
        Path staged = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.isSymbolicLink(file)) throw new IOException("Charge file is a symlink");
            staged = Files.createTempFile(file.toAbsolutePath().getParent(), ".charges-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
                out.writeInt(MAGIC); out.writeInt(next.size());
                for (Map.Entry<UUID, Account> entry : next.entrySet()) {
                    writeUuid(out, entry.getKey());
                    for (int i = 0; i < Kind.values().length; i++) { out.writeInt(entry.getValue().remaining[i]); out.writeLong(entry.getValue().successful[i]); }
                }
                out.writeInt(pending.size());
                for (Reservation r : pending.values()) { writeUuid(out, r.id()); writeUuid(out, r.owner()); out.writeInt(r.kind().ordinal()); out.writeInt(r.units()); }
            }
            publisher.publish(staged, file);
            accounts = Collections.unmodifiableMap(next); reservations = Collections.unmodifiableMap(pending);
        } catch (IOException e) {
            failed = true; throw new CompletionException(e); // publication may be uncertain; freeze all future effects
        } finally { if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {} }
    }
    private void read() {
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Charge file is a symlink");
            if (!Files.exists(file)) return;
            if (Files.size(file) > 2_000_000) throw new IOException("Oversized charge ledger");
            Map<UUID, Account> recovered = new HashMap<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid charge ledger version");
                int count = bounded(in.readInt(), MAX_ACCOUNTS);
                for (int i = 0; i < count; i++) {
                    UUID id = readUuid(in); Account a = new Account(new int[3], new long[3]);
                    for (int j = 0; j < 3; j++) { a.remaining[j] = in.readInt(); a.successful[j] = in.readLong(); if (a.remaining[j] < 0 || a.successful[j] < 0) throw new IOException("Invalid counters"); }
                    if (recovered.put(id, a) != null) throw new IOException("Duplicate account");
                }
                int countReservations = bounded(in.readInt(), MAX_RESERVATIONS); Set<UUID> ids = new HashSet<>();
                for (int i = 0; i < countReservations; i++) {
                    UUID id = readUuid(in), owner = readUuid(in); int kind = in.readInt(), units = in.readInt();
                    if (!ids.add(id) || !recovered.containsKey(owner) || kind < 0 || kind >= 3 || units < 1 || units > 64) throw new IOException("Invalid reservation");
                }
                if (in.read() != -1) throw new IOException("Trailing charge ledger data");
            }
            // Recovery never refunds possibly executed gameplay. Old tokens cannot authorize callbacks.
            accounts = Collections.unmodifiableMap(recovered); reservations = Map.of();
        } catch (IOException e) { failed = true; throw new CompletionException(e); }
    }
    private static int bounded(int value, int max) throws IOException { if (value < 0 || value > max) throw new IOException("Invalid ledger count"); return value; }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    @Override public void close() { worker.shutdown(); } // Drain queued writes without blocking an owner thread.
}
