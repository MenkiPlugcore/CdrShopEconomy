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

public final class CdrShopEconomy extends JavaPlugin implements Listener {
    private Catalog catalog;
    private StockLedger stock;
    private Wallet wallet;
    private Journal journal;
    private TradeEngine trades;
    private CitizensBridge citizens;
    private final Map<Integer, String> bindings = new LinkedHashMap<>();
    private AccessPolicy.Mode mode;
    private AccessPolicy.Missing missing;
    private double distance;
    private int sessionSeconds;

    private record SellPlan(long total, int quantity, Map<StockLedger.Key, Integer> stockDeltas) {}

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!Files.exists(getDataFolder().toPath().resolve("shops"))) saveResource("shops/umum.yml", false);
            catalog = new Catalog(getDataFolder().toPath().resolve("shops"));
            loadSettings();
            stock = new StockLedger(getDataFolder().toPath().resolve("stock.yml"));
            stock.reconcile(catalog);
            wallet = new Wallet();
            journal = new Journal(getDataFolder().toPath().resolve("transactions.log"));
            trades = new TradeEngine(journal);
            if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
                try { citizens = new CitizensBridge(this, this::npcClick); }
                catch (Exception e) { getLogger().severe("Citizens hook gagal. Toko NPC tetap terkunci: " + e); }
            }
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("CADERA / MENKIESTES | access=" + route() + " | shops=" + catalog.all().size() + " | finite-stock=" + stock.finiteProducts());
            if (!journal.pending().isEmpty()) getLogger().warning("Ada transaksi belum selesai: /cdrshop pending (console). Review sebelum resolve.");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "CdrShopEconomy gagal aktif dengan aman", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        closeMenus();
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
        if (!Double.isFinite(nextDistance) || nextDistance < 1 || nextDistance > 32 || nextSeconds < 10 || nextSeconds > 600)
            throw new IllegalArgumentException("Jarak NPC 1-32 blok; sesi 10-600 detik.");

        Map<Integer, String> nextBindings = new LinkedHashMap<>();
        var path = getDataFolder().toPath().resolve("npcs.yml");
        if (Files.exists(path)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(path.toFile());
            var section = yaml.getConfigurationSection("bindings");
            if (section != null) for (String key : section.getKeys(false)) {
                int npc = Integer.parseInt(key);
                if (npc < 0) throw new IllegalArgumentException("NPC ID negatif");
                nextBindings.put(npc, Filesafe.id(section.getString(key, "")));
            }
        }
        catalog.load();
        mode = nextMode;
        missing = nextMissing;
        distance = nextDistance;
        sessionSeconds = nextSeconds;
        bindings.clear();
        bindings.putAll(nextBindings);
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

    private void access(Player p, String shopId, Integer npc) {
        if (!p.hasPermission("cdrshopeconomy.use")) throw new IllegalArgumentException("Tidak punya izin toko.");
        if (p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)
            throw new IllegalArgumentException("Transaksi hanya saat hidup di Survival/Adventure.");
        if (route() == AccessPolicy.Route.DISABLED) throw new IllegalArgumentException("Toko dinonaktifkan: Citizens tidak tersedia.");
        if (npc == null && route() == AccessPolicy.Route.NPC) throw new IllegalArgumentException("Temui NPC pedagang untuk membeli atau menjual barang.");
        if (npc != null && (route() != AccessPolicy.Route.NPC || citizens == null || !Objects.equals(bindings.get(npc), shopId) || !citizens.near(p, npc, distance)))
            throw new IllegalArgumentException("Sesi NPC tidak valid. Dekati dan klik kembali pedagang.");
        if (shopId != null && !catalog.get(shopId).allows(p)) throw new IllegalArgumentException("Tidak punya izin toko ini.");
    }

    private void npcClick(Player p, int id) {
        if (!bindings.containsKey(id) || route() != AccessPolicy.Route.NPC) return;
        try {
            String shopId = bindings.get(id);
            access(p, shopId, id);
            openBuy(p, shopId, id, 0);
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

    private Menu menu(Player p, Menu.Kind kind, String shopId, Integer npc, int page, String title) {
        return new Menu(kind, p.getUniqueId(), shopId, npc, page, System.currentTimeMillis() + sessionSeconds * 1000L, color(title));
    }

    private void navigation(Menu m, int size) {
        if (m.page > 0) m.inventory.setItem(45, icon(Material.ARROW, "&eHalaman sebelumnya"));
        if ((m.page + 1) * 45 < size) m.inventory.setItem(53, icon(Material.ARROW, "&eHalaman berikutnya"));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cTutup"));
    }

    private void openShops(Player p, int page, boolean editor) {
        if (!editor) access(p, null, null);
        Menu m = menu(p, editor ? Menu.Kind.EDIT_SHOPS : Menu.Kind.SHOPS, null, null, page,
            editor ? "&6Editor: pilih toko" : "&6CdrShopEconomy - Toko");
        List<Catalog.Shop> list = catalog.all().stream().filter(s -> editor || s.allows(p)).toList();
        for (int i = page * 45; i < Math.min(list.size(), (page + 1) * 45); i++) {
            var shop = list.get(i);
            int slot = i % 45;
            m.keys.put(slot, shop.id());
            m.inventory.setItem(slot, icon(Material.CHEST, shop.title(), "&7ID: " + shop.id(), "&eKlik untuk membuka"));
        }
        navigation(m, list.size());
        if (!editor) m.inventory.setItem(49, icon(Material.HOPPER, "&aJual Barang"));
        p.openInventory(m.inventory);
    }

    private void openBuy(Player p, String shopId, Integer npc, int page) {
        access(p, shopId, npc);
        var shopDef = catalog.get(shopId);
        Menu m = menu(p, Menu.Kind.BUY, shopId, npc, page, shopDef.title());
        var products = shopDef.products().stream().filter(e -> e.buy() > 0).toList();
        for (int i = page * 45; i < Math.min(products.size(), (page + 1) * 45); i++) {
            var product = products.get(i);
            int slot = i % 45;
            m.keys.put(slot, product.id());
            String stockLine = product.finiteStock() ? "&eStock: " + stock.display(shopId, product) : "&7Stock: unlimited";
            m.inventory.setItem(slot, label(product.template(),
                "&aBeli/unit: $" + Money.format(product.buy()),
                "&7Jual/unit: " + Money.format(product.sell()),
                stockLine,
                product.finiteStock() && stock.current(shopId, product) == 0 ? "&cHABIS" : "&eKlik pilih jumlah"));
        }
        navigation(m, products.size());
        m.inventory.setItem(49, icon(Material.HOPPER, "&aJual ke toko ini"));
        p.openInventory(m.inventory);
    }

    private Catalog.Product product(String shopId, String id) {
        return catalog.get(shopId).products().stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Item sudah berubah; buka ulang toko."));
    }

    private void quantity(Player p, Menu source, Catalog.Product product) {
        Menu m = menu(p, Menu.Kind.QUANTITY, source.shop, source.npc, 0, "&6Pilih jumlah pembelian");
        m.product = product;
        int[] choices = {1, 16, 32, 64};
        for (int i = 0; i < choices.length; i++) {
            int qty = choices[i];
            boolean enough = !product.finiteStock() || stock.current(source.shop, product) >= qty;
            if (enough) m.keys.put(19 + i * 2, Integer.toString(qty));
            m.inventory.setItem(19 + i * 2, label(product.template(),
                "&eBeli " + qty + " item",
                "&aTotal: $" + Money.format(Money.total(product.buy(), qty)),
                enough ? "&aStock tersedia" : "&cStock tidak cukup"));
        }
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal"));
        p.openInventory(m.inventory);
    }

    private void buyConfirmation(Player p, Menu source, int amount) {
        Menu m = menu(p, Menu.Kind.BUY_CONFIRM, source.shop, source.npc, 0, "&6Konfirmasi pembelian");
        m.product = source.product;
        m.quantity = amount;
        m.inventory.setItem(22, label(m.product.template(),
            "&eJumlah: " + amount,
            "&aTotal: $" + Money.format(Money.total(m.product.buy(), amount)),
            m.product.finiteStock() ? "&eStock sekarang: " + stock.display(source.shop, m.product) : "&7Stock: unlimited"));
        m.inventory.setItem(49, icon(Material.LIME_CONCRETE, "&aKONFIRMASI BELI"));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal"));
        p.openInventory(m.inventory);
    }

    private void openSell(Player p, String shopId, Integer npc) {
        access(p, shopId, npc);
        Menu m = menu(p, Menu.Kind.SELL, shopId, npc, 0, "&6Jual - pilih item inventory");
        renderSell(p, m);
        p.openInventory(m.inventory);
    }

    private Catalog.Offer sellOffer(Player p, String onlyShop, ItemStack stack, int amount, Map<StockLedger.Key, Integer> pending) {
        for (Catalog.Offer offer : catalog.sellOffers(p, onlyShop, stack)) {
            Catalog.Product product = offer.product();
            if (!product.finiteStock() || stock.canAdjust(offer.shopId(), product, amount, pending)) return offer;
        }
        return null;
    }

    private SellPlan planSell(Player p, String onlyShop, Map<Integer, ItemStack> chosen) {
        long total = 0;
        int quantity = 0;
        Map<StockLedger.Key, Integer> deltas = new LinkedHashMap<>();
        for (var selected : chosen.entrySet()) {
            ItemStack stack = selected.getValue();
            Catalog.Offer offer = sellOffer(p, onlyShop, stack, stack.getAmount(), deltas);
            if (offer == null)
                throw new IllegalArgumentException("Salah satu item tidak diterima atau kapasitas stock merchant sudah penuh.");
            Catalog.Product product = offer.product();
            total = Math.addExact(total, Money.total(product.sell(), stack.getAmount()));
            quantity = Math.addExact(quantity, stack.getAmount());
            if (product.finiteStock()) {
                StockLedger.Key key = new StockLedger.Key(offer.shopId(), product.id());
                deltas.merge(key, stack.getAmount(), Math::addExact);
            }
        }
        return new SellPlan(total, quantity, Map.copyOf(deltas));
    }

    private void renderSell(Player p, Menu m) {
        m.inventory.clear();
        long total = 0;
        int count = 0;
        if (!m.selected.isEmpty()) {
            try {
                SellPlan plan = planSell(p, m.shop, m.selected);
                total = plan.total();
                count = plan.quantity();
            } catch (Exception ignored) {
                // A changed stock state will be reported on confirm; selected copies remain visible.
            }
        }
        for (var chosen : m.selected.entrySet())
            m.inventory.setItem(chosen.getKey(), label(chosen.getValue(), "&eKlik untuk batalkan pilihan"));
        m.inventory.setItem(45, icon(Material.PAPER, "&eKlik item di inventory bawah",
            "&7Ini salinan pilihan, bukan tempat penitipan.", "&7Barang asli tetap di inventory hingga konfirmasi."));
        m.inventory.setItem(48, icon(Material.HOPPER, "&ePilih semua item yang laku"));
        m.inventory.setItem(49, icon(Material.LIME_CONCRETE, "&aKONFIRMASI JUAL", "&fJumlah: " + count, "&aTotal: $" + Money.format(total)));
        m.inventory.setItem(50, icon(Material.BARRIER, "&cBatal / Tutup"));
    }

    private void openEditor(Player p, String shopId, int page) {
        Menu m = menu(p, Menu.Kind.EDIT_ITEMS, shopId, null, page, "&6Editor " + shopId);
        var entries = catalog.get(shopId).products();
        for (int i = page * 45; i < Math.min(entries.size(), (page + 1) * 45); i++) {
            var e = entries.get(i);
            m.keys.put(i % 45, e.id());
            m.inventory.setItem(i % 45, label(e.template(),
                "&eID: " + e.id(),
                "&aBeli: " + Money.format(e.buy()),
                "&7Jual: " + Money.format(e.sell()),
                "&eStock: " + stock.display(shopId, e),
                "&eKlik edit harga"));
        }
        navigation(m, entries.size());
        m.inventory.setItem(49, icon(Material.PAPER, "&eKlik item inventory untuk menambah", "&7Template disalin, item asli tidak diambil."));
        p.openInventory(m.inventory);
    }

    private void editPrice(Player p, String shopId, Catalog.Product product) {
        Menu m = menu(p, Menu.Kind.EDIT_PRICE, shopId, null, 0, "&6Editor harga");
        m.product = product;
        m.buy = product.buy();
        m.sell = product.sell();
        renderEditor(m);
        p.openInventory(m.inventory);
    }

    private void renderEditor(Menu m) {
        m.inventory.setItem(4, label(m.product.template(),
            "&eID: " + m.product.id(),
            "&aBeli: " + Money.format(m.buy),
            "&7Jual: " + Money.format(m.sell),
            "&eStock: " + stock.display(m.shop, m.product)));
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
                    case SHOPS, EDIT_SHOPS -> {
                        if (key != null) {
                            if (m.editor()) openEditor(p, key, 0);
                            else openBuy(p, key, null, 0);
                        } else if (raw == 49 && !m.editor()) openSell(p, null, null);
                        else if ((raw == 45 || raw == 53) && m.inventory.getItem(raw) != null)
                            openShops(p, m.page + (raw == 45 ? -1 : 1), m.editor());
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
                                if (stack == null || stack.getType().isAir()) continue;
                                Map<Integer, ItemStack> candidate = new LinkedHashMap<>(m.selected);
                                candidate.put(i, stack.clone());
                                try {
                                    planSell(p, m.shop, candidate);
                                    m.selected.put(i, stack.clone());
                                } catch (Exception ignored) {}
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
                            catalog.saveProduct(m.shop, m.product.withPrices(m.buy, m.sell));
                            closeMenus();
                            message(p, "Item tersimpan: " + m.product.id());
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
            if (m.selected.containsKey(slot)) {
                m.selected.remove(slot);
            } else {
                Map<Integer, ItemStack> candidate = new LinkedHashMap<>(m.selected);
                candidate.put(slot, stack.clone());
                planSell(p, m.shop, candidate);
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

    private void sell(Player p, String shopId, Integer npc, Map<Integer, ItemStack> chosen) throws Exception {
        access(p, shopId, npc);
        if (chosen.isEmpty()) throw new IllegalArgumentException("Belum ada item yang dipilih.");
        ItemStack[] before = copy(p.getInventory().getStorageContents());
        ItemStack[] after = copy(before);
        for (var entry : chosen.entrySet()) {
            int slot = entry.getKey();
            ItemStack expected = entry.getValue();
            if (slot < 0 || slot >= before.length || !Objects.equals(before[slot], expected))
                throw new IllegalArgumentException("Inventory berubah. Buka ulang dan pilih kembali item.");
            after[slot] = null;
        }
        SellPlan plan = planSell(p, shopId, chosen);
        StockLedger.Change stockChange = stock.plan(plan.stockDeltas());
        execute(p, before, after, plan.total(), true, plan.quantity(), shopId, stockChange);
    }

    private void buy(Player p, Menu m) throws Exception {
        access(p, m.shop, m.npc);
        var current = product(m.shop, m.product.id());
        if (current.buy() != m.product.buy() || !current.template().isSimilar(m.product.template()))
            throw new IllegalArgumentException("Harga/item berubah; buka ulang toko.");

        Map<StockLedger.Key, Integer> stockDeltas = new LinkedHashMap<>();
        if (current.finiteStock()) {
            StockLedger.Key key = new StockLedger.Key(m.shop, current.id());
            stockDeltas.put(key, -m.quantity);
        }
        StockLedger.Change stockChange = stock.plan(stockDeltas);

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
        for (int i = 0; i < after.length && remaining > 0; i++) {
            if (after[i] == null || after[i].getType().isAir()) {
                after[i] = current.template().clone();
                int add = Math.min(remaining, Math.min(after[i].getMaxStackSize(), p.getInventory().getMaxStackSize()));
                after[i].setAmount(add);
                remaining -= add;
            }
        }
        if (remaining > 0) throw new IllegalArgumentException("Ruang inventory tidak cukup. Tidak ada uang dipotong.");
        execute(p, before, after, Money.total(current.buy(), m.quantity), false, m.quantity, m.shop, stockChange);
    }

    private void execute(Player p, ItemStack[] before, ItemStack[] after, long amount, boolean credit,
                         int count, String shopId, StockLedger.Change stockChange) throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Transaksi wajib main thread.");
        if (!wallet.available()) throw new IllegalArgumentException("Provider economy tidak tersedia.");
        String detail = (credit ? "SELL" : "BUY") + " shop=" + (shopId == null ? "all" : shopId)
            + " cents=" + amount + " count=" + count + " " + stockChange.detail()
            + " before=" + encode(before) + " after=" + encode(after);
        try {
            List<TradeEngine.StatePort> states = new ArrayList<>();
            states.add(new TradeEngine.InventoryPort() {
                public void apply() { p.getInventory().setStorageContents(copy(after)); }
                public void restore() { p.getInventory().setStorageContents(copy(before)); }
                public void persist() { p.saveData(); }
            });
            if (!stockChange.empty()) states.add(stockChange);
            var result = trades.run(p.getUniqueId(), detail, states, () -> wallet.change(p, amount, credit));
            if (result == TradeEngine.Result.DECLINED)
                message(p, "Transaksi ditolak economy (saldo kurang/batas saldo). Item dan stock dikembalikan.");
            else
                message(p, "Berhasil " + (credit ? "menjual " : "membeli ") + count + " item, total $" + Money.format(amount));
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Review transaksi " + p.getUniqueId() + " di transactions.log", ex);
            throw new IllegalStateException("Transaksi terkunci untuk pemeriksaan admin. Jangan ulangi pembayaran.", ex);
        }
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
                    if (args.length == 0) openShops(p, 0, false);
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
            message(sender, "/cdrshop price <toko> <id> <beli> <jual> | remove <toko> <id>");
            message(sender, "/cdrshop stock <toko> <id> [jumlah] | stocklimit <toko> <id> unlimited|<max> [initial]");
            message(sender, "/cdrshop bind <toko> <id-NPC> | unbind <id-NPC> | reload | status");
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
                closeMenus();
                message(sender, "Template disalin dan disimpan. Stock default: unlimited.");
            }
            case "price" -> {
                require(a, 5);
                var old = product(a[1], a[2]);
                catalog.saveProduct(a[1], old.withPrices(Money.parse(a[3]), Money.parse(a[4])));
                closeMenus();
                message(sender, "Harga diperbarui.");
            }
            case "remove" -> {
                require(a, 3);
                catalog.remove(a[1], a[2]);
                stock.reconcile(catalog);
                closeMenus();
                message(sender, "Listing dihapus; item pemain tidak disentuh.");
            }
            case "stock" -> {
                if (a.length != 3 && a.length != 4) throw new IllegalArgumentException("/cdrshop stock <toko> <id> [jumlah]");
                var item = product(a[1], a[2]);
                if (a.length == 3) {
                    message(sender, "Stock " + a[1] + "/" + a[2] + " = " + stock.display(a[1], item));
                } else {
                    int amount = Integer.parseInt(a[3]);
                    stock.set(a[1], item, amount);
                    closeMenus();
                    message(sender, "Stock " + a[1] + "/" + a[2] + " = " + stock.display(a[1], item));
                }
            }
            case "stocklimit" -> {
                if (a.length < 4 || a.length > 5)
                    throw new IllegalArgumentException("/cdrshop stocklimit <toko> <id> unlimited|<max> [initial]");
                var old = product(a[1], a[2]);
                if (a[3].equalsIgnoreCase("unlimited")) {
                    if (a.length != 4) throw new IllegalArgumentException("Unlimited tidak memakai initial.");
                    catalog.saveProduct(a[1], old.withStock(Catalog.UNLIMITED_STOCK, Catalog.UNLIMITED_STOCK));
                    stock.reconcile(catalog);
                    closeMenus();
                    message(sender, "Stock " + a[1] + "/" + a[2] + " sekarang unlimited.");
                } else {
                    int max = Integer.parseInt(a[3]);
                    int initial = a.length == 5 ? Integer.parseInt(a[4]) : max;
                    Catalog.validateStock(max, initial);
                    if (old.finiteStock() && stock.current(a[1], old) > max)
                        throw new IllegalArgumentException("Current stock lebih besar dari max baru. Turunkan dulu dengan /cdrshop stock.");
                    catalog.saveProduct(a[1], old.withStock(max, initial));
                    stock.reconcile(catalog);
                    closeMenus();
                    message(sender, "Finite stock aktif: " + stock.display(a[1], product(a[1], a[2])) + ".");
                }
            }
            case "bind" -> {
                require(a, 3);
                catalog.get(a[1]);
                int id = Integer.parseInt(a[2]);
                if (citizens == null || !citizens.exists(id))
                    throw new IllegalArgumentException("NPC Citizens tidak ditemukan. Pastikan Citizens aktif sejak startup.");
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
                stock.reconcile(catalog);
                message(sender, "Konfigurasi + stock valid dimuat. Akses=" + route());
            }
            case "status" -> message(sender,
                "v1.0.0-beta.2 | akses=" + route() + " | toko=" + catalog.all().size()
                    + " | finite-stock=" + stock.finiteProducts() + " | NPC=" + bindings + " | economy=" + wallet.available());
            case "pending" -> {
                console(sender);
                message(sender, "Transaksi untuk review: " + journal.pending());
            }
            case "resolve" -> {
                console(sender);
                require(a, 3);
                if (!a[2].equals("reviewed"))
                    throw new IllegalArgumentException("Review uang/item/stock di journal dahulu; argumen akhir: reviewed");
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
            if (args.length == 1)
                options.addAll(List.of("create", "editor", "add", "price", "remove", "stock", "stocklimit", "bind", "unbind", "reload", "status", "pending", "resolve"));
            if (args.length == 2 && Set.of("editor", "add", "price", "remove", "stock", "stocklimit", "bind").contains(args[0].toLowerCase(Locale.ROOT)))
                catalog.all().forEach(s -> options.add(s.id()));
            if (args.length == 3 && Set.of("price", "remove", "stock", "stocklimit").contains(args[0].toLowerCase(Locale.ROOT))) {
                try { catalog.get(args[1]).products().forEach(p -> options.add(p.id())); }
                catch (Exception ignored) {}
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("stocklimit")) options.add("unlimited");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
