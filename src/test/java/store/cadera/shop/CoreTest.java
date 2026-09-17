package store.cadera.shop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class CoreTest {
    @TempDir Path temp;

    @ParameterizedTest @CsvSource({"1,100", "0.01,1", "120.25,12025", "1000000000,100000000000", "-1,-1"})
    void pricesUseExactCents(String price, long cents) { assertEquals(cents, Money.parse(price)); }
    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "0", "-2", "0.001", "1000000000.01", "", "abc"})
    void invalidPricesRejected(String price) { assertThrows(RuntimeException.class, () -> Money.parse(price)); }
    @Test void totals() { assertEquals(480000, Money.total(Money.parse("75"), 64)); }
    @Test void disabledSideCannotTrade() { assertThrows(IllegalArgumentException.class, () -> Money.total(-1, 64)); }
    @Test void zeroQuantityCannotTrade() { assertThrows(IllegalArgumentException.class, () -> Money.total(100, 0)); }
    @Test void overflowRejected() { assertThrows(ArithmeticException.class, () -> Money.total(Long.MAX_VALUE, 64)); }
    @Test void losslessFormatting() { assertEquals("123.45", Money.format(12345)); assertEquals("-1", Money.config(-1)); }
    @Test void noBuySellLoop() { assertThrows(IllegalArgumentException.class, () -> Money.validatePair(100, 101)); }
    @Test void disabledPricePairAllowed() { assertDoesNotThrow(() -> Money.validatePair(-1, 100)); }

    @Test void finiteStockValidation() {
        assertDoesNotThrow(() -> Catalog.validateStock(-1, -1));
        assertDoesNotThrow(() -> Catalog.validateStock(256, 128));
        assertThrows(IllegalArgumentException.class, () -> Catalog.validateStock(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Catalog.validateStock(100, 101));
        assertThrows(IllegalArgumentException.class, () -> Catalog.validateStock(-2, -2));
    }

    private static final StockLedger.Key WHEAT = new StockLedger.Key("umum", "wheat");
    private static final Map<StockLedger.Key, StockLedger.Spec> STOCK_SPEC =
        Map.of(WHEAT, new StockLedger.Spec(100, 40));

    @Test void stockInitializesAndSurvivesRestart() throws Exception {
        Path file = temp.resolve("stock.yml");
        StockLedger first = new StockLedger(file);
        first.reconcile(STOCK_SPEC);
        assertEquals(40, first.current(WHEAT));
        assertEquals(1, first.finiteProducts());

        first.set(WHEAT, 73);
        StockLedger restarted = new StockLedger(file);
        restarted.reconcile(STOCK_SPEC);
        assertEquals(73, restarted.current(WHEAT));
    }

    @Test void stockPlanRejectsOversellAndOverbuyCapacity() throws Exception {
        StockLedger ledger = new StockLedger(temp.resolve("stock.yml"));
        ledger.reconcile(STOCK_SPEC);

        assertThrows(IllegalArgumentException.class, () -> ledger.plan(Map.of(WHEAT, -41)));
        assertThrows(IllegalArgumentException.class, () -> ledger.plan(Map.of(WHEAT, 61)));
        assertTrue(ledger.canAdjust(WHEAT, -40, Map.of()));
        assertFalse(ledger.canAdjust(WHEAT, -41, Map.of()));
        assertTrue(ledger.canAdjust(WHEAT, 60, Map.of()));
        assertFalse(ledger.canAdjust(WHEAT, 61, Map.of()));
    }

    @Test void committedBuyDecrementsDurableStock() throws Exception {
        Path stockFile = temp.resolve("stock.yml");
        StockLedger ledger = new StockLedger(stockFile);
        ledger.reconcile(STOCK_SPEC);
        StockLedger.Change change = ledger.plan(Map.of(WHEAT, -16));

        try (Journal journal = new Journal(temp.resolve("tx.log"))) {
            assertEquals(TradeEngine.Result.SUCCESS,
                new TradeEngine(journal).run(UUID.randomUUID(), "BUY wheat 16", List.of(change), () -> true));
        }
        assertEquals(24, ledger.current(WHEAT));

        StockLedger restarted = new StockLedger(stockFile);
        restarted.reconcile(STOCK_SPEC);
        assertEquals(24, restarted.current(WHEAT));
    }

    @Test void declinedBuyRestoresDurableStock() throws Exception {
        Path stockFile = temp.resolve("stock.yml");
        StockLedger ledger = new StockLedger(stockFile);
        ledger.reconcile(STOCK_SPEC);
        StockLedger.Change change = ledger.plan(Map.of(WHEAT, -16));

        try (Journal journal = new Journal(temp.resolve("tx.log"))) {
            assertEquals(TradeEngine.Result.DECLINED,
                new TradeEngine(journal).run(UUID.randomUUID(), "BUY wheat 16", List.of(change), () -> false));
        }
        assertEquals(40, ledger.current(WHEAT));

        StockLedger restarted = new StockLedger(stockFile);
        restarted.reconcile(STOCK_SPEC);
        assertEquals(40, restarted.current(WHEAT));
    }

    @Test void committedSellIncreasesStockUpToCapacity() throws Exception {
        Path stockFile = temp.resolve("stock.yml");
        StockLedger ledger = new StockLedger(stockFile);
        ledger.reconcile(STOCK_SPEC);
        StockLedger.Change change = ledger.plan(Map.of(WHEAT, 60));

        try (Journal journal = new Journal(temp.resolve("tx.log"))) {
            assertEquals(TradeEngine.Result.SUCCESS,
                new TradeEngine(journal).run(UUID.randomUUID(), "SELL wheat 60", List.of(change), () -> true));
        }
        assertEquals(100, ledger.current(WHEAT));
        assertThrows(IllegalArgumentException.class, () -> ledger.plan(Map.of(WHEAT, 1)));
    }

    @Test void corruptOrOutOfRangeRuntimeStockFailsClosed() throws Exception {
        Path stockFile = temp.resolve("stock.yml");
        Files.writeString(stockFile, "stock:\n  umum:\n    wheat: 101\n");
        StockLedger ledger = new StockLedger(stockFile);
        assertThrows(IllegalArgumentException.class, () -> ledger.reconcile(STOCK_SPEC));

        Files.writeString(stockFile, "stock:\n  umum:\n    wheat: nope\n");
        assertThrows(IllegalArgumentException.class, () -> ledger.reconcile(STOCK_SPEC));
    }

    static Stream<Arguments> routes() {
        return Stream.of(
            Arguments.of("AUTO", "COMMAND", false, false, "COMMAND"),
            Arguments.of("AUTO", "COMMAND", false, true, "COMMAND"),
            Arguments.of("AUTO", "COMMAND", true, false, "COMMAND"),
            Arguments.of("AUTO", "COMMAND", true, true, "NPC"),
            Arguments.of("NPC_ONLY", "COMMAND", true, false, "NPC"),
            Arguments.of("NPC_ONLY", "COMMAND", false, true, "COMMAND"),
            Arguments.of("NPC_ONLY", "DISABLE", false, true, "DISABLED"),
            Arguments.of("COMMAND", "DISABLE", true, true, "COMMAND"));
    }

    @ParameterizedTest @MethodSource("routes")
    void accessRoutes(String mode, String missing, boolean citizens, boolean bound, String expected) {
        assertEquals(AccessPolicy.Route.valueOf(expected), AccessPolicy.route(AccessPolicy.Mode.valueOf(mode), AccessPolicy.Missing.valueOf(missing), citizens, bound));
    }

    static class Inventory implements TradeEngine.InventoryPort {
        int items = 64, persisted;
        public void apply() { items = 0; }
        public void restore() { items = 64; }
        public void persist() { persisted++; }
    }

    static class State implements TradeEngine.StatePort {
        int value, before, after, persisted;
        State(int before, int after) { this.value = before; this.before = before; this.after = after; }
        public void apply() { value = after; }
        public void restore() { value = before; }
        public void persist() { persisted++; }
    }

    @Test void successfulPaymentRemovesItemsAndCompletesJournal() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            Inventory inventory = new Inventory(); UUID user = UUID.randomUUID();
            assertEquals(TradeEngine.Result.SUCCESS, new TradeEngine(log).run(user, "SELL", inventory, () -> true));
            assertEquals(0, inventory.items);
            assertEquals(1, inventory.persisted);
            assertFalse(log.blocked(user));
            assertTrue(log.pending().isEmpty());
        }
    }

    @Test void successfulMultiStateTradePersistsInventoryAndStockBeforePayment() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            UUID user = UUID.randomUUID();
            Inventory inventory = new Inventory();
            State stock = new State(100, 84);
            assertEquals(TradeEngine.Result.SUCCESS,
                new TradeEngine(log).run(user, "BUY stock=100->84", List.of(inventory, stock), () -> true));
            assertEquals(0, inventory.items);
            assertEquals(84, stock.value);
            assertEquals(1, inventory.persisted);
            assertEquals(1, stock.persisted);
            assertTrue(log.pending().isEmpty());
        }
    }

    @Test void failedPaymentRestoresAllStates() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            Inventory inventory = new Inventory();
            State stock = new State(100, 84);
            assertEquals(TradeEngine.Result.DECLINED,
                new TradeEngine(log).run(UUID.randomUUID(), "BUY", List.of(inventory, stock), () -> false));
            assertEquals(64, inventory.items);
            assertEquals(100, stock.value);
            assertEquals(2, inventory.persisted);
            assertEquals(2, stock.persisted);
            assertTrue(log.pending().isEmpty());
        }
    }

    @Test void unknownPaymentDoesNotDuplicateAndLocksPlayerAcrossRestart() throws Exception {
        UUID user = UUID.randomUUID(); Path file = temp.resolve("tx.log"); Inventory inventory = new Inventory();
        try (Journal log = new Journal(file)) {
            TradeEngine engine = new TradeEngine(log);
            assertThrows(Exception.class, () -> engine.run(user, "SELL", inventory, () -> { throw new Exception("timeout after debit"); }));
            assertEquals(0, inventory.items);
            assertTrue(log.blocked(user));
            assertThrows(IllegalStateException.class, () -> engine.run(user, "SELL", inventory, () -> true));
        }
        try (Journal log = new Journal(file)) {
            assertTrue(log.blocked(user));
            log.finish(log.pending().keySet().iterator().next(), "RESOLVED", "manual review");
            assertFalse(log.blocked(user));
        }
    }

    @Test void reentrantTradeIsRejected() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            UUID user = UUID.randomUUID(); TradeEngine engine = new TradeEngine(log); Inventory inventory = new Inventory();
            engine.run(user, "SELL", inventory, () -> {
                assertThrows(IllegalStateException.class, () -> engine.run(user, "SELL", inventory, () -> true));
                return true;
            });
            assertTrue(log.pending().isEmpty());
        }
    }

    @Test void transientPrePaymentPersistFailureRollsBackWithoutCallingWallet() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            UUID user = UUID.randomUUID();
            Inventory inventory = new Inventory() {
                int calls;
                @Override public void persist() {
                    calls++;
                    if (calls == 1) throw new IllegalStateException("first save failed");
                    persisted++;
                }
            };
            assertThrows(IllegalStateException.class, () -> new TradeEngine(log).run(user, "BUY", inventory, () -> {
                fail("wallet must not be called before state persistence succeeds"); return true;
            }));
            assertEquals(64, inventory.items);
            assertFalse(log.blocked(user));
            assertTrue(log.pending().isEmpty());
        }
    }

    @Test void laterStatePrePaymentFailureRollsBackEarlierStateToo() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            UUID user = UUID.randomUUID();
            State inventory = new State(64, 0);
            State stock = new State(100, 84) {
                int calls;
                @Override public void persist() {
                    calls++;
                    if (calls == 1) throw new IllegalStateException("stock save failed");
                    persisted++;
                }
            };
            assertThrows(IllegalStateException.class,
                () -> new TradeEngine(log).run(user, "BUY", List.of(inventory, stock), () -> { fail("wallet called"); return true; }));
            assertEquals(64, inventory.value);
            assertEquals(100, stock.value);
            assertFalse(log.blocked(user));
        }
    }

    @Test void stateFailureThatCannotBeDurablyRolledBackStaysLocked() throws Exception {
        try (Journal log = new Journal(temp.resolve("tx.log"))) {
            UUID user = UUID.randomUUID();
            Inventory inventory = new Inventory() { public void persist() { throw new IllegalStateException("save failed"); } };
            assertThrows(IllegalStateException.class,
                () -> new TradeEngine(log).run(user, "SELL", inventory, () -> { fail("wallet must not be called"); return true; }));
            assertTrue(log.blocked(user));
        }
    }

    @Test void tornJournalFailsClosed() throws Exception {
        Path file = temp.resolve("tx.log"); Files.writeString(file, "truncated");
        assertThrows(java.io.IOException.class, () -> new Journal(file));
    }

    @Test void orphanTerminalJournalRecordFailsClosed() throws Exception {
        Path file = temp.resolve("tx.log");
        UUID tx = UUID.randomUUID(), user = UUID.randomUUID();
        Files.writeString(file, "2026-09-17T00:00:00Z\tCOMMIT\t" + tx + "\t" + user + "\tdone\n");
        assertThrows(java.io.IOException.class, () -> new Journal(file));
    }

    @Test void completedTransactionSurvivesRestart() throws Exception {
        UUID user = UUID.randomUUID(); Path file = temp.resolve("tx.log");
        try (Journal log = new Journal(file)) { UUID tx = log.begin(user, "BUY"); log.finish(tx, "COMMIT", "done"); }
        try (Journal log = new Journal(file)) { assertFalse(log.blocked(user)); assertTrue(log.pending().isEmpty()); }
    }
}
