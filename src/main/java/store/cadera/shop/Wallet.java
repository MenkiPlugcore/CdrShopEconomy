package store.cadera.shop;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Uses the public Vault API reflectively, without bundling a second copy of it. */
final class Wallet {
    private final Class<?> api;
    private final Method deposit, withdraw, success;
    Wallet() throws ReflectiveOperationException {
        ClassLoader loader = Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader();
        api = Class.forName("net.milkbowl.vault.economy.Economy", true, loader);
        deposit = api.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        withdraw = api.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        success = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader).getMethod("transactionSuccess");
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
        Object response = (credit ? deposit : withdraw).invoke(registration.getProvider(), player, cents / 100.0);
        return (Boolean) success.invoke(response);
    }
}
