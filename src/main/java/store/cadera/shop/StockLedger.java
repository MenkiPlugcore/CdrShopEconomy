package store.cadera.shop;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Durable runtime stock state. Catalog files define limits/initial values; stock.yml owns the live count.
 * All mutation is performed on the main thread through transaction Changes or explicit admin operations.
 */
final class StockLedger {
    record Key(String shop, String product) {
        Key {
            Filesafe.id(shop);
            Filesafe.id(product);
        }
        @Override public String toString() { return shop + "/" + product; }
    }

    final class Change implements TradeEngine.StatePort {
        private final Map<Key, Integer> before;
        private final Map<Key, Integer> after;

        private Change(Map<Key, Integer> before, Map<Key, Integer> after) {
            this.before = Map.copyOf(before);
            this.after = Map.copyOf(after);
        }

        @Override public void apply() { after.forEach(current::put); }
        @Override public void restore() { before.forEach(current::put); }
        @Override public void persist() throws Exception { save(); }

        String detail() {
            if (before.isEmpty()) return "stock=unchanged";
            StringJoiner joiner = new StringJoiner(",", "stock=", "");
            for (Key key : before.keySet()) joiner.add(key + ":" + before.get(key) + "->" + after.get(key));
            return joiner.toString();
        }

        boolean empty() { return before.isEmpty(); }
    }

    private final Path path;
    private final Map<Key, Integer> current = new LinkedHashMap<>();
    private final Map<Key, Integer> limits = new LinkedHashMap<>();

    StockLedger(Path path) { this.path = path; }

    void reconcile(Catalog catalog) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.exists(path)) yaml.load(path.toFile());

        Map<Key, Integer> next = new LinkedHashMap<>();
        Map<Key, Integer> nextLimits = new LinkedHashMap<>();
        for (Catalog.Shop shop : catalog.all()) {
            for (Catalog.Product product : shop.products()) {
                if (!product.finiteStock()) continue;
                Key key = new Key(shop.id(), product.id());
                String configPath = node(key);
                int amount = yaml.contains(configPath) ? yaml.getInt(configPath) : product.initialStock();
                if (amount < 0 || amount > product.maxStock())
                    throw new IllegalArgumentException("Runtime stock " + key + " = " + amount + " di luar batas 0-" + product.maxStock() + ". Perbaiki stock.yml atau limit produk.");
                next.put(key, amount);
                nextLimits.put(key, product.maxStock());
            }
        }
        current.clear();
        current.putAll(next);
        limits.clear();
        limits.putAll(nextLimits);
        save(); // Canonicalize and prune entries for products that no longer exist / are unlimited.
    }

    int current(String shop, Catalog.Product product) {
        if (!product.finiteStock()) return Catalog.UNLIMITED_STOCK;
        Key key = new Key(shop, product.id());
        Integer amount = current.get(key);
        Integer limit = limits.get(key);
        if (amount == null || limit == null || limit != product.maxStock())
            throw new IllegalStateException("Stock ledger belum sinkron untuk " + key + ". Reload atau periksa konfigurasi.");
        return amount;
    }

    int max(String shop, Catalog.Product product) {
        return product.finiteStock() ? product.maxStock() : Catalog.UNLIMITED_STOCK;
    }

    String display(String shop, Catalog.Product product) {
        return product.finiteStock() ? current(shop, product) + "/" + product.maxStock() : "UNLIMITED";
    }

    boolean canAdjust(String shop, Catalog.Product product, int delta) {
        if (!product.finiteStock()) return true;
        Key key = new Key(shop, product.id());
        long next = (long) current(shop, product) + delta;
        return next >= 0 && next <= limits.get(key);
    }

    boolean canAdjust(String shop, Catalog.Product product, int delta, Map<Key, Integer> pendingDeltas) {
        if (!product.finiteStock()) return true;
        Key key = new Key(shop, product.id());
        long next = (long) current(shop, product) + pendingDeltas.getOrDefault(key, 0) + delta;
        return next >= 0 && next <= limits.get(key);
    }

    Change plan(Map<Key, Integer> deltas) {
        if (deltas.isEmpty()) return new Change(Map.of(), Map.of());
        Map<Key, Integer> before = new LinkedHashMap<>();
        Map<Key, Integer> after = new LinkedHashMap<>();
        for (var entry : deltas.entrySet()) {
            Key key = entry.getKey();
            int delta = entry.getValue();
            if (delta == 0) continue;
            Integer old = current.get(key);
            Integer max = limits.get(key);
            if (old == null || max == null) throw new IllegalArgumentException("Produk " + key + " tidak memakai finite stock.");
            long next = (long) old + delta;
            if (next < 0) throw new IllegalArgumentException("Stock " + key + " tidak cukup. Tersedia " + old + ".");
            if (next > max) throw new IllegalArgumentException("Kapasitas stock " + key + " penuh. " + old + "/" + max + ".");
            before.put(key, old);
            after.put(key, (int) next);
        }
        return new Change(before, after);
    }

    void set(String shop, Catalog.Product product, int amount) throws Exception {
        if (!product.finiteStock()) throw new IllegalArgumentException("Produk ini unlimited. Gunakan stocklimit untuk mengaktifkan finite stock.");
        if (amount < 0 || amount > product.maxStock())
            throw new IllegalArgumentException("Stock harus 0-" + product.maxStock() + ".");
        Key key = new Key(shop, product.id());
        if (!current.containsKey(key)) throw new IllegalStateException("Stock ledger belum sinkron untuk " + key + ".");
        int old = current.get(key);
        current.put(key, amount);
        try { save(); }
        catch (Exception failure) {
            current.put(key, old);
            throw failure;
        }
    }

    private void save() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : current.entrySet()) yaml.set(node(entry.getKey()), entry.getValue());
        Filesafe.save(yaml, path);
    }

    private static String node(Key key) { return "stock." + key.shop() + "." + key.product(); }
}
