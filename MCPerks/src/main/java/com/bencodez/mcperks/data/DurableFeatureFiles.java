package com.bencodez.mcperks.data;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
/** Forces staged contents and atomic publication. A post-move error has an uncertain outcome. */
public final class DurableFeatureFiles {
    private DurableFeatureFiles() {}
    @FunctionalInterface public interface Publisher { void publish(Path staged, Path file) throws IOException; }
    public static void publish(Path staged, Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel channel = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) { channel.force(true); }
        catch (AccessDeniedException error) { if (!System.getProperty("os.name", "").startsWith("Windows")) throw error; }
        catch (UnsupportedOperationException ignored) { /* Provider does not expose directory forcing. */ }
    }
}
