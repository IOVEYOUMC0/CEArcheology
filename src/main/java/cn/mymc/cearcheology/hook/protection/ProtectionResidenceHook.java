package cn.mymc.cearcheology.hook.protection;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

// Residence, via reflection so there is no compile-time dependency.
public class ProtectionResidenceHook extends AbstractProtectionHook {

    private Object playerManager;          // Residence.getInstance().getPlayerManager()
    private Method getResidencePlayer;     // PlayerManager.getResidencePlayer(Player) -> ResidencePlayer
    private Method canBreakBlock;          // ResidencePlayer.canBreakBlock(Block, boolean) -> boolean
    private Method canPlaceBlock;          // ResidencePlayer.canPlaceBlock(Block, boolean) -> boolean
    private boolean available;

    public ProtectionResidenceHook() {
        super("Residence");
        try {
            Class<?> residenceClass = Class.forName("com.bekvon.bukkit.residence.Residence");
            Object instance = residenceClass.getMethod("getInstance").invoke(null);
            this.playerManager = instance.getClass().getMethod("getPlayerManager").invoke(instance);
            this.getResidencePlayer = playerManager.getClass().getMethod("getResidencePlayer", Player.class);
            Class<?> residencePlayer = Class.forName("com.bekvon.bukkit.residence.containers.ResidencePlayer");
            this.canBreakBlock = residencePlayer.getMethod("canBreakBlock", Block.class, boolean.class);
            this.canPlaceBlock = residencePlayer.getMethod("canPlaceBlock", Block.class, boolean.class);
            this.available = true;
        } catch (Throwable t) {
            this.available = false;
        }
    }

    private boolean check(Player player, Location location, Method method) {
        if (!available) {
            return true;
        }
        try {
            Object rPlayer = getResidencePlayer.invoke(playerManager, player);
            if (rPlayer == null) {
                return true;
            }
            // The boolean controls whether Residence sends its own denial message. The plugin
            // emits one consolidated message after all protection hooks have been checked.
            Object result = method.invoke(rPlayer, location.getBlock(), false);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        return check(player, location, canBreakBlock);
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        return check(player, location, canPlaceBlock);
    }
}
