package cn.mymc.cearcheology.hook.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

// Towny, via reflection so there is no compile-time dependency.
// PlayerCacheUtil.getCachePermission is Towny's documented entry point for "may this player
// do X here"; it consults the per-player permission cache rather than re-resolving the town.
public class ProtectionTownyHook extends AbstractProtectionHook {

    private Method getCachePermissionMethod;
    private Object actionBuild;
    private Object actionDestroy;
    private boolean available;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ProtectionTownyHook() {
        super("Towny");
        try {
            Class<?> playerCacheUtil = Class.forName("com.palmergames.bukkit.towny.utils.PlayerCacheUtil");
            Class<?> actionType = Class.forName("com.palmergames.bukkit.towny.object.TownyPermission$ActionType");
            this.getCachePermissionMethod = playerCacheUtil.getMethod(
                "getCachePermission", Player.class, Location.class, Material.class, actionType);
            this.actionBuild = Enum.valueOf((Class<Enum>) actionType, "BUILD");
            this.actionDestroy = Enum.valueOf((Class<Enum>) actionType, "DESTROY");
            this.available = true;
        } catch (Throwable t) {
            this.available = false;
        }
    }

    private boolean check(Player player, Location location, Object action) {
        if (!available || location == null) {
            return true;
        }
        try {
            Object allowed = getCachePermissionMethod.invoke(
                null, player, location, location.getBlock().getType(), action);
            return !(allowed instanceof Boolean) || (Boolean) allowed;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        return check(player, location, actionDestroy);
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        return check(player, location, actionBuild);
    }
}
