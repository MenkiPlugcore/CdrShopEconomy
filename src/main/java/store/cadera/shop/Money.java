package store.cadera.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}
    public static long parse(String text) {
        if (text.equals("-1")) return -1;
        BigDecimal number = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
        if (number.signum() <= 0 || number.compareTo(new BigDecimal("1000000000")) > 0)
            throw new IllegalArgumentException("Harga harus > 0 hingga 1 miliar, atau -1 untuk nonaktif.");
        return number.movePointRight(2).longValueExact();
    }
    public static String format(long cents) {
        return cents < 0 ? "NONAKTIF" : BigDecimal.valueOf(cents, 2).toPlainString();
    }
    public static String config(long cents) { return cents < 0 ? "-1" : format(cents); }
    public static long total(long unit, int count) {
        if (unit <= 0 || count <= 0) throw new IllegalArgumentException("Harga/jumlah tidak valid.");
        return Math.multiplyExact(unit, count);
    }
    public static void validatePair(long buy, long sell) {
        if (buy == 0 || sell == 0 || buy < -1 || sell < -1 || buy > 100_000_000_000L || sell > 100_000_000_000L)
            throw new IllegalArgumentException("Harga tidak valid.");
        if (buy > 0 && sell > buy) throw new IllegalArgumentException("Harga jual tidak boleh melebihi harga beli.");
    }
}
