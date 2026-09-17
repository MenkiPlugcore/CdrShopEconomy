package store.cadera.shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Compact operational history. The write-ahead journal remains the authority for recovery. */
final class History {
    private final Path path;

    History(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) Files.createFile(path);
    }

    void append(String status, UUID player, String name, String action, String shop, long cents, int count) throws IOException {
        String line = String.join("\t",
            Instant.now().toString(),
            clean(status),
            player.toString(),
            clean(name),
            clean(action),
            clean(shop == null ? "all" : shop),
            Long.toString(cents),
            Integer.toString(count)
        ) + "\n";
        Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    List<String> tail(String filter, int limit) throws IOException {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Limit history harus 1-50.");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
            String[] p = lines.get(i).split("\\t", 8);
            if (p.length != 8) continue;
            if (filter != null && !filter.equalsIgnoreCase(p[2]) && !filter.equalsIgnoreCase(p[3])) continue;
            try {
                long cents = Long.parseLong(p[6]);
                int count = Integer.parseInt(p[7]);
                out.add(p[0] + " | " + p[1] + " " + p[4] + " | " + p[3] + " | toko=" + p[5]
                    + " | x" + count + " | $" + Money.format(cents));
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private static String clean(String value) {
        return (value == null ? "" : value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
