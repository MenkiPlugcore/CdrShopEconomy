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
        if (journal.blocked(player) || !busy.add(player))
            throw new IllegalStateException("Transaksi terkunci; hubungi admin untuk review journal.");

        UUID tx = null;
        boolean paymentStarted = false;
        try {
            tx = journal.begin(player, detail);
            try {
                inventory.apply();
                inventory.persist();
            } catch (Exception prePaymentFailure) {
                // Wallet has definitely not been touched yet. Roll back aggressively instead of
                // leaving changed inventory around just because persistence failed.
                try {
                    inventory.restore();
                    inventory.persist();
                    journal.finish(tx, "ABORT", "pre-payment-failure-rolled-back");
                } catch (Exception rollbackFailure) {
                    prePaymentFailure.addSuppressed(rollbackFailure);
                    // Keep journal entry pending. Manual review is required because rollback durability
                    // could not be proven.
                }
                throw prePaymentFailure;
            }

            paymentStarted = true;
            if (!payment.execute()) {
                inventory.restore();
                inventory.persist();
                journal.finish(tx, "ABORT", "wallet-declined");
                return Result.DECLINED;
            }

            inventory.persist();
            journal.finish(tx, "COMMIT", "success");
            return Result.SUCCESS;
        } catch (Exception failure) {
            // Once payment execution has started, an exception may mean the provider changed money
            // before failing. Never auto-retry or auto-restore that ambiguous outcome.
            if (paymentStarted) throw failure;
            throw failure;
        } finally {
            busy.remove(player);
        }
    }
}
