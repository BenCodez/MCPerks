package com.bencodez.mcperks.userapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;

/** Per-user receipt and balance updates must commit in the same SQL transaction. */
final class ReplayAwardLedger {
    static final String COLUMN = "MCPerksAwardReceipts";
    private static final int MAX_ENCODED = 60000;
    private ReplayAwardLedger() { }

    static boolean apply(SqlUserStorage.TransactionScope scope, String balanceKey, int amount, String identity) throws SQLException {
        String receipt = receipt(identity);
        var row = scope.readRow();
        if (!hasColumn(row, COLUMN)) throw rejected("Award receipt column is unavailable");
        if (!hasColumn(row, balanceKey)) throw rejected("Award balance column is unavailable");
        DataValue balance = find(row, balanceKey);
        DataValue stored = find(row, COLUMN);
        Set<String> receipts = decode(stored == null ? "" : stored.getString());
        String entry = receipt + ":" + receipt(balanceKey + "\0" + amount);
        for (String existing : receipts) if (existing.startsWith(receipt + ":")) {
            if (!existing.equals(entry)) throw rejected("Replay award identity has a different intent");
            return false;
        }
        int updated;
        try { updated = Math.addExact(balance == null ? 0 : balance.getInt(), amount); }
        catch (IllegalArgumentException | ArithmeticException failure) { throw rejected("Award balance is invalid or outside the supported range"); }
        receipts.add(entry);
        String encoded = String.join("\n", receipts);
        if (encoded.length() > MAX_ENCODED) throw rejected("Award receipt capacity reached");
        scope.writeValues(Map.of(balanceKey, new DataValueInt(updated), COLUMN, new DataValueString(encoded)));
        return true;
    }

    static void retire(SqlUserStorage.TransactionScope scope, String identity) throws SQLException {
        if (scope.createdUserRow()) throw rejected("Replay checkpoint user is unavailable");
        var row = scope.readRow();
        if (!hasColumn(row, COLUMN)) throw rejected("Award receipt column is unavailable");
        var stored = find(row, COLUMN);
        if (stored == null) return;
        Set<String> receipts = decode(stored.getString());
        String prefix = receipt(identity) + ":";
        if (receipts.removeIf(value -> value.startsWith(prefix))) scope.writeValues(Map.of(COLUMN, new DataValueString(String.join("\n", receipts))));
    }

    private static DataValue find(java.util.List<Column> row, String key) {
        if (row == null) return null;
        DataValue found = null; boolean matched = false;
        for (Column column : row) if (key.equalsIgnoreCase(column.getName())) {
            if (matched) throw rejected("Ambiguous award column");
            matched = true; found = column.getValue();
        }
        return found;
    }

    private static boolean hasColumn(java.util.List<Column> row, String key) {
        if (row == null) return false;
        int matches = 0;
        for (Column column : row) if (key.equalsIgnoreCase(column.getName())) matches++;
        if (matches > 1) throw rejected("Ambiguous award column");
        return matches == 1;
    }

    private static Set<String> decode(String text) {
        Set<String> receipts = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return receipts;
        if (text.length() > MAX_ENCODED) throw rejected("Award receipt capacity reached");
        for (String value : text.split("\n", -1)) {
            if (!value.matches("[0-9a-f]{64}:[0-9a-f]{64}") || !receipts.add(value)) throw rejected("Invalid award receipt data");
        }
        return receipts;
    }

    private static String receipt(String identity) {
        if (identity == null || identity.isEmpty()) throw rejected("Replay identity is required");
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static SqlUserStorage.ReplayPreconditionException rejected(String message) {
        return new SqlUserStorage.ReplayPreconditionException(message);
    }
}
