package store.cadera.shop;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Append-only write-ahead log. Unknown outcomes require manual review, never automatic replay. */
final class Journal implements AutoCloseable {
    private final Map<UUID, UUID> pending = new LinkedHashMap<>();
    private final FileChannel channel;
    private boolean healthy = true;
    Journal(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", 6);
                try {
                    if (parts.length < 5) throw new IllegalArgumentException();
                    UUID tx = UUID.fromString(parts[2]), player = UUID.fromString(parts[3]);
                    if (parts[1].equals("BEGIN")) pending.put(tx, player);
                    else if (Set.of("COMMIT", "ABORT", "RESOLVED").contains(parts[1])) pending.remove(tx);
                    else throw new IllegalArgumentException();
                } catch (RuntimeException ex) { throw new IOException("Journal rusak; review file sebelum membuka transaksi.", ex); }
            }
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }
    Map<UUID, UUID> pending() { return Map.copyOf(pending); }
    boolean blocked(UUID player) { return pending.containsValue(player); }
    UUID begin(UUID player, String detail) throws IOException {
        if (!healthy) throw new IOException("Journal I/O gagal sebelumnya; restart setelah perbaikan storage.");
        UUID tx = UUID.randomUUID();
        pending.put(tx, player); // Keep locked even when the write partially fails.
        append("BEGIN", tx, player, detail); return tx;
    }
    void finish(UUID tx, String state, String detail) throws IOException {
        UUID player = pending.get(tx);
        if (player == null) throw new IllegalArgumentException("Transaksi pending tidak ditemukan.");
        if (!Set.of("COMMIT", "ABORT", "RESOLVED").contains(state)) throw new IllegalArgumentException("Status salah.");
        append(state, tx, player, detail); pending.remove(tx);
    }
    private void append(String state, UUID tx, UUID player, String detail) throws IOException {
        if (!healthy) throw new IOException("Journal tidak sehat; transaksi dihentikan.");
        String line = Instant.now() + "\t" + state + "\t" + tx + "\t" + player + "\t" + detail.replaceAll("[\\r\\n\\t]", " ") + "\n";
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
        try {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e) { healthy = false; throw e; }
    }
    @Override public void close() throws IOException { channel.close(); }
}
