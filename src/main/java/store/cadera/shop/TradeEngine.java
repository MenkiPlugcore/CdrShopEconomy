package store.cadera.shop;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Caller must run on the main server thread. Inventory mutation and wallet call never run async. */
final class TradeEngine {
    interface InventoryPort { void apply(); void restore(); void persist(); }
    interface Payment { boolean execute() throws Exception; }
    enum Result { SUCCESS, DECLINED }
    private final Journal journal;
    private final Set<UUID> busy = new HashSet<>();
    TradeEngine(Journal journal) { this.journal = journal; }
    Result run(UUID player, String detail, InventoryPort inventory, Payment payment) throws Exception {
        if (journal.blocked(player) || !busy.add(player)) throw new IllegalStateException("Transaksi terkunci; hubungi admin untuk review journal.");
        try {
            UUID tx = journal.begin(player, detail);
            inventory.apply(); inventory.persist();
            // A thrown exception is ambiguous: do not restore or pay again automatically.
            if (!payment.execute()) {
                inventory.restore(); inventory.persist(); journal.finish(tx, "ABORT", "wallet-declined");
                return Result.DECLINED;
            }
            inventory.persist(); journal.finish(tx, "COMMIT", "success");
            return Result.SUCCESS;
        } finally { busy.remove(player); }
    }
}
