package store.cadera.shop;

import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class Catalog {
    record Product(
        String id,
        ItemStack template,
        long buy,
        long sell,
        int stockMax,
        int stockInitial,
        int restockAmount,
        int dynamicPercent
    ) {
        Product(String id, ItemStack template, long buy, long sell) {
            this(id, template, buy, sell, -1, -1, 0, 0);
        }
        boolean finiteStock() { return stockMax > 0; }
    }

    record Shop(String id, String title, String permission, String category, List<Product> products) {
        boolean allows(Player p) { return permission.isBlank() || p.hasPermission(permission); }
    }

    private final Path directory;
    private Map<String, Shop> shops = Map.of();

    Catalog(Path directory) { this.directory = directory; }
    Collection<Shop> all() { return shops.values(); }

    Shop get(String id) {
        Shop shop = shops.get(id);
        if (shop == null) throw new IllegalArgumentException("Toko tidak ditemukan: " + id);
        return shop;
    }

    void load() throws Exception {
        Files.createDirectories(directory);
        Map<String, Shop> next = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String id = Filesafe.id(path.getFileName().toString().replaceFirst("\\.yml$", ""));
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(path.toFile());
                String category = Filesafe.id(yaml.getString("category", "umum"));
                List<Product> entries = new ArrayList<>();
                ConfigurationSection items = yaml.getConfigurationSection("items");
                if (items != null) for (String key : items.getKeys(false)) {
                    Filesafe.id(key);
                    ConfigurationSection item = Objects.requireNonNull(items.getConfigurationSection(key));
                    ItemStack template;
                    if (item.isString("data")) {
                        template = ItemStack.deserializeBytes(Base64.getDecoder().decode(item.getString("data")));
                    } else {
                        Material material = Material.matchMaterial(item.getString("material", "AIR"));
                        if (material == null || !material.isItem() || material.isAir())
                            throw new IllegalArgumentException("Material salah: " + id + "/" + key);
                        template = new ItemStack(material);
                    }
                    if (template.getType().isAir()) throw new IllegalArgumentException("Template AIR: " + key);
                    template.setAmount(1);
                    long buy = Money.parse(item.getString("buy", "-1"));
                    long sell = Money.parse(item.getString("sell", "-1"));
                    int stockMax = item.getInt("stock.max", -1);
                    int stockInitial = item.getInt("stock.initial", stockMax > 0 ? stockMax : -1);
                    int restock = item.getInt("stock.restock", 0);
                    int dynamic = item.getInt("dynamic-percent", 0);
                    Product product = new Product(key, template, buy, sell, stockMax, stockInitial, restock, dynamic);
                    validateProduct(product);
                    for (Product old : entries) if (old.template().isSimilar(template))
                        throw new IllegalArgumentException("Template duplikat di toko " + id + ": " + key);
                    entries.add(product);
                }
                next.put(id, new Shop(
                    id,
                    yaml.getString("title", id),
                    yaml.getString("permission", ""),
                    category,
                    List.copyOf(entries)
                ));
            }
        }

        // Base prices may not provide direct cross-shop buy-low/sell-high loops.
        List<Product> products = next.values().stream().flatMap(s -> s.products().stream()).toList();
        for (Product a : products) for (Product b : products)
            if (a.buy() > 0 && b.sell() > a.buy() && a.template().isSimilar(b.template()))
                throw new IllegalArgumentException("Harga lintas toko memungkinkan arbitrase: " + a.id() + " / " + b.id());
        shops = Collections.unmodifiableMap(next);
    }

    void create(String id) throws Exception {
        Path path = directory.resolve(Filesafe.id(id) + ".yml");
        if (Files.exists(path)) throw new IllegalArgumentException("Toko sudah ada.");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", id);
        yaml.set("permission", "");
        yaml.set("category", "umum");
        Filesafe.save(yaml, path);
        load();
    }

    void saveCategory(String shopId, String category) throws Exception {
        get(shopId);
        String safe = Filesafe.id(category);
        Path path = directory.resolve(shopId + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        yaml.set("category", safe);
        Filesafe.save(yaml, path);
        load();
    }

    void saveProduct(String shopId, Product entry) throws Exception {
        get(shopId);
        Filesafe.id(entry.id());
        validateProduct(entry);

        // Validate draft against every other entry before replacing the file.
        for (Shop shop : all()) for (Product other : shop.products()) {
            if (shop.id().equals(shopId) && other.id().equals(entry.id())) continue;
            if (!other.template().isSimilar(entry.template())) continue;
            if (shop.id().equals(shopId)) throw new IllegalArgumentException("Item serupa sudah terdaftar sebagai " + other.id());
            if ((entry.buy() > 0 && other.sell() > entry.buy()) || (other.buy() > 0 && entry.sell() > other.buy()))
                throw new IllegalArgumentException("Harga menyebabkan arbitrase lintas toko.");
        }

        Path path = directory.resolve(shopId + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        String prefix = "items." + entry.id();
        yaml.set(prefix, null);
        ItemStack copy = entry.template().clone();
        copy.setAmount(1);
        yaml.set(prefix + ".data", Base64.getEncoder().encodeToString(copy.serializeAsBytes()));
        yaml.set(prefix + ".buy", Money.config(entry.buy()));
        yaml.set(prefix + ".sell", Money.config(entry.sell()));
        if (entry.finiteStock()) {
            yaml.set(prefix + ".stock.max", entry.stockMax());
            yaml.set(prefix + ".stock.initial", entry.stockInitial());
            yaml.set(prefix + ".stock.restock", entry.restockAmount());
        }
        if (entry.dynamicPercent() > 0) yaml.set(prefix + ".dynamic-percent", entry.dynamicPercent());
        Filesafe.save(yaml, path);
        load();
    }

    void remove(String shop, String id) throws Exception {
        get(shop);
        Filesafe.id(id);
        Path path = directory.resolve(shop + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        if (!yaml.contains("items." + id)) throw new IllegalArgumentException("ID item tidak ditemukan.");
        yaml.set("items." + id, null);
        Filesafe.save(yaml, path);
        load();
    }

    Product sellMatch(Player player, String onlyShop, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        Product best = null;
        for (Shop shop : all()) {
            if (!shop.allows(player) || (onlyShop != null && !shop.id().equals(onlyShop))) continue;
            for (Product entry : shop.products()) if (entry.sell() > 0 && entry.template().isSimilar(stack)) {
                if (best == null || entry.sell() > best.sell()) best = entry;
            }
        }
        return best;
    }

    static void validateProduct(Product product) {
        Filesafe.id(product.id());
        if (product.template() == null || product.template().getType().isAir()) throw new IllegalArgumentException("Template item tidak valid.");
        Money.validatePair(product.buy(), product.sell());
        if (product.stockMax() == -1) {
            if (product.stockInitial() != -1 || product.restockAmount() != 0 || product.dynamicPercent() != 0)
                throw new IllegalArgumentException("Stock unlimited wajib initial=-1, restock=0, dynamic=0.");
        } else {
            if (product.stockMax() < 1 || product.stockMax() > 1_000_000)
                throw new IllegalArgumentException("stock.max harus 1-1.000.000 atau -1 untuk unlimited.");
            if (product.stockInitial() < 0 || product.stockInitial() > product.stockMax())
                throw new IllegalArgumentException("stock.initial harus 0 sampai stock.max.");
            if (product.restockAmount() < 0 || product.restockAmount() > product.stockMax())
                throw new IllegalArgumentException("stock.restock harus 0 sampai stock.max.");
            if (product.dynamicPercent() < 0 || product.dynamicPercent() > 90)
                throw new IllegalArgumentException("dynamic-percent harus 0-90.");
        }
    }
}
