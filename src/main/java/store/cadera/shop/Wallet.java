package store.cadera.shop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Uses the public Vault API reflectively, without bundling a second copy of it. */
final class Wallet {
    private final Class<?> api;
    private final Method deposit, withdraw, success;
    private final Field responseAmount;

    Wallet() throws ReflectiveOperationException {
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) throw new IllegalStateException("Vault tidak tersedia.");
        ClassLoader loader = vault.getClass().getClassLoader();
        api = Class.forName("net.milkbowl.vault.economy.Economy", true, loader);
        deposit = api.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        withdraw = api.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        Class<?> response = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader);
        success = response.getMethod("transactionSuccess");
        responseAmount = response.getField("amount");
        if (!available()) throw new IllegalStateException("Vault ada, tetapi provider economy tidak ada (misalnya EssentialsX).");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RegisteredServiceProvider<?> registration() { return Bukkit.getServicesManager().getRegistration((Class) api); }

    boolean available() {
        var registration = registration();
        return registration != null && registration.getPlugin().isEnabled();
    }

    boolean change(OfflinePlayer player, long cents, boolean credit) throws Exception {
        var registration = registration();
        if (registration == null || !registration.getPlugin().isEnabled()) return false;
        double requested = cents / 100.0;
        Object response = (credit ? deposit : withdraw).invoke(registration.getProvider(), player, requested);
        boolean ok = (Boolean) success.invoke(response);
        double moved = ((Number) responseAmount.get(response)).doubleValue();
        return validateResponse(cents, ok, moved);
    }

    /**
     * Vault failures are only safely reversible when the provider reports that zero money moved.
     * A success with a different amount is also ambiguous and must be reviewed manually.
     */
    static boolean validateResponse(long requestedCents, boolean successful, double reportedAmount) {
        double expected = requestedCents / 100.0;
        if (requestedCents <= 0 || !Double.isFinite(expected) || !Double.isFinite(reportedAmount) || reportedAmount < 0)
            throw new IllegalStateException("Respons economy tidak valid.");
        double tolerance = Math.max(1.0e-7, Math.ulp(expected) * 4.0);
        if (!successful) {
            if (Math.abs(reportedAmount) > tolerance)
                throw new IllegalStateException("Provider economy menolak transaksi tetapi melaporkan dana berubah; outcome ambigu.");
            return false;
        }
        if (Math.abs(reportedAmount - expected) > tolerance)
            throw new IllegalStateException("Provider economy melaporkan nominal berbeda dari permintaan; outcome ambigu.");
        return true;
    }
}
