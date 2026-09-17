package store.cadera.shop;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Caller must run on the main server thread. Inventory/stock mutation and wallet call never run async. */
final class TradeEngine {
    interface InventoryPort {
        void apply() throws Exception;
        void restore() throws Exception;
        void persist() throws Exception;
    }
    interface Payment { boolean execute() throws Exception; }
    enum Result { SUCCESS, DECLINED }

    /** Failure before the payment call where the original state was restored and persisted safely. */
    static final class SafeAbortException extends Exception {
        SafeAbortException(String message, Throwable cause) { super(message, cause); }
    }

    private final Journal journal;
    private final Set<UUID> busy = new HashSet<>();
    TradeEngine(Journal journal) { this.journal = journal; }

    Result run(UUID player, String detail, InventoryPort inventory, Payment payment) throws Exception {
        if (journal.blocked(player) || !busy.add(player))
            throw new IllegalStateException("Transaksi terkunci; hubungi admin untuk review journal.");
        try {
            UUID tx = journal.begin(player, detail);

            // No money has moved yet. If preparing/persisting state fails, restoration is safe.
            try {
                inventory.apply();
                inventory.persist();
            } catch (Exception preparationFailure) {
                try {
                    inventory.restore();
                    inventory.persist();
                    journal.finish(tx, "ABORT", "pre-payment-failure-restored");
                } catch (Exception rollbackFailure) {
                    preparationFailure.addSuppressed(rollbackFailure);
                    // Recovery itself is uncertain. Leave BEGIN pending and fail closed.
                    throw preparationFailure;
                }
                throw new SafeAbortException("Transaksi dibatalkan sebelum pembayaran; state dipulihkan.", preparationFailure);
            }

            // From this point a thrown provider exception is ambiguous: never auto-restore/replay.
            if (!payment.execute()) {
                inventory.restore();
                inventory.persist();
                journal.finish(tx, "ABORT", "wallet-declined");
                return Result.DECLINED;
            }

            // Payment succeeded. Any persistence/journal failure must stay pending for manual review.
            inventory.persist();
            journal.finish(tx, "COMMIT", "success");
            return Result.SUCCESS;
        } finally {
            busy.remove(player);
        }
    }
}
