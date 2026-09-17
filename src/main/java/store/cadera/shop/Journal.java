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
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] parts = line.split("\\t", 5);
                try {
                    if (parts.length != 5) throw new IllegalArgumentException("kolom tidak lengkap");
                    String state = parts[1];
                    UUID tx = UUID.fromString(parts[2]);
                    UUID player = UUID.fromString(parts[3]);
                    if (state.equals("BEGIN")) {
                        if (pending.containsKey(tx)) throw new IllegalArgumentException("BEGIN tx duplikat");
                        if (pending.containsValue(player)) throw new IllegalArgumentException("player memiliki lebih dari satu transaksi pending");
                        pending.put(tx, player);
                    } else if (Set.of("COMMIT", "ABORT", "RESOLVED").contains(state)) {
                        UUID expectedPlayer = pending.get(tx);
                        if (expectedPlayer == null) throw new IllegalArgumentException("terminal record tanpa BEGIN pending");
                        if (!expectedPlayer.equals(player)) throw new IllegalArgumentException("player terminal record tidak cocok");
                        pending.remove(tx);
                    } else {
                        throw new IllegalArgumentException("state tidak dikenal");
                    }
                } catch (RuntimeException ex) {
                    throw new IOException("Journal rusak pada baris " + lineNumber + "; review file sebelum membuka transaksi.", ex);
                }
            }
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    Map<UUID, UUID> pending() { return Map.copyOf(pending); }
    boolean blocked(UUID player) { return pending.containsValue(player); }

    UUID begin(UUID player, String detail) throws IOException {
        if (!healthy) throw new IOException("Journal I/O gagal sebelumnya; restart setelah perbaikan storage.");
        if (blocked(player)) throw new IllegalStateException("Player sudah memiliki transaksi pending.");
        UUID tx = UUID.randomUUID();
        pending.put(tx, player); // Keep locked even when the write partially fails.
        append("BEGIN", tx, player, detail);
        return tx;
    }

    void finish(UUID tx, String state, String detail) throws IOException {
        UUID player = pending.get(tx);
        if (player == null) throw new IllegalArgumentException("Transaksi pending tidak ditemukan.");
        if (!Set.of("COMMIT", "ABORT", "RESOLVED").contains(state)) throw new IllegalArgumentException("Status salah.");
        append(state, tx, player, detail);
        pending.remove(tx);
    }

    private void append(String state, UUID tx, UUID player, String detail) throws IOException {
        if (!healthy) throw new IOException("Journal tidak sehat; transaksi dihentikan.");
        String safeDetail = detail == null ? "" : detail.replaceAll("[\\r\\n\\t]", " ");
        String line = Instant.now() + "\t" + state + "\t" + tx + "\t" + player + "\t" + safeDetail + "\n";
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
        try {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e) {
            healthy = false;
            throw e;
        }
    }

    @Override public void close() throws IOException { channel.close(); }
}
