package cn.mymc.cearcheology.hook.protection;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.LandWorld;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// Lands 7.x, via its official API.
public class ProtectionLandsHook extends AbstractProtectionHook {

    private final LandsIntegration api;

    public ProtectionLandsHook(Plugin plugin) {
        super("Lands");
        this.api = LandsIntegration.of(plugin);
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        try {
            LandWorld world = api.getWorld(location.getWorld());
            if (world == null) {
                // Lands is not enabled in this world.
                return true;
            }
            return world.hasRoleFlag(api.getLandPlayer(player.getUniqueId()), location,
                    Flags.BLOCK_BREAK, location.getBlock().getType(), false);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        try {
            LandWorld world = api.getWorld(location.getWorld());
            if (world == null) {
                return true;
            }
            return world.hasRoleFlag(api.getLandPlayer(player.getUniqueId()), location,
                    Flags.BLOCK_PLACE, location.getBlock().getType(), false);
        } catch (Throwable t) {
            return true;
        }
    }
}
