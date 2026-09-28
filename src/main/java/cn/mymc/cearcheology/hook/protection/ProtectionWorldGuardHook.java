package cn.mymc.cearcheology.hook.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.Location;
import org.bukkit.entity.Player;

// WorldGuard 7.x, via its official API.
// The bypass check uses the TARGET location's world, not the player's: testBuild is evaluated
// at location, so keying bypass off the player's own world disagrees with it for any
// cross-world edit. Same family as the PlotSquared hook's player-position bug.
public class ProtectionWorldGuardHook extends AbstractProtectionHook {

    public ProtectionWorldGuardHook() {
        super("WorldGuard");
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .testBuild(BukkitAdapter.adapt(location), localPlayer, Flags.BLOCK_BREAK)
                || WorldGuard.getInstance().getPlatform().getSessionManager()
                    .hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()));
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .testBuild(BukkitAdapter.adapt(location), localPlayer, Flags.BLOCK_PLACE)
                || WorldGuard.getInstance().getPlatform().getSessionManager()
                    .hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()));
        } catch (Throwable t) {
            return true;
        }
    }
}
