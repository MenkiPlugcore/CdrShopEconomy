package store.cadera.shop;

import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class Catalog {
    static final int UNLIMITED_STOCK = -1;
    static final int MAX_STOCK = 1_000_000_000;

    record Product(String id, ItemStack template, long buy, long sell, int maxStock, int initialStock) {
        Product(String id, ItemStack template, long buy, long sell) {
            this(id, template, buy, sell, UNLIMITED_STOCK, UNLIMITED_STOCK);
        }
        Product {
            Filesafe.id(id);
            Objects.requireNonNull(template, "template");
            Money.validatePair(buy, sell);
            validateStock(maxStock, initialStock);
        }
        boolean finiteStock() { return maxStock >= 0; }
        Product withPrices(long nextBuy, long nextSell) {
            return new Product(id, template, nextBuy, nextSell, maxStock, initialStock);
        }
        Product withStock(int nextMax, int nextInitial) {
            return new Product(id, template, buy, sell, nextMax, nextInitial);
        }
    }

    record Shop(String id, String title, String permission, List<Product> products) {
        boolean allows(Player p) { return permission.isBlank() || p.hasPermission(permission); }
    }

    record Offer(String shopId, Product product) {}

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
                    int maxStock = UNLIMITED_STOCK;
                    int initialStock = UNLIMITED_STOCK;
                    ConfigurationSection stock = item.getConfigurationSection("stock");
                    if (stock != null) {
                        maxStock = stock.getInt("max", UNLIMITED_STOCK);
                        initialStock = stock.contains("initial") ? stock.getInt("initial") : maxStock;
                    }
                    validateStock(maxStock, initialStock);
                    for (Product old : entries) if (old.template().isSimilar(template))
                        throw new IllegalArgumentException("Template duplikat di toko " + id + ": " + key);
                    entries.add(new Product(key, template, buy, sell, maxStock, initialStock));
                }
                next.put(id, new Shop(id, yaml.getString("title", id), yaml.getString("permission", ""), List.copyOf(entries)));
            }
        }

        // No direct cross-shop buy-low/sell-high loops, including NPC stores.
        List<Product> products = next.values().stream().flatMap(s -> s.products().stream()).toList();
        for (Product a : products) for (Product b : products)
            if (a.buy() > 0 && b.sell() > a.buy() && a.template().isSimilar(b.template()))
                throw new IllegalArgumentException("Harga lintas toko memungkinkan arbitrase: " + a.id() + " / " + b.id());
        shops = Collections.unmodifiableMap(next);
    }

    static void validateStock(int maxStock, int initialStock) {
        if (maxStock == UNLIMITED_STOCK) {
            if (initialStock != UNLIMITED_STOCK)
                throw new IllegalArgumentException("Stock unlimited harus memakai initial -1.");
            return;
        }
        if (maxStock < 0 || maxStock > MAX_STOCK)
            throw new IllegalArgumentException("Maksimum stock harus 0-" + MAX_STOCK + " atau -1 untuk unlimited.");
        if (initialStock < 0 || initialStock > maxStock)
            throw new IllegalArgumentException("Initial stock harus 0 sampai maksimum stock.");
    }

    void create(String id) throws Exception {
        Path path = directory.resolve(Filesafe.id(id) + ".yml");
        if (Files.exists(path)) throw new IllegalArgumentException("Toko sudah ada.");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", id);
        yaml.set("permission", "");
        Filesafe.save(yaml, path);
        load();
    }

    void saveProduct(String shopId, Product entry) throws Exception {
        get(shopId);
        Filesafe.id(entry.id());
        Money.validatePair(entry.buy(), entry.sell());
        validateStock(entry.maxStock(), entry.initialStock());

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
            yaml.set(prefix + ".stock.max", entry.maxStock());
            yaml.set(prefix + ".stock.initial", entry.initialStock());
        }
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

    List<Offer> sellOffers(Player player, String onlyShop, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return List.of();
        List<Offer> offers = new ArrayList<>();
        for (Shop shop : all()) {
            if (!shop.allows(player) || (onlyShop != null && !shop.id().equals(onlyShop))) continue;
            for (Product entry : shop.products()) {
                if (entry.sell() > 0 && entry.template().isSimilar(stack)) offers.add(new Offer(shop.id(), entry));
            }
        }
        offers.sort(Comparator.comparingLong((Offer o) -> o.product().sell()).reversed());
        return List.copyOf(offers);
    }

    Product sellMatch(Player player, String onlyShop, ItemStack stack) {
        List<Offer> offers = sellOffers(player, onlyShop, stack);
        return offers.isEmpty() ? null : offers.getFirst().product();
    }
}
