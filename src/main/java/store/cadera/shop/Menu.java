package store.cadera.shop;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

final class Menu implements InventoryHolder {
    enum Kind { CATEGORIES, SHOPS, BUY, QUANTITY, BUY_CONFIRM, SELL, EDIT_SHOPS, EDIT_ITEMS, EDIT_PRICE }
    final Kind kind;
    final UUID owner;
    final String shop;
    final Integer npc;
    final long expires;
    final int page;
    final Inventory inventory;
    final Map<Integer, String> keys = new HashMap<>();
    final Map<Integer, ItemStack> selected = new TreeMap<>();
    Catalog.Product product;
    String category;
    int quantity = 1;
    long quotedPrice = -1;
    long buy = -1, sell = -1;

    Menu(Kind kind, UUID owner, String shop, Integer npc, int page, long expires, String title) {
        this.kind = kind;
        this.owner = owner;
        this.shop = shop;
        this.npc = npc;
        this.page = page;
        this.expires = expires;
        this.inventory = Bukkit.createInventory(this, 54, title);
    }

    @Override public Inventory getInventory() { return inventory; }
    boolean editor() { return kind == Kind.EDIT_SHOPS || kind == Kind.EDIT_ITEMS || kind == Kind.EDIT_PRICE; }
}
