package store.cadera.shop;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

final class CitizensBridge {
    private final Method registry, byId, spawned, entity, npcId;
    CitizensBridge(JavaPlugin plugin, BiConsumer<Player, Integer> click) throws Exception {
        ClassLoader loader = Bukkit.getPluginManager().getPlugin("Citizens").getClass().getClassLoader();
        Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI", true, loader);
        Class<?> reg = Class.forName("net.citizensnpcs.api.npc.NPCRegistry", true, loader);
        Class<?> npc = Class.forName("net.citizensnpcs.api.npc.NPC", true, loader);
        registry = api.getMethod("getNPCRegistry"); byId = reg.getMethod("getById", int.class);
        spawned = npc.getMethod("isSpawned"); entity = npc.getMethod("getEntity"); npcId = npc.getMethod("getId");
        Class<? extends Event> event = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent", true, loader).asSubclass(Event.class);
        Method getClicker = event.getMethod("getClicker"), getNpc = event.getMethod("getNPC");
        Bukkit.getPluginManager().registerEvent(event, new Listener() {}, EventPriority.NORMAL, (listener, e) -> {
            if (!event.isInstance(e)) return;
            try { click.accept((Player) getClicker.invoke(e), (Integer) npcId.invoke(getNpc.invoke(e))); }
            catch (Exception ex) { plugin.getLogger().warning("Citizens click: " + ex); }
        }, plugin, true);
    }
    boolean exists(int id) {
        try { return byId.invoke(registry.invoke(null), id) != null; }
        catch (Exception e) { return false; }
    }
    boolean near(Player player, int id, double maxDistance) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return false;
        try {
            Object npc = byId.invoke(registry.invoke(null), id);
            if (npc == null || !(Boolean) spawned.invoke(npc)) return false;
            Entity ent = (Entity) entity.invoke(npc);
            return ent != null && ent.getWorld().equals(player.getWorld())
                && ent.getLocation().distanceSquared(player.getLocation()) <= maxDistance * maxDistance;
        } catch (Exception e) { return false; }
    }
}
