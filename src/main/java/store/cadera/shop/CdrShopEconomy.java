package store.cadera.shop;

import java.nio.file.Files;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CdrShopEconomy extends JavaPlugin implements Listener {
    private Catalog catalog;
    private StockLedger stock;
    private Wallet wallet;
    private Journal journal;
    private TradeEngine trades;
    private History history;
    private CitizensBridge citizens;
    private BukkitTask restockTask;
    private final Map<Integer, String> bindings = new LinkedHashMap<>();
    private AccessPolicy.Mode mode;
    private AccessPolicy.Missing missing;
    private double distance;
    private int sessionSeconds;
    private int restockSeconds;

    private record SellOffer(String shop, Catalog.Product product, long price) {}

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!Files.exists(getDataFolder().toPath().resolve("shops"))) saveResource("shops/umum.yml", false);
            catalog = new Catalog(getDataFolder().toPath().resolve("shops"));
            stock = new StockLedger(getDataFolder().toPath().resolve("stock.yml"));
            loadSettings();
            wallet = new Wallet();
            journal = new Journal(getDataFolder().toPath().resolve("transactions.log"));
            trades = new TradeEngine(journal);
            history = new History(getDataFolder().toPath().resolve("history.log"));
            if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
                try { citizens = new CitizensBridge(this, this::npcClick); }
                catch (Exception e) { getLogger().severe("Citizens hook gagal. Toko NPC tetap terkunci: " + e); }
            }
            Bukkit.getPluginManager().registerEvents(this, this);
            scheduleRestock();
            getLogger().info("CADERA / MENKIESTES | access=" + route() + " | shops=" + catalog.all().size() + " | beta.2 stock economy aktif");
            if (!journal.pending().isEmpty()) getLogger().warning("Ada transaksi belum selesai: /cdrshop pending (console). Review sebelum resolve.");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "CdrShopEconomy gagal aktif dengan aman", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        closeMenus();
        if (restockTask != null) restockTask.cancel();
        if (journal != null) try { journal.close(); } catch (Exception e) { getLogger().severe(e.toString()); }
    }

    private void closeMenus() {
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
    }

    private void loadSettings() throws Exception {
        reloadConfig();
        var nextMode = AccessPolicy.Mode.valueOf(getConfig().getString("access-mode", "AUTO").toUpperCase(Locale.ROOT));
        var nextMissing = AccessPolicy.Missing.valueOf(getConfig().getString("missing-citizens", "COMMAND").toUpperCase(Locale.ROOT));
        double nextDistance = getConfig().getDouble("npc-max-distance", 6);
        int nextSeconds = getConfig().getInt("session-seconds", 120);
        int nextRestock = getConfig().getInt("restock-interval-seconds", 300);
        if (!Double.isFinite(nextDistance) || nextDistance < 1 || nextDistance > 32 || nextSeconds < 10 || nextSeconds > 600)
            throw new IllegalArgumentException("Jarak NPC 1-32 blok; sesi 10-600 detik.");
        if (nextRestock != 0 && (nextRestock < 10 || nextRestock > 86_400))
            throw new IllegalArgumentException("restock-interval-seconds harus 0 atau 10-86400.");

        Map<Integer, String> nextBindings = new LinkedHashMap<>();
        var npcPath = getDataFolder().toPath().resolve("npcs.yml");
        if (Files.exists(npcPath)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(npcPath.toFile());
            var section = yaml.getConfigurationSection("bindings");
            if (section != null) for (String key : section.getKeys(false)) {
                int npc = Integer.parseInt(key);
                if (npc < 0) throw new IllegalArgumentException("NPC ID negatif");
                String shop = Filesafe.id(section.getString(key, ""));
                var shopFile = getDataFolder().toPath().resolve("shops").resolve(shop + ".yml");
                if (!Files.isRegularFile(shopFile)) throw new IllegalArgumentException("Binding NPC " + npc + " menunjuk toko yang hilang: " + shop);
                nextBindings.put(npc, shop);
            }
        }

        catalog.load();
        for (String shop : nextBindings.values()) catalog.get(shop);
        stock.reconcile(catalog);
        mode = nextMode;
        missing = nextMissing;
        distance = nextDistance;
        sessionSeconds = nextSeconds;
        restockSeconds = nextRestock;
        bindings.clear();
        bindings.putAll(nextBindings);
    }

    private void scheduleRestock() {
        if (restockTask != null) restockTask.cancel();
        restockTask = null;
        if (restockSeconds <= 0) return;
        long ticks = restockSeconds * 20L;
        restockTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                int added = stock.restock(catalog);
                if (added > 0) getLogger().fine("Auto-restock menambahkan " + added + " unit.");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Auto-restock gagal; transaksi tetap memakai state stock terakhir yang tersimpan.", e);
            }
        }, ticks, ticks);
    }

    private void saveBindings(Map<Integer, String> next) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        next.forEach((key, value) -> yaml.set("bindings." + key, value));
        Filesafe.save(yaml, getDataFolder().toPath().resolve("npcs.yml"));
        bindings.clear();
        bindings.putAll(next);
        closeMenus();
    }

    private AccessPolicy.Route route() {
        return AccessPolicy.route(mode, missing, Bukkit.getPluginManager().isPluginEnabled("Citizens"), !bindings.isEmpty());
    }

    private void access(Player p, String shop, Integer npc) {
        if (!p.hasPermission("cdrshopeconomy.use")) throw new IllegalArgumentException("Tidak punya izin toko.");
        if (p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)
            throw new IllegalArgumentException("Transaksi hanya saat hidup di Survival/Adventure.");
        if (route() == AccessPolicy.Route.DISABLED) throw new IllegalArgumentException("Toko dinonaktifkan: Citizens tidak tersedia.");
        if (npc == null && route() == AccessPolicy.Route.NPC) throw new IllegalArgumentException("Temui NPC pedagang untuk membeli atau menjual barang.");
        if (npc != null && (route() != AccessPolicy.Route.NPC || citizens == null || !Objects.equals(bindings.get(npc), shop) || !citizens.near(p, npc, distance)))
            throw new IllegalArgumentException("Sesi NPC tidak valid. Dekati dan klik kembali pedagang.");
        if (shop != null && !catalog.get(shop).allows(p)) throw new IllegalArgumentException("Tidak punya izin toko ini.");
    }

    private void npcClick(Player p, int id) {
        if (!bindings.containsKey(id) || route() != AccessPolicy.Route.NPC) return;
        try {
            String shop = bindings.get(id);
            access(p, shop, id);
            openBuy(p, shop, id, 0);
        } catch (Exception e) { message(p, e.getMessage()); }
    }

    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private static void message(CommandSender p, String text) {
        p.sendMessage(color("&6[CdrShop] &f" + (text == null ? "Operasi gagal; hubungi admin." : text)));
    }
    private static ItemStack icon(Material type, String title, String... lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title));
        meta.setLore(Arrays.stream(lore).map(CdrShopEconomy::color).toList());
        item.setItemMeta(meta);
        return item;
    }
    private static ItemStack label(ItemStack original, String... lines) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
        for (String line : lines) lore.add(color(line));
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private Menu menu(Player p, Menu.Kind kind, String shop, Integer npc, int page, String title) {
        return new Menu(kind, p.getUniqueId(), shop, npc, page, System.currentTimeMillis() + sessionSeconds * 1000L, color(title));
    }
    private void navigation(Menu m, int size) {
        if (m.page > 0) m.inventory.setItem(45, icon(Material.ARROW, "&eHalaman sebelumnya"));
        if ((m.page + 1) * 45 < size) m.inventory.setItem(53, icon(Material.ARROW, "&eHalaman berikutnya"));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cTutup"));
    }

    private List<String> categories(Player p) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (Catalog.Shop shop : catalog.all()) if (shop.allows(p)) categories.add(shop.category());
        return List.copyOf(categories);
    }

    private void openRoot(Player p) {
        access(p, null, null);
        List<String> categories = categories(p);
        if (categories.size() > 1) openCategories(p, 0);
        else openShops(p, 0, false, categories.isEmpty() ? null : categories.get(0));
    }

    private void openCategories(Player p, int page) {
        access(p, null, null);
        List<String> list = categories(p);
        Menu m = menu(p, Menu.Kind.CATEGORIES, null, null, page, "&6CdrShopEconomy - Kategori");
        for (int i = page * 45; i < Math.min(list.size(), (page + 1) * 45); i++) {
            String category = list.get(i);
            int count = (int) catalog.all().stream().filter(s -> s.allows(p) && s.category().equals(category)).count();
            int slot = i % 45;
            m.keys.put(slot, category);
            m.inventory.setItem(slot, icon(Material.BOOKSHELF, "&e" + category, "&7" + count + " toko", "&eKlik untuk membuka"));
        }
        navigation(m, list.size());
        m.inventory.setItem(49, icon(Material.HOPPER, "&aJual Barang"));
        p.openInventory(m.inventory);
    }

    private void openShops(Player p, int page, boolean editor) { openShops(p, page, editor, null); }
    private void openShops(Player p, int page, boolean editor, String category) {
        if (!editor) access(p, null, null);
        Menu m = menu(p, editor ? Menu.Kind.EDIT_SHOPS : Menu.Kind.SHOPS, null, null, page,
            editor ? "&6Editor: pilih toko" : category == null ? "&6CdrShopEconomy - Toko" : "&6Toko: " + category);
        m.category = category;
        List<Catalog.Shop> list = catalog.all().stream()
            .filter(s -> editor || s.allows(p))
            .filter(s -> category == null || s.category().equals(category))
            .toList();
        for (int i = page * 45; i < Math.min(list.size(), (page + 1) * 45); i++) {
            var shop = list.get(i);
            int slot = i % 45;
            m.keys.put(slot, shop.id());
            m.inventory.setItem(slot, icon(Material.CHEST, shop.title(), "&7ID: " + shop.id(), "&7Kategori: " + shop.category(), "&eKlik untuk membuka"));
        }
        navigation(m, list.size());
        if (!editor) {
            if (categories(p).size() > 1) m.inventory.setItem(47, icon(Material.COMPASS, "&eKembali ke kategori"));
            m.inventory.setItem(49, icon(Material.HOPPER, "&aJual Barang"));
        }
        p.openInventory(m.inventory);
    }

    private void openBuy(Player p, String shopId, Integer npc, int page) {
        access(p, shopId, npc);
        var shop = catalog.get(shopId);
        Menu m = menu(p, Menu.Kind.BUY, shopId, npc, page, shop.title());
        var products = shop.products().stream().filter(e -> e.buy() > 0).toList();
        for (int i = page * 45; i < Math.min(products.size(), (page + 1) * 45); i++) {
            var product = products.get(i);
            int slot = i % 45;
            m.keys.put(slot, product.id());
            long buy = Pricing.buy(catalog, stock, shopId, product);
            long sell = product.sell() > 0 ? Pricing.sell(catalog, stock, shopId, product) : -1;
            String stockLine = product.finiteStock()
                ? (stock.current(shopId, product) == 0 ? "&cStok: 0/" + product.stockMax() + " (HABIS)" : "&eStok: " + stock.current(shopId, product) + "/" + product.stockMax())
                : "&7Stok: unlimited";
            String dynamicLine = product.dynamicPercent() > 0 ? "&dDynamic: ±" + product.dynamicPercent() + "%" : "&7Harga: fixed";
            m.inventory.setItem(slot, label(product.template(), "&aBeli/unit: $" + Money.format(buy), "&7Jual/unit: " + Money.format(sell), stockLine, dynamicLine, "&eKlik pilih jumlah"));
        }
        navigation(m, products.size());
        m.inventory.setItem(49, icon(Material.HOPPER, "&aJual ke toko ini"));
        p.openInventory(m.inventory);
    }

    private Catalog.Product product(String shop, String id) {
        return catalog.get(shop).products().stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Item sudah berubah; buka ulang toko."));
    }

    private void quantity(Player p, Menu source, Catalog.Product product) {
        int available = product.finiteStock() ? stock.current(source.shop, product) : Integer.MAX_VALUE;
        if (available <= 0) throw new IllegalArgumentException("Stok item habis.");
        long unit = Pricing.buy(catalog, stock, source.shop, product);
        Menu m = menu(p, Menu.Kind.QUANTITY, source.shop, source.npc, 0, "&6Pilih jumlah pembelian");
        m.product = product;
        m.quotedPrice = unit;
        int[] choices = {1, 16, 32, 64};
        for (int i = 0; i < choices.length; i++) {
            int qty = choices[i];
            int slot = 19 + i * 2;
            if (qty <= available) {
                m.keys.put(slot, Integer.toString(qty));
                m.inventory.setItem(slot, label(product.template(), "&eBeli " + qty + " item", "&aTotal: $" + Money.format(Money.total(unit, qty))));
            } else {
                m.inventory.setItem(slot, icon(Material.REDSTONE_BLOCK, "&cStok tidak cukup untuk x" + qty, "&7Tersedia: " + available));
            }
        }
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal"));
        p.openInventory(m.inventory);
    }

    private void buyConfirmation(Player p, Menu source, int amount) {
        Catalog.Product current = product(source.shop, source.product.id());
        int available = current.finiteStock() ? stock.current(source.shop, current) : Integer.MAX_VALUE;
        if (available < amount) throw new IllegalArgumentException("Stok berubah; tersedia " + available + ". Pilih ulang jumlah.");
        long unit = Pricing.buy(catalog, stock, source.shop, current);
        Menu m = menu(p, Menu.Kind.BUY_CONFIRM, source.shop, source.npc, 0, "&6Konfirmasi pembelian");
        m.product = current;
        m.quantity = amount;
        m.quotedPrice = unit;
        m.inventory.setItem(22, label(current.template(), "&eJumlah: " + amount, "&aHarga/unit: $" + Money.format(unit), "&aTotal: $" + Money.format(Money.total(unit, amount))));
        m.inventory.setItem(49, icon(Material.LIME_CONCRETE, "&aKONFIRMASI BELI"));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal"));
        p.openInventory(m.inventory);
    }

    private void openSell(Player p, String shop, Integer npc) {
        access(p, shop, npc);
        Menu m = menu(p, Menu.Kind.SELL, shop, npc, 0, "&6Jual - pilih item inventory");
        renderSell(p, m);
        p.openInventory(m.inventory);
    }

    private SellOffer sellOffer(Player player, String onlyShop, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        SellOffer best = null;
        for (Catalog.Shop shop : catalog.all()) {
            if (!shop.allows(player) || (onlyShop != null && !shop.id().equals(onlyShop))) continue;
            for (Catalog.Product product : shop.products()) {
                if (product.sell() <= 0 || !product.template().isSimilar(stack)) continue;
                long quote = Pricing.sell(catalog, stock, shop.id(), product);
                if (best == null || quote > best.price()) best = new SellOffer(shop.id(), product, quote);
            }
        }
        return best;
    }

    private void renderSell(Player p, Menu m) {
        m.inventory.clear();
        long total = 0;
        int count = 0;
        for (var chosen : m.selected.entrySet()) {
            SellOffer offer = sellOffer(p, m.shop, chosen.getValue());
            if (offer == null) continue;
            total = Math.addExact(total, Money.total(offer.price(), chosen.getValue().getAmount()));
            count += chosen.getValue().getAmount();
            m.inventory.setItem(chosen.getKey(), label(chosen.getValue(), "&eKlik untuk batalkan pilihan", "&a$" + Money.format(offer.price()) + "/unit"));
        }
        m.inventory.setItem(45, icon(Material.PAPER, "&eKlik item di inventory bawah", "&7Ini salinan pilihan, bukan tempat penitipan.", "&7Barang asli tetap di inventory hingga konfirmasi."));
        m.inventory.setItem(48, icon(Material.HOPPER, "&ePilih semua item yang laku"));
        m.inventory.setItem(49, icon(Material.LIME_CONCRETE, "&aKONFIRMASI JUAL", "&fJumlah: " + count, "&aTotal: $" + Money.format(total)));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal / Tutup"));
    }

    private void openEditor(Player p, String shop, int page) {
        Menu m = menu(p, Menu.Kind.EDIT_ITEMS, shop, null, page, "&6Editor " + shop);
        var entries = catalog.get(shop).products();
        for (int i = page * 45; i < Math.min(entries.size(), (page + 1) * 45); i++) {
            var e = entries.get(i);
            m.keys.put(i % 45, e.id());
            String stockLine = e.finiteStock() ? "&eStock: " + stock.current(shop, e) + "/" + e.stockMax() + " +" + e.restockAmount() + "/cycle" : "&7Stock: unlimited";
            m.inventory.setItem(i % 45, label(e.template(), "&eID: " + e.id(), "&aBeli: " + Money.format(e.buy()), "&7Jual: " + Money.format(e.sell()), stockLine, "&dDynamic: " + e.dynamicPercent() + "%", "&eKlik edit harga"));
        }
        navigation(m, entries.size());
        m.inventory.setItem(49, icon(Material.PAPER, "&eKlik item inventory untuk menambah", "&7Template disalin, item asli tidak diambil."));
        p.openInventory(m.inventory);
    }

    private void editPrice(Player p, String shop, Catalog.Product product) {
        Menu m = menu(p, Menu.Kind.EDIT_PRICE, shop, null, 0, "&6Editor harga");
        m.product = product;
        m.buy = product.buy();
        m.sell = product.sell();
        renderEditor(m);
        p.openInventory(m.inventory);
    }

    private void renderEditor(Menu m) {
        String stockLine = m.product.finiteStock() ? "&eStock max: " + m.product.stockMax() + " | restock: " + m.product.restockAmount() : "&7Stock: unlimited";
        m.inventory.setItem(4, label(m.product.template(), "&eID: " + m.product.id(), "&aBeli: " + Money.format(m.buy), "&7Jual: " + Money.format(m.sell), stockLine, "&dDynamic: " + m.product.dynamicPercent() + "%"));
        String[] labels = {"-10", "-1", "+1", "+10", "ON/OFF"};
        for (int i = 0; i < 5; i++) {
            m.inventory.setItem(19 + i, icon(Material.GOLD_NUGGET, "&eBeli " + labels[i]));
            m.inventory.setItem(28 + i, icon(Material.IRON_NUGGET, "&eJual " + labels[i]));
        }
        m.inventory.setItem(49, icon(Material.LIME_CONCRETE, "&aSimpan", "&7Harga presisi: /cdrshop price <toko> <id> <beli> <jual>"));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !m.owner.equals(p.getUniqueId())) return;
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        int raw = e.getRawSlot(), slot = e.getSlot();
        boolean bottom = e.getClickedInventory() == p.getInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != m) return;
            try {
                if (System.currentTimeMillis() > m.expires) {
                    p.closeInventory();
                    throw new IllegalArgumentException("Sesi habis. Silakan buka ulang.");
                }
                if (m.editor()) {
                    if (!p.hasPermission("cdrshopeconomy.admin")) throw new IllegalArgumentException("Izin admin diperlukan.");
                } else access(p, m.shop, m.npc);
                if (bottom) { bottomClick(p, m, slot); return; }
                if (raw < 0 || raw >= 54) return;
                if (raw == 50) { p.closeInventory(); return; }
                String key = m.keys.get(raw);
                switch (m.kind) {
                    case CATEGORIES -> {
                        if (key != null) openShops(p, 0, false, key);
                        else if (raw == 49) openSell(p, null, null);
                        else if ((raw == 45 || raw == 53) && m.inventory.getItem(raw) != null)
                            openCategories(p, m.page + (raw == 45 ? -1 : 1));
                    }
                    case SHOPS, EDIT_SHOPS -> {
                        if (key != null) {
                            if (m.editor()) openEditor(p, key, 0);
                            else openBuy(p, key, null, 0);
                        } else if (raw == 47 && !m.editor() && categories(p).size() > 1) openCategories(p, 0);
                        else if (raw == 49 && !m.editor()) openSell(p, null, null);
                        else if ((raw == 45 || raw == 53) && m.inventory.getItem(raw) != null)
                            openShops(p, m.page + (raw == 45 ? -1 : 1), m.editor(), m.category);
                    }
                    case BUY -> {
                        if (key != null) quantity(p, m, product(m.shop, key));
                        else if (raw == 49) openSell(p, m.shop, m.npc);
                        else if ((raw == 45 || raw == 53) && m.inventory.getItem(raw) != null)
                            openBuy(p, m.shop, m.npc, m.page + (raw == 45 ? -1 : 1));
                    }
                    case QUANTITY -> { if (key != null) buyConfirmation(p, m, Integer.parseInt(key)); }
                    case BUY_CONFIRM -> { if (raw == 49) { p.closeInventory(); buy(p, m); } }
                    case SELL -> {
                        if (raw < 36) {
                            m.selected.remove(raw);
                            renderSell(p, m);
                        } else if (raw == 48) {
                            m.selected.clear();
                            for (int i = 0; i < 36; i++) {
                                ItemStack stack = p.getInventory().getItem(i);
                                if (sellOffer(p, m.shop, stack) != null) m.selected.put(i, stack.clone());
                            }
                            renderSell(p, m);
                        } else if (raw == 49) {
                            p.closeInventory();
                            sell(p, m.shop, m.npc, m.selected);
                        }
                    }
                    case EDIT_ITEMS -> {
                        if (key != null) editPrice(p, m.shop, product(m.shop, key));
                        else if ((raw == 45 || raw == 53) && m.inventory.getItem(raw) != null)
                            openEditor(p, m.shop, m.page + (raw == 45 ? -1 : 1));
                    }
                    case EDIT_PRICE -> {
                        if (raw == 49) {
                            Catalog.Product old = m.product;
                            catalog.saveProduct(m.shop, new Catalog.Product(old.id(), old.template(), m.buy, m.sell, old.stockMax(), old.stockInitial(), old.restockAmount(), old.dynamicPercent()));
                            stock.reconcile(catalog);
                            closeMenus();
                            message(p, "Item tersimpan: " + old.id());
                            openEditor(p, m.shop, 0);
                        } else if (raw >= 19 && raw <= 23) {
                            m.buy = adjust(m.buy, raw - 19);
                            renderEditor(m);
                        } else if (raw >= 28 && raw <= 32) {
                            m.sell = adjust(m.sell, raw - 28);
                            renderEditor(m);
                        }
                    }
                }
            } catch (Exception ex) { message(p, ex.getMessage()); }
        });
    }

    private static long adjust(long price, int button) {
        if (button == 4) return price < 0 ? 100 : -1;
        long[] delta = {-1000, -100, 100, 1000};
        return Math.max(1, Math.min(100_000_000_000L, Math.max(0, price) + delta[button]));
    }

    private void bottomClick(Player p, Menu m, int slot) {
        if (slot < 0 || slot >= 36) return;
        ItemStack stack = p.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) return;
        if (m.kind == Menu.Kind.SELL) {
            if (m.selected.containsKey(slot)) m.selected.remove(slot);
            else {
                if (sellOffer(p, m.shop, stack) == null) throw new IllegalArgumentException("Item ini tidak diterima toko.");
                m.selected.put(slot, stack.clone());
            }
            renderSell(p, m);
        } else if (m.kind == Menu.Kind.EDIT_ITEMS) {
            ItemStack template = stack.clone();
            template.setAmount(1);
            String id = "item_" + UUID.randomUUID().toString().substring(0, 8);
            editPrice(p, m.shop, new Catalog.Product(id, template, -1, -1));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Menu) e.getPlayer().closeInventory();
    }

    private static ItemStack[] copy(ItemStack[] input) {
        return Arrays.stream(input).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
    }

    private void sell(Player p, String shop, Integer npc, Map<Integer, ItemStack> chosen) throws Exception {
        access(p, shop, npc);
        if (chosen.isEmpty()) throw new IllegalArgumentException("Belum ada item yang dipilih.");
        ItemStack[] before = copy(p.getInventory().getStorageContents());
        ItemStack[] after = copy(before);
        long total = 0;
        int quantity = 0;
        List<StockLedger.Change> stockChanges = new ArrayList<>();
        for (var entry : chosen.entrySet()) {
            int slot = entry.getKey();
            ItemStack expected = entry.getValue();
            if (slot < 0 || slot >= before.length || !Objects.equals(before[slot], expected))
                throw new IllegalArgumentException("Inventory berubah. Buka ulang dan pilih kembali item.");
            SellOffer offer = sellOffer(p, shop, expected);
            if (offer == null) throw new IllegalArgumentException("Barang/hak akses berubah; transaksi dibatalkan.");
            total = Math.addExact(total, Money.total(offer.price(), expected.getAmount()));
            quantity += expected.getAmount();
            if (offer.product().finiteStock()) stockChanges.add(new StockLedger.Change(offer.shop(), offer.product(), expected.getAmount()));
            after[slot] = null;
        }
        execute(p, before, after, total, true, quantity, shop, stockChanges);
    }

    private void buy(Player p, Menu m) throws Exception {
        access(p, m.shop, m.npc);
        Catalog.Product current = product(m.shop, m.product.id());
        if (current.buy() != m.product.buy() || !current.template().isSimilar(m.product.template()))
            throw new IllegalArgumentException("Item berubah; buka ulang toko.");
        long unit = Pricing.buy(catalog, stock, m.shop, current);
        if (unit != m.quotedPrice) throw new IllegalArgumentException("Harga berubah karena stock pasar. Buka ulang konfirmasi.");
        if (current.finiteStock() && stock.current(m.shop, current) < m.quantity)
            throw new IllegalArgumentException("Stok tidak cukup. Buka ulang toko.");

        ItemStack[] before = copy(p.getInventory().getStorageContents());
        ItemStack[] after = copy(before);
        int remaining = m.quantity;
        for (int i = 0; i < after.length && remaining > 0; i++) {
            if (after[i] == null || !after[i].isSimilar(current.template())) continue;
            int space = Math.max(0, Math.min(after[i].getMaxStackSize(), p.getInventory().getMaxStackSize()) - after[i].getAmount());
            int add = Math.min(space, remaining);
            after[i].setAmount(after[i].getAmount() + add);
            remaining -= add;
        }
        for (int i = 0; i < after.length && remaining > 0; i++) if (after[i] == null || after[i].getType().isAir()) {
            after[i] = current.template().clone();
            int add = Math.min(remaining, Math.min(after[i].getMaxStackSize(), p.getInventory().getMaxStackSize()));
            after[i].setAmount(add);
            remaining -= add;
        }
        if (remaining > 0) throw new IllegalArgumentException("Ruang inventory tidak cukup. Tidak ada uang dipotong.");
        List<StockLedger.Change> changes = current.finiteStock()
            ? List.of(new StockLedger.Change(m.shop, current, -m.quantity)) : List.of();
        execute(p, before, after, Money.total(unit, m.quantity), false, m.quantity, m.shop, changes);
    }

    private void execute(Player p, ItemStack[] before, ItemStack[] after, long amount, boolean credit, int count, String shop, List<StockLedger.Change> changes) throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Transaksi wajib main thread.");
        if (!wallet.available()) throw new IllegalArgumentException("Provider economy tidak tersedia.");
        Map<String, Integer> stockBefore = stock.snapshot(changes);
        String detail = (credit ? "SELL" : "BUY") + " shop=" + (shop == null ? "all" : shop) + " cents=" + amount + " count=" + count
            + " stockBefore=" + stockBefore + " stockChanges=" + changes
            + " before=" + encode(before) + " after=" + encode(after);
        try {
            var result = trades.run(p.getUniqueId(), detail, new TradeEngine.InventoryPort() {
                public void apply() throws Exception {
                    stock.apply(changes);
                    p.getInventory().setStorageContents(copy(after));
                }
                public void restore() throws Exception {
                    p.getInventory().setStorageContents(copy(before));
                    stock.restore(stockBefore);
                }
                public void persist() { p.saveData(); }
            }, () -> wallet.change(p, amount, credit));
            if (result == TradeEngine.Result.DECLINED) {
                recordHistory("DECLINED", p, credit ? "SELL" : "BUY", shop, amount, count);
                message(p, "Transaksi ditolak economy (saldo kurang/batas saldo). Item dan stock dikembalikan.");
            } else {
                recordHistory("SUCCESS", p, credit ? "SELL" : "BUY", shop, amount, count);
                message(p, "Berhasil " + (credit ? "menjual " : "membeli ") + count + " item, total $" + Money.format(amount));
            }
        } catch (TradeEngine.SafeAbortException ex) {
            recordHistory("ABORTED", p, credit ? "SELL" : "BUY", shop, amount, count);
            getLogger().log(java.util.logging.Level.WARNING, "Transaksi dibatalkan aman sebelum pembayaran untuk " + p.getUniqueId(), ex);
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (Exception ex) {
            recordHistory("REVIEW", p, credit ? "SELL" : "BUY", shop, amount, count);
            getLogger().log(java.util.logging.Level.SEVERE, "Review transaksi " + p.getUniqueId() + " di transactions.log", ex);
            throw new IllegalStateException("Transaksi terkunci untuk pemeriksaan admin. Jangan ulangi pembayaran.", ex);
        }
    }

    private void recordHistory(String status, Player p, String action, String shop, long amount, int count) {
        if (history == null) return;
        try { history.append(status, p.getUniqueId(), p.getName(), action, shop, amount, count); }
        catch (Exception e) { getLogger().warning("Gagal menulis history.log: " + e.getMessage()); }
    }

    private static String encode(ItemStack[] storage) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(Arrays.asList(storage)));
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (cmd.getName().equals("cdrshop")) { admin(sender, args); return true; }
            if (!(sender instanceof Player p)) throw new IllegalArgumentException("Command player hanya di dalam game.");
            access(p, null, null);
            switch (cmd.getName()) {
                case "toko" -> {
                    if (args.length == 0) openRoot(p);
                    else if (args.length == 1) openBuy(p, args[0], null, 0);
                    else throw new IllegalArgumentException("/toko [nama-toko]");
                }
                case "jualgui" -> {
                    if (args.length != 0) throw new IllegalArgumentException("/jualgui");
                    openSell(p, null, null);
                }
                case "jual" -> {
                    if (args.length != 0) throw new IllegalArgumentException("/jual (seluruh stack tangan utama)");
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) throw new IllegalArgumentException("Pegang item terlebih dahulu.");
                    sell(p, null, null, Map.of(p.getInventory().getHeldItemSlot(), hand.clone()));
                }
                default -> { return false; }
            }
        } catch (Exception e) { message(sender, e.getMessage()); }
        return true;
    }

    private void admin(CommandSender sender, String[] a) throws Exception {
        if (!sender.hasPermission("cdrshopeconomy.admin")) throw new IllegalArgumentException("Izin admin diperlukan.");
        if (a.length == 0) {
            message(sender, "/cdrshop create <toko> | editor [toko] | add <toko> <id> <beli> <jual>");
            message(sender, "/cdrshop price <toko> <id> <beli> <jual> | stock <toko> <id> <max|-1> <initial|-1> <restock>");
            message(sender, "/cdrshop dynamic <toko> <id> <0-90> | category <toko> <kategori> | remove <toko> <id>");
            message(sender, "/cdrshop bind <toko> <id-NPC> | unbind <id-NPC> | reload | restock | status | history [player|all] [limit]");
            message(sender, "Console: pending | resolve <tx-UUID> reviewed");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                require(a, 2);
                catalog.create(a[1]);
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Toko dibuat: " + a[1]);
            }
            case "editor" -> {
                Player p = player(sender);
                if (a.length == 1) openShops(p, 0, true);
                else { require(a, 2); openEditor(p, a[1], 0); }
            }
            case "add" -> {
                require(a, 5);
                Player p = player(sender);
                ItemStack item = p.getInventory().getItemInMainHand().clone();
                if (item.getType().isAir()) throw new IllegalArgumentException("Pegang item template.");
                if (catalog.get(a[1]).products().stream().anyMatch(e -> e.id().equals(a[2])))
                    throw new IllegalArgumentException("ID sudah ada; gunakan editor/price.");
                item.setAmount(1);
                catalog.saveProduct(a[1], new Catalog.Product(a[2], item, Money.parse(a[3]), Money.parse(a[4])));
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Template disalin dan disimpan. Stock default unlimited.");
            }
            case "price" -> {
                require(a, 5);
                var old = product(a[1], a[2]);
                catalog.saveProduct(a[1], new Catalog.Product(old.id(), old.template(), Money.parse(a[3]), Money.parse(a[4]), old.stockMax(), old.stockInitial(), old.restockAmount(), old.dynamicPercent()));
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Harga diperbarui.");
            }
            case "stock" -> {
                require(a, 6);
                var old = product(a[1], a[2]);
                int max = Integer.parseInt(a[3]);
                int initial = Integer.parseInt(a[4]);
                int restock = Integer.parseInt(a[5]);
                int dynamic = max == -1 ? 0 : old.dynamicPercent();
                catalog.saveProduct(a[1], new Catalog.Product(old.id(), old.template(), old.buy(), old.sell(), max, initial, restock, dynamic));
                stock.reconcile(catalog);
                closeMenus();
                message(sender, max == -1 ? "Stock diubah menjadi unlimited; dynamic dimatikan." : "Stock diperbarui: max=" + max + ", initial=" + initial + ", restock=" + restock);
            }
            case "dynamic" -> {
                require(a, 4);
                var old = product(a[1], a[2]);
                int percent = Integer.parseInt(a[3]);
                catalog.saveProduct(a[1], new Catalog.Product(old.id(), old.template(), old.buy(), old.sell(), old.stockMax(), old.stockInitial(), old.restockAmount(), percent));
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Dynamic pricing: ±" + percent + "%.");
            }
            case "category" -> {
                require(a, 3);
                catalog.saveCategory(a[1], a[2]);
                closeMenus();
                message(sender, "Kategori toko diperbarui: " + a[2]);
            }
            case "remove" -> {
                require(a, 3);
                catalog.remove(a[1], a[2]);
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Listing dihapus; item pemain tidak disentuh.");
            }
            case "bind" -> {
                require(a, 3);
                catalog.get(a[1]);
                int id = Integer.parseInt(a[2]);
                if (citizens == null || !citizens.exists(id)) throw new IllegalArgumentException("NPC Citizens tidak ditemukan. Pastikan Citizens aktif sejak startup.");
                if (bindings.containsKey(id)) throw new IllegalArgumentException("NPC sudah terhubung. Unbind dahulu untuk mengganti.");
                Map<Integer, String> next = new LinkedHashMap<>(bindings);
                next.put(id, a[1]);
                saveBindings(next);
                message(sender, "NPC " + id + " terhubung ke " + a[1] + "; akses=" + route());
            }
            case "unbind" -> {
                require(a, 2);
                Map<Integer, String> next = new LinkedHashMap<>(bindings);
                if (next.remove(Integer.parseInt(a[1])) == null) throw new IllegalArgumentException("Binding tidak ditemukan.");
                saveBindings(next);
                message(sender, "Binding dilepas; akses=" + route());
            }
            case "reload" -> {
                require(a, 1);
                closeMenus();
                loadSettings();
                scheduleRestock();
                message(sender, "Konfigurasi valid dimuat. Akses=" + route());
            }
            case "restock" -> {
                require(a, 1);
                int added = stock.restock(catalog);
                closeMenus();
                message(sender, "Restock manual selesai: +" + added + " unit.");
            }
            case "status" -> {
                long finite = catalog.all().stream().flatMap(s -> s.products().stream()).filter(Catalog.Product::finiteStock).count();
                message(sender, "v" + getPluginMeta().getVersion() + " | akses=" + route() + " | toko=" + catalog.all().size() + " | finite-stock=" + finite
                    + " | restock=" + (restockSeconds == 0 ? "off" : restockSeconds + "s") + " | pending=" + journal.pending().size() + " | economy=" + wallet.available());
            }
            case "history" -> {
                if (a.length > 3) throw new IllegalArgumentException("/cdrshop history [player|all] [limit]");
                String filter = null;
                int limit = 10;
                if (a.length == 2) {
                    try { limit = Integer.parseInt(a[1]); }
                    catch (NumberFormatException ignored) { if (!a[1].equalsIgnoreCase("all")) filter = a[1]; }
                } else if (a.length == 3) {
                    if (!a[1].equalsIgnoreCase("all")) filter = a[1];
                    limit = Integer.parseInt(a[2]);
                }
                List<String> lines = history.tail(filter, limit);
                if (lines.isEmpty()) message(sender, "History tidak ditemukan.");
                else for (String line : lines) message(sender, line);
            }
            case "pending" -> {
                console(sender);
                message(sender, "Transaksi untuk review: " + journal.pending());
            }
            case "resolve" -> {
                console(sender);
                require(a, 3);
                if (!a[2].equals("reviewed")) throw new IllegalArgumentException("Review uang/item/stock di journal dahulu; argumen akhir: reviewed");
                journal.finish(UUID.fromString(a[1]), "RESOLVED", "console-reviewed");
                message(sender, "Kunci dilepas. Tidak ada refund, item, atau perubahan stock otomatis.");
            }
            default -> throw new IllegalArgumentException("Subcommand tidak dikenal. Gunakan /cdrshop.");
        }
    }

    private static void console(CommandSender sender) {
        if (!(sender instanceof ConsoleCommandSender)) throw new IllegalArgumentException("Hanya console server.");
    }
    private static Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        throw new IllegalArgumentException("Gunakan di dalam game.");
    }
    private static void require(String[] a, int size) {
        if (a.length != size) throw new IllegalArgumentException("Argumen salah; lihat /cdrshop.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (cmd.getName().equals("toko") && args.length == 1 && route() == AccessPolicy.Route.COMMAND) {
            for (var shop : catalog.all()) if (!(sender instanceof Player p) || shop.allows(p)) options.add(shop.id());
        }
        if (cmd.getName().equals("cdrshop") && sender.hasPermission("cdrshopeconomy.admin")) {
            if (args.length == 1) options.addAll(List.of("create", "editor", "add", "price", "stock", "dynamic", "category", "remove", "bind", "unbind", "reload", "restock", "status", "history", "pending", "resolve"));
            if (args.length == 2 && Set.of("editor", "add", "price", "stock", "dynamic", "category", "remove", "bind").contains(args[0].toLowerCase(Locale.ROOT)))
                catalog.all().forEach(s -> options.add(s.id()));
            if (args.length == 3 && Set.of("price", "stock", "dynamic", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
                try { catalog.get(args[1]).products().forEach(p -> options.add(p.id())); } catch (Exception ignored) { }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
