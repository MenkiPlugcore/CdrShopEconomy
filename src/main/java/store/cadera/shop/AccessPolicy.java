package store.cadera.shop;

public final class AccessPolicy {
    public enum Mode { AUTO, COMMAND, NPC_ONLY }
    public enum Missing { COMMAND, DISABLE }
    public enum Route { COMMAND, NPC, DISABLED }
    private AccessPolicy() {}
    public static Route route(Mode mode, Missing missing, boolean citizens, boolean bound) {
        if (mode == Mode.COMMAND) return Route.COMMAND;
        if (!citizens) return missing == Missing.COMMAND ? Route.COMMAND : Route.DISABLED;
        return mode == Mode.NPC_ONLY || bound ? Route.NPC : Route.COMMAND;
    }
}
