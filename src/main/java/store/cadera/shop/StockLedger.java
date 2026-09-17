package store.cadera.shop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Durable runtime stock state. All mutation is expected on the primary server thread. */
final class StockLedger {
    record Change(String shop, Catalog.Product product, int delta) {
        Change {
            Filesafe.id(shop);
            Objects.requireNonNull(product, "product");
            if (delta == 0) throw new IllegalArgumentException("Delta stok tidak boleh 0.");
        }
        String key() { return shop + "." + product.id(); }
    }

    private final Path path;
    private Map<String, Integer> levels = Map.of();

    StockLedger(Path path) throws IOException {
        this.path = path;
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(path)) { levels = Map.of(); return; }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(path.toFile());
            Map<String, Integer> next = new LinkedHashMap<>();
            ConfigurationSection shops = yaml.getConfigurationSection("levels");
            if (shops != null) for (String shop : shops.getKeys(false)) {
                Filesafe.id(shop);
                ConfigurationSection products = shops.getConfigurationSection(shop);
                if (products == null) throw new IllegalArgumentException("Struktur stock.yml salah pada " + shop);
                for (String product : products.getKeys(false)) {
                    Filesafe.id(product);
                    int amount = products.getInt(product, -1);
                    if (amount < 0) throw new IllegalArgumentException("Stok negatif: " + shop + "/" + product);
                    next.put(shop + "." + product, amount);
                }
            }
            levels = Map.copyOf(next);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("stock.yml rusak; perbaiki sebelum membuka transaksi.", e);
        }
    }

    int current(String shop, Catalog.Product product) {
        if (!product.finiteStock()) return Integer.MAX_VALUE;
        return levels.getOrDefault(shop + "." + product.id(), product.stockInitial());
    }

    Map<String, Integer> snapshot(List<Change> changes) {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (Change change : changes) if (change.product().finiteStock())
            snapshot.putIfAbsent(change.key(), current(change.shop(), change.product()));
        return Map.copyOf(snapshot);
    }

    void apply(List<Change> changes) throws IOException {
        if (changes.isEmpty()) return;
        Map<String, Integer> next = new LinkedHashMap<>(levels);
        boolean touched = false;
        for (Change change : changes) {
            Catalog.Product product = change.product();
            if (!product.finiteStock()) continue;
            touched = true;
            String key = change.key();
            int current = next.getOrDefault(key, product.stockInitial());
            long candidate = (long) current + change.delta();
            if (candidate < 0) throw new IllegalArgumentException("Stok tidak cukup untuk " + change.shop() + "/" + product.id());
            int value = (int) Math.min(product.stockMax(), candidate);
            next.put(key, value);
        }
        if (touched) saveAndReplace(next);
    }

    void restore(Map<String, Integer> snapshot) throws IOException {
        if (snapshot.isEmpty()) return;
        Map<String, Integer> next = new LinkedHashMap<>(levels);
        next.putAll(snapshot);
        saveAndReplace(next);
    }

    void reconcile(Catalog catalog) throws IOException {
        Map<String, Integer> next = new LinkedHashMap<>();
        for (Catalog.Shop shop : catalog.all()) for (Catalog.Product product : shop.products()) if (product.finiteStock()) {
            String key = shop.id() + "." + product.id();
            int existing = levels.getOrDefault(key, product.stockInitial());
            next.put(key, Math.max(0, Math.min(product.stockMax(), existing)));
        }
        if (!next.equals(levels)) saveAndReplace(next);
    }

    int restock(Catalog catalog) throws IOException {
        Map<String, Integer> next = new LinkedHashMap<>(levels);
        int added = 0;
        for (Catalog.Shop shop : catalog.all()) for (Catalog.Product product : shop.products()) {
            if (!product.finiteStock() || product.restockAmount() <= 0) continue;
            String key = shop.id() + "." + product.id();
            int before = next.getOrDefault(key, product.stockInitial());
            int after = Math.min(product.stockMax(), before + product.restockAmount());
            if (after != before) {
                next.put(key, after);
                added += after - before;
            }
        }
        if (added > 0) saveAndReplace(next);
        return added;
    }

    private void saveAndReplace(Map<String, Integer> next) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : next.entrySet()) yaml.set("levels." + entry.getKey(), entry.getValue());
        Filesafe.save(yaml, path);
        levels = Map.copyOf(next);
    }
}
