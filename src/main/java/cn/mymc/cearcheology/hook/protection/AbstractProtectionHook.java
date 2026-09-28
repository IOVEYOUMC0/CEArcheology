package cn.mymc.cearcheology.hook.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

// Base for region-protection plugin hooks. Contract: every implementation wraps its calls in
// try/catch and fails OPEN, so a protection-plugin version mismatch never blocks normal play.
public abstract class AbstractProtectionHook {

    protected final String pluginName;

    protected AbstractProtectionHook(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getPluginName() {
        return pluginName;
    }

    // Brushing counts as breaking.
    public abstract boolean canBreak(Player player, Location location);

    public abstract boolean canPlace(Player player, Location location);
}
