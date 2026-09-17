package store.cadera.shop;

/** Stock-aware quotes. Dynamic prices preserve a global no-buy-low/sell-high guard for similar items. */
final class Pricing {
    private static final long MAX_CENTS = 100_000_000_000L;
    private Pricing() {}

    static long buy(Catalog catalog, StockLedger stock, String shopId, Catalog.Product product) {
        if (product.buy() < 0) return -1;
        long quote = raw(product.buy(), product, stock.current(shopId, product));
        long sellFloor = 0;
        for (Catalog.Shop otherShop : catalog.all()) for (Catalog.Product other : otherShop.products()) {
            if (other.sell() <= 0 || !other.template().isSimilar(product.template())) continue;
            long otherSell = raw(other.sell(), other, stock.current(otherShop.id(), other));
            sellFloor = Math.max(sellFloor, otherSell);
        }
        return Math.min(MAX_CENTS, Math.max(quote, sellFloor));
    }

    static long sell(Catalog catalog, StockLedger stock, String shopId, Catalog.Product product) {
        if (product.sell() < 0) return -1;
        long quote = raw(product.sell(), product, stock.current(shopId, product));
        long buyCeiling = Long.MAX_VALUE;
        for (Catalog.Shop otherShop : catalog.all()) for (Catalog.Product other : otherShop.products()) {
            if (other.buy() <= 0 || !other.template().isSimilar(product.template())) continue;
            long otherBuy = raw(other.buy(), other, stock.current(otherShop.id(), other));
            buyCeiling = Math.min(buyCeiling, otherBuy);
        }
        if (buyCeiling != Long.MAX_VALUE) quote = Math.min(quote, buyCeiling);
        return Math.max(1, quote);
    }

    static long raw(long base, Catalog.Product product, int currentStock) {
        if (base < 0) return -1;
        if (!product.finiteStock() || product.dynamicPercent() == 0) return base;
        long max = product.stockMax();
        long current = Math.max(0, Math.min(max, currentStock));
        long deviation = max - (2L * current); // +max empty, 0 half, -max full
        long strengthBps = (long) product.dynamicPercent() * 100L;
        long multiplierBps = 10_000L + (strengthBps * deviation) / max;
        long scaled = Math.multiplyExact(base, multiplierBps);
        long rounded = (scaled + 5_000L) / 10_000L;
        return Math.max(1, Math.min(MAX_CENTS, rounded));
    }
}
