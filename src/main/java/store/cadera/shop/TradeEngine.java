package store.cadera.shop;

import java.util.*;

/** Caller must run on the main server thread. State mutation and wallet calls never run async. */
final class TradeEngine {
    interface StatePort {
        void apply() throws Exception;
        void restore() throws Exception;
        void persist() throws Exception;
    }
    interface InventoryPort extends StatePort {}
    interface Payment { boolean execute() throws Exception; }
    enum Result { SUCCESS, DECLINED }

    private final Journal journal;
    private final Set<UUID> busy = new HashSet<>();

    TradeEngine(Journal journal) { this.journal = journal; }

    Result run(UUID player, String detail, InventoryPort inventory, Payment payment) throws Exception {
        return run(player, detail, List.of(inventory), payment);
    }

    Result run(UUID player, String detail, List<? extends StatePort> states, Payment payment) throws Exception {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(payment, "payment");
        if (states.isEmpty()) throw new IllegalArgumentException("Transaksi tanpa state tidak diizinkan.");
        if (journal.blocked(player) || !busy.add(player))
            throw new IllegalStateException("Transaksi terkunci; hubungi admin untuk review journal.");

        UUID tx = null;
        boolean paymentStarted = false;
        try {
            tx = journal.begin(player, detail);
            try {
                for (StatePort state : states) state.apply();
                for (StatePort state : states) state.persist();
            } catch (Exception prePaymentFailure) {
                // Wallet has definitely not been touched yet. Restore every participating state.
                Exception rollbackFailure = rollback(states);
                if (rollbackFailure == null) {
                    journal.finish(tx, "ABORT", "pre-payment-failure-rolled-back");
                } else {
                    prePaymentFailure.addSuppressed(rollbackFailure);
                    // Keep BEGIN pending because durable rollback could not be proven.
                }
                throw prePaymentFailure;
            }

            paymentStarted = true;
            if (!payment.execute()) {
                Exception rollbackFailure = rollback(states);
                if (rollbackFailure != null) throw rollbackFailure;
                journal.finish(tx, "ABORT", "wallet-declined");
                return Result.DECLINED;
            }

            // State was durably persisted before calling the wallet. No second player/stock save is
            // needed here; COMMIT is the final durable marker after confirmed payment success.
            journal.finish(tx, "COMMIT", "success");
            return Result.SUCCESS;
        } catch (Exception failure) {
            // Once payment execution starts, an exception may mean the provider changed money before
            // failing. Preserve the pending BEGIN and current durable state for manual reconciliation.
            if (paymentStarted) throw failure;
            throw failure;
        } finally {
            busy.remove(player);
        }
    }

    private static Exception rollback(List<? extends StatePort> states) {
        Exception first = null;
        for (int i = states.size() - 1; i >= 0; i--) {
            try { states.get(i).restore(); }
            catch (Exception e) { first = collect(first, e); }
        }
        // Attempt every persistence even if another one failed, maximizing the chance of restoring a
        // consistent durable snapshot. Any failure still leaves the journal pending for manual review.
        for (StatePort state : states) {
            try { state.persist(); }
            catch (Exception e) { first = collect(first, e); }
        }
        return first;
    }

    private static Exception collect(Exception first, Exception next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }
}
