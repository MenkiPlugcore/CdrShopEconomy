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
    private static final int MAX_DETAIL_CHARS = 4_000_000;
    private final Map<UUID, UUID> pending = new LinkedHashMap<>();
    private final FileChannel channel;
    private boolean healthy = true;

    Journal(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String[] parts = line.split("\\t", 6);
                try {
                    if (parts.length < 5) throw new IllegalArgumentException("kolom kurang");
                    UUID tx = UUID.fromString(parts[2]), player = UUID.fromString(parts[3]);
                    if (parts[1].equals("BEGIN")) {
                        if (pending.putIfAbsent(tx, player) != null)
                            throw new IllegalArgumentException("BEGIN duplikat");
                    } else if (Set.of("COMMIT", "ABORT", "RESOLVED").contains(parts[1])) {
                        UUID owner = pending.remove(tx);
                        if (owner == null || !owner.equals(player))
                            throw new IllegalArgumentException("terminal tanpa BEGIN yang cocok");
                    } else throw new IllegalArgumentException("state tidak dikenal");
                } catch (RuntimeException ex) {
                    throw new IOException("Journal rusak di baris " + lineNo + "; review file sebelum membuka transaksi.", ex);
                }
            }
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    Map<UUID, UUID> pending() { return Map.copyOf(pending); }
    boolean blocked(UUID player) { return pending.containsValue(player); }

    UUID begin(UUID player, String detail) throws IOException {
        Objects.requireNonNull(player, "player");
        validateDetail(detail);
        if (!healthy) throw new IOException("Journal I/O gagal sebelumnya; restart setelah perbaikan storage.");
        UUID tx = UUID.randomUUID();
        pending.put(tx, player); // Keep locked even when the write partially fails.
        append("BEGIN", tx, player, detail);
        return tx;
    }

    void finish(UUID tx, String state, String detail) throws IOException {
        validateDetail(detail);
        UUID player = pending.get(tx);
        if (player == null) throw new IllegalArgumentException("Transaksi pending tidak ditemukan.");
        if (!Set.of("COMMIT", "ABORT", "RESOLVED").contains(state)) throw new IllegalArgumentException("Status salah.");
        append(state, tx, player, detail);
        pending.remove(tx);
    }

    private static void validateDetail(String detail) {
        if (detail == null) throw new IllegalArgumentException("Detail journal null.");
        if (detail.length() > MAX_DETAIL_CHARS)
            throw new IllegalArgumentException("Detail journal terlalu besar; transaksi ditolak untuk melindungi disk/main thread.");
    }

    private void append(String state, UUID tx, UUID player, String detail) throws IOException {
        if (!healthy) throw new IOException("Journal tidak sehat; transaksi dihentikan.");
        String sanitized = detail.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        String line = Instant.now() + "\t" + state + "\t" + tx + "\t" + player + "\t" + sanitized + "\n";
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
