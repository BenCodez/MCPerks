package com.bencodez.mcperks.data;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
/** Bounded, ordered persistence for queue identities and opt-in personal banked time. */
public final class FeatureState implements AutoCloseable {
    private static final long MAX_FILE_BYTES = 3_000_000;
    public enum Stage { WAITING, STARTING, UNCERTAIN }
    public record QueueEntry(UUID id, UUID owner, String perk, long admitted, Stage stage, String credit) {
        public QueueEntry withStage(Stage next) { return new QueueEntry(id, owner, perk, admitted, next, credit); }
    }
    public record BankKey(UUID owner, String perk) {}
    public record Bank(long milliseconds, boolean paused) {}
    private final Path file;
    private final DurableFeatureFiles.Publisher publisher;
    private final com.bencodez.mcperks.data.FeatureFileOwnership ownership;
    private final ThreadPoolExecutor worker;
    private volatile Thread ownerThread;
    private volatile List<QueueEntry> queue = List.of();
    private volatile Map<BankKey, Bank> banks = Map.of();
    private final Object bankBoundaryLock = new Object();
    // Banks are never removed and are capped at 10,000. Retain one epoch per replaced
    // bank, rather than retaining every disconnected clock during a blocked publish.
    private final ConcurrentHashMap<BankKey, Long> bankReplacementVersions = new ConcurrentHashMap<>();
    private volatile boolean paused, failed, ownershipFailed;
    public record BankGrant(long elapsed, Runnable commit, Runnable rollback) {}
    private volatile java.util.function.BiFunction<BankKey, Long, BankGrant> bankGrantBoundary = (key, oldBalance) -> new BankGrant(0, () -> {}, () -> {});
    private volatile java.util.function.Function<BankKey, BankGrant> bankReplacementBoundary = key -> new BankGrant(0, () -> {}, () -> {});
    private final CompletableFuture<Void> ready;
    public FeatureState(Path file) { this(file, DurableFeatureFiles::publish); }
    FeatureState(Path file, DurableFeatureFiles.Publisher publisher) {
        this.file = file; this.publisher = publisher;
        ownership = new com.bencodez.mcperks.data.FeatureFileOwnership(file);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
            Thread t = new Thread(r, "MCPerks-feature-store"); t.setDaemon(true); ownerThread = t; return t;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { ownership.drained(); }
        };
        ready = submit(() -> {
            try { ownership.awaitPredecessor(); }
            catch (RuntimeException | Error failure) { ownershipFailed = true; failed = true; throw failure; }
            read(); return null;
        });
    }
    public CompletableFuture<Void> ready() { return ready; }
    public boolean available() { return ready.isDone() && !ready.isCompletedExceptionally() && !failed; }
    public List<QueueEntry> queue() { return queue; }
    public Map<BankKey, Bank> banks() { return banks; }
    public long bankReplacementVersion(BankKey key) { return bankReplacementVersions.getOrDefault(key, 0L); }
    /** Memory-only clock creation/reservation; never submit storage or perform I/O in this turn. */
    public <T> T bankCheckpoint(Supplier<T> checkpoint) {
        synchronized (bankBoundaryLock) { return checkpoint.get(); }
    }
    /** Register the clock boundary used when a grant is published on this ordered store. */
    public void onBankGrant(java.util.function.BiFunction<BankKey, Long, BankGrant> boundary) {
        bankGrantBoundary = java.util.Objects.requireNonNull(boundary);
    }
    /** Replacements establish a new clock/debit ownership boundary after publication. */
    public void onBankReplacement(java.util.function.Function<BankKey, BankGrant> boundary) {
        bankReplacementBoundary = java.util.Objects.requireNonNull(boundary);
    }
    public boolean paused() { return paused; }
    public int reserved(UUID owner, String credit) { return (int) queue.stream().filter(e -> e.owner.equals(owner) && e.credit.equals(credit)).count(); }
    public CompletableFuture<QueueEntry> enqueue(UUID owner, String perk, String credit, int available) {
        return admit(owner, perk, credit, available, Stage.WAITING);
    }
    /** Direct starts never enter WAITING, so normal queue checks cannot schedule them. */
    public CompletableFuture<QueueEntry> startDirect(UUID owner, String perk, String credit, int available) {
        return admit(owner, perk, credit, available, Stage.STARTING);
    }
    private CompletableFuture<QueueEntry> admit(UUID owner, String perk, String credit, int available, Stage stage) {
        return submit(() -> {
            if (queue.size() >= 256) throw new IllegalStateException("Queue capacity reached");
            if (queue.stream().anyMatch(e -> e.owner.equals(owner))) throw new IllegalStateException("Player already queued");
            if (!credit.isEmpty() && available <= reserved(owner, credit)) throw new IllegalStateException("Activation already reserved");
            QueueEntry entry = new QueueEntry(UUID.randomUUID(), owner, boundedName(perk), System.currentTimeMillis(), stage, boundedCredit(credit));
            var next = new ArrayList<>(queue); next.add(entry); publish(next, banks, paused); return entry;
        });
    }
    /** Persist the irreversible start fence before consuming credit or applying effects. */
    public CompletableFuture<QueueEntry> claim(UUID id) {
        return submit(() -> {
            if (paused || queue.isEmpty() || !queue.getFirst().id.equals(id) || queue.getFirst().stage != Stage.WAITING) return null;
            var next = new ArrayList<>(queue); QueueEntry entry = next.getFirst().withStage(Stage.STARTING); next.set(0, entry);
            publish(next, banks, paused); return entry;
        });
    }
    public CompletableFuture<Boolean> cancel(UUID id, UUID owner, boolean staff) {
        return submit(() -> {
            var next = new ArrayList<>(queue);
            boolean removed = next.removeIf(e -> e.id.equals(id) && (staff || e.owner.equals(owner)) && (e.stage == Stage.WAITING || staff && e.stage == Stage.UNCERTAIN));
            if (removed) publish(next, banks, paused); return removed;
        });
    }
    public CompletableFuture<Void> complete(UUID id) {
        return submit(() -> { var next = new ArrayList<>(queue); next.removeIf(e -> e.id.equals(id) && e.stage == Stage.STARTING); publish(next, banks, paused); return null; });
    }
    public CompletableFuture<Void> uncertain(UUID id) {
        return submit(() -> { var next = new ArrayList<>(queue); next.replaceAll(e -> e.id.equals(id) ? e.withStage(Stage.UNCERTAIN) : e); publish(next, banks, paused); return null; });
    }
    public CompletableFuture<Void> releaseClaim(UUID id) {
        return submit(() -> { var next = new ArrayList<>(queue); next.replaceAll(e -> e.id.equals(id) && e.stage == Stage.STARTING ? e.withStage(Stage.WAITING) : e); publish(next, banks, paused); return null; });
    }
    public CompletableFuture<Boolean> move(UUID id, int position) {
        return submit(() -> {
            var next = new ArrayList<>(queue); QueueEntry selected = next.stream().filter(e -> e.id.equals(id) && e.stage == Stage.WAITING).findFirst().orElse(null);
            if (selected == null || queue.stream().anyMatch(e -> e.stage == Stage.STARTING)) return false;
            next.remove(selected); next.add(Math.max(0, Math.min(position - 1, next.size())), selected); publish(next, banks, paused); return true;
        });
    }
    public CompletableFuture<Void> pauseQueue(boolean value) { return submit(() -> { publish(queue, banks, value); return null; }); }
    public CompletableFuture<Void> clearWaiting() { return submit(() -> { publish(queue.stream().filter(e -> e.stage != Stage.WAITING).toList(), banks, paused); return null; }); }
    public CompletableFuture<Bank> setBank(BankKey key, long milliseconds) {
        if (milliseconds < 0 || milliseconds > 31_536_000_000L) return CompletableFuture.failedFuture(new IllegalArgumentException("Banked time must be between zero and one year"));
        return submit(() -> {
            boundedName(key.perk); if (!banks.containsKey(key) && (banks.size() >= 10000 || banks.keySet().stream().filter(k -> k.owner.equals(key.owner)).count() >= 64)) throw new IllegalStateException("Bank capacity reached");
            var next = new HashMap<>(banks); Bank bank = new Bank(milliseconds, false); next.put(key, bank);
            BankGrant boundary = bankReplacementBoundary.apply(key);
            try {
                publish(queue, next, paused, () -> {
                    bankReplacementVersions.merge(key, 1L, Long::sum);
                    boundary.commit().run();
                });
                return bank;
            }
            catch (RuntimeException | Error failure) { boundary.rollback().run(); throw failure; }
        });
    }
    public CompletableFuture<Bank> addBank(BankKey key, long milliseconds) {
        return storage(() -> { Bank bank = addBankOnWorker(key, milliseconds, () -> true);
            if (bank == null) throw new IllegalStateException("Bank capacity reached"); return bank; });
    }
    /** Called only inside storage(): capacity and debit share this store's ordered ownership. */
    public Bank addBankOnWorker(BankKey key, long milliseconds, java.util.function.BooleanSupplier debit) {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Bank grant requires state worker ownership");
        if (milliseconds < 1 || milliseconds > 31_536_000_000L) throw new IllegalArgumentException("Invalid bank grant");
        boundedName(key.perk); Bank old = banks.get(key);
        if (old == null && (banks.size() >= 10000 || banks.keySet().stream().filter(k -> k.owner.equals(key.owner)).count() >= 64)) return null;
        long remaining = old == null ? 0 : old.milliseconds;
        if (milliseconds > 31_536_000_000L - remaining) return null;
        // Deterministic byte capacity must reject before an irreversible activation debit.
        var capacityCandidate = new HashMap<>(banks);
        capacityCandidate.put(key, new Bank(remaining + milliseconds, old != null && old.paused));
        if (!fitsSerializedCapacity(queue, capacityCandidate, paused)) return null;
        if (!debit.getAsBoolean()) return null;
        BankGrant boundary = bankGrantBoundary.apply(key, remaining);
        long elapsedBeforeGrant = Math.min(remaining, boundary.elapsed());
        Bank bank = new Bank(remaining + milliseconds - elapsedBeforeGrant, old != null && old.paused);
        var next = new HashMap<>(banks); next.put(key, bank);
        try { publish(queue, next, paused, boundary.commit()); return bank; }
        catch (RuntimeException | Error failure) { boundary.rollback().run(); throw failure; }
    }
    public CompletableFuture<Bank> pauseBank(BankKey key, boolean value) {
        return submit(() -> { Bank old = banks.get(key); if (old == null) return null; var next = new HashMap<>(banks);
            Bank bank = new Bank(old.milliseconds, value); next.put(key, bank); publish(queue, next, paused); return bank; });
    }
    public CompletableFuture<Bank> elapsed(BankKey key, long milliseconds) {
        return elapsed(key, milliseconds, () -> true);
    }
    /** A delayed controller debit must still own the clock's published bank replacement. */
    public CompletableFuture<Bank> elapsed(BankKey key, long milliseconds, java.util.function.BooleanSupplier ownsReplacement) {
        if (milliseconds < 0 || milliseconds > 31_536_000_000L) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid bank checkpoint"));
        return submit(() -> { Bank old = banks.get(key); if (!ownsReplacement.getAsBoolean() || old == null || old.paused) return old; var next = new HashMap<>(banks);
            Bank bank = new Bank(Math.max(0, old.milliseconds - milliseconds), false); next.put(key, bank); publish(queue, next, paused); return bank; });
    }
    /** Database confirmation runs on this ordered worker, never on a player/server owner. */
    public <T> CompletableFuture<T> storage(Supplier<T> work) { return submit(work); }
    /** Independent legacy YAML uses the same ordered capacity, even if optional feature data is unreadable. */
    public <T> CompletableFuture<T> runtimeStorage(Supplier<T> work) { return submit(work, false); }
    private <T> CompletableFuture<T> submit(Supplier<T> operation) { return submit(operation, true); }
    private <T> CompletableFuture<T> submit(Supplier<T> operation, boolean requireFeatureData) {
        var future = new CompletableFuture<T>();
        try { worker.execute(() -> {
            if (ownershipFailed || requireFeatureData && failed) { future.completeExceptionally(new IllegalStateException("Feature store unavailable until restart")); return; }
            try { future.complete(operation.get()); } catch (Throwable error) { future.completeExceptionally(error); }
        }); } catch (RejectedExecutionException error) { future.completeExceptionally(error); }
        return future;
    }
    private void publish(List<QueueEntry> entries, Map<BankKey, Bank> bankValues, boolean queuePaused) {
        publish(entries, bankValues, queuePaused, () -> {});
    }
    private void publish(List<QueueEntry> entries, Map<BankKey, Bank> bankValues, boolean queuePaused, Runnable bankCommit) {
        Path staged = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent()); if (Files.isSymbolicLink(file)) throw new IOException("Feature file is a symlink");
            staged = Files.createTempFile(file.toAbsolutePath().getParent(), ".feature-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
                writeState(out, entries, bankValues, queuePaused);
            }
            // writeUTF uses modified UTF-8, so count the actual complete serialized bytes.
            // Capacity rejection leaves the previous publication usable, unlike uncertain I/O failure.
            if (Files.size(staged) > MAX_FILE_BYTES) throw new IllegalStateException("Feature state byte capacity reached");
            publisher.publish(staged, file);
            var confirmedQueue = List.copyOf(entries); var confirmedBanks = Map.copyOf(bankValues);
            // No filesystem work occurs under this monitor. A late clock cannot be
            // created/reserved between its committed boundary and balance exposure.
            synchronized (bankBoundaryLock) {
                bankCommit.run();
                queue = confirmedQueue; banks = confirmedBanks; paused = queuePaused;
            }
        } catch (IOException error) { failed = true; throw new CompletionException(error); }
        finally { if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {} }
    }
    private static void writeState(DataOutputStream out, List<QueueEntry> entries,
            Map<BankKey, Bank> bankValues, boolean queuePaused) throws IOException {
        out.writeInt(0x4d435032); out.writeBoolean(queuePaused); out.writeInt(entries.size());
        for (QueueEntry entry : entries) { uuid(out, entry.id); uuid(out, entry.owner); out.writeUTF(entry.perk); out.writeLong(entry.admitted); out.writeInt(entry.stage.ordinal()); out.writeUTF(entry.credit); }
        out.writeInt(bankValues.size());
        for (var entry : bankValues.entrySet()) { uuid(out, entry.getKey().owner); out.writeUTF(entry.getKey().perk); out.writeLong(entry.getValue().milliseconds); out.writeBoolean(entry.getValue().paused); }
    }
    private static boolean fitsSerializedCapacity(List<QueueEntry> entries, Map<BankKey, Bank> bankValues,
            boolean queuePaused) {
        // The real serializer counts modified UTF-8 exactly without allocating a full snapshot.
        var out = new DataOutputStream(java.io.OutputStream.nullOutputStream());
        try { writeState(out, entries, bankValues, queuePaused); return out.size() <= MAX_FILE_BYTES; }
        catch (IOException invalid) { throw new CompletionException(invalid); }
    }
    private void read() {
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Feature file is a symlink");
            if (!Files.exists(file)) return; if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Oversized feature state");
            var entries = new ArrayList<QueueEntry>(); var bankValues = new HashMap<BankKey, Bank>(); Set<UUID> ids = new HashSet<>(), owners = new HashSet<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != 0x4d435032) throw new IOException("Invalid feature state"); boolean queuePaused = in.readBoolean();
                int count = count(in.readInt(), 256);
                for (int i = 0; i < count; i++) {
                    UUID id = uuid(in), owner = uuid(in); String perk = boundedName(in.readUTF()); long admitted = in.readLong(); int stage = count(in.readInt(), Stage.values().length-1); String credit = boundedCredit(in.readUTF());
                    if (!ids.add(id) || !owners.add(owner) || admitted < 0) throw new IOException("Duplicate/invalid queue identity");
                    entries.add(new QueueEntry(id, owner, perk, admitted, stage == Stage.STARTING.ordinal() ? Stage.UNCERTAIN : Stage.values()[stage], credit));
                }
                int bankCount = count(in.readInt(), 10000); Map<UUID, Integer> perOwner = new HashMap<>();
                for (int i = 0; i < bankCount; i++) {
                    BankKey key = new BankKey(uuid(in), boundedName(in.readUTF()));
                    if (perOwner.merge(key.owner(), 1, Integer::sum) > 64) throw new IOException("Too many banks for one player");
                    long value = in.readLong(); boolean bankPaused = in.readBoolean();
                    if (value < 0 || value > 31_536_000_000L || bankValues.put(key, new Bank(value, bankPaused)) != null) throw new IOException("Invalid bank value");
                }
                if (in.read() != -1) throw new IOException("Trailing feature state"); queue = List.copyOf(entries); banks = Map.copyOf(bankValues); paused = queuePaused;
            }
        } catch (IOException | IllegalArgumentException error) { failed = true; throw new CompletionException(error); }
    }
    private static String boundedName(String name) { if (name == null || name.isEmpty() || name.length() > 128 || name.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid perk name"); return name; }
    private static String boundedCredit(String credit) { if (credit == null || credit.length() > 144 || !(credit.isEmpty() || credit.equals("Activations") || credit.startsWith("PerkActivations_") && validCreditName(credit.substring(16)))) throw new IllegalArgumentException("Invalid credit key"); return credit; }
    private static boolean validCreditName(String name) { try { boundedName(name); return true; } catch (IllegalArgumentException invalid) { return false; } }
    private static int count(int value, int maximum) throws IOException { if (value < 0 || value > maximum) throw new IOException("Invalid state count"); return value; }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    @Override public void close() { worker.shutdown(); }
}
