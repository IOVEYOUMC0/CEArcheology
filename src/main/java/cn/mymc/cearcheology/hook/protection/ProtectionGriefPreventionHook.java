package cn.mymc.cearcheology.hook.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

// GriefPrevention, via reflection so there is no compile-time dependency.
// checkPermission returns null when allowed and a denial message otherwise, hence the
// null test. ignoreClaims is GriefPrevention's admin bypass toggle and must be honoured,
// otherwise staff in bypass mode are still blocked here.
public class ProtectionGriefPreventionHook extends AbstractProtectionHook {

    private Object dataStore;
    private Method getPlayerDataMethod;   // DataStore.getPlayerData(UUID) -> PlayerData
    private Field ignoreClaimsField;      // PlayerData.ignoreClaims
    private Method getClaimAtMethod;      // DataStore.getClaimAt(Location, boolean, Claim) -> Claim
    private Method checkPermissionMethod; // Claim.checkPermission(Player, ClaimPermission, Event) -> String
    private Object buildPermission;
    private boolean available;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ProtectionGriefPreventionHook() {
        super("GriefPrevention");
        try {
            Class<?> gp = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object instance = gp.getField("instance").get(null);
            this.dataStore = gp.getField("dataStore").get(instance);

            Class<?> dataStoreClass = this.dataStore.getClass();
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            Class<?> playerDataClass = Class.forName("me.ryanhamshire.GriefPrevention.PlayerData");
            Class<?> claimPermission = Class.forName("me.ryanhamshire.GriefPrevention.ClaimPermission");

            this.getPlayerDataMethod = dataStoreClass.getMethod("getPlayerData", UUID.class);
            this.ignoreClaimsField = playerDataClass.getField("ignoreClaims");
            this.getClaimAtMethod = dataStoreClass.getMethod(
                "getClaimAt", Location.class, boolean.class, claimClass);
            this.checkPermissionMethod = claimClass.getMethod(
                "checkPermission", Player.class, claimPermission, org.bukkit.event.Event.class);
            this.buildPermission = Enum.valueOf((Class<Enum>) claimPermission, "Build");
            this.available = true;
        } catch (Throwable t) {
            this.available = false;
        }
    }

    private boolean canModify(Player player, Location location) {
        if (!available || location == null) {
            return true;
        }
        try {
            Object playerData = getPlayerDataMethod.invoke(dataStore, player.getUniqueId());
            if (playerData != null && ignoreClaimsField.getBoolean(playerData)) {
                return true;
            }
            Object claim = getClaimAtMethod.invoke(dataStore, location, false, null);
            if (claim == null) {
                return true;
            }
            Object denial = checkPermissionMethod.invoke(claim, player, buildPermission, null);
            return denial == null;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        return canModify(player, location);
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        return canModify(player, location);
    }
}
