package store.cadera.shop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class Filesafe {
    private Filesafe() {}
    static void save(YamlConfiguration yaml, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(yaml.saveToString());
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static String id(String id) {
        if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("ID: a-z, 0-9, _ atau -, maks 48 karakter.");
        return id;
    }
}
