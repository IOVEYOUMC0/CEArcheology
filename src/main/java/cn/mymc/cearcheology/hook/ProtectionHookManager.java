package cn.mymc.cearcheology.hook;

import cn.mymc.cearcheology.hook.protection.AbstractProtectionHook;
import cn.mymc.cearcheology.hook.protection.ProtectionGriefPreventionHook;
import cn.mymc.cearcheology.hook.protection.ProtectionLandsHook;
import cn.mymc.cearcheology.hook.protection.ProtectionPlotSquaredHook;
import cn.mymc.cearcheology.hook.protection.ProtectionResidenceHook;
import cn.mymc.cearcheology.hook.protection.ProtectionTownyHook;
import cn.mymc.cearcheology.hook.protection.ProtectionWorldGuardHook;
import cn.mymc.cearcheology.locale.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// Aggregates every installed region-protection plugin: any hook that denies denies the action,
// and the bypass permission short-circuits all of them.
public class ProtectionHookManager {

    public static final String BYPASS_PERMISSION = "cearcheology.bypass.protection";

    private final List<AbstractProtectionHook> hooks = new ArrayList<>();

    public ProtectionHookManager(Plugin plugin) {
        register(plugin, "WorldGuard", ProtectionWorldGuardHook::new);
        register(plugin, "GriefPrevention", ProtectionGriefPreventionHook::new);
        register(plugin, "Towny", ProtectionTownyHook::new);
        register(plugin, "Lands", () -> new ProtectionLandsHook(plugin));
        register(plugin, "PlotSquared", ProtectionPlotSquaredHook::new);
        register(plugin, "Residence", ProtectionResidenceHook::new);
    }

    private void register(Plugin plugin, String pluginName, Supplier<AbstractProtectionHook> factory) {
        // getPlugin returns a handle for a plugin that loaded but failed onEnable, or was
        // disabled by an admin; hooking one of those calls into half-initialized managers.
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
            return;
        }
        try {
            hooks.add(factory.get());
            plugin.getLogger().info(LanguageManager.log("log-protection-hook-registered",
                "Hooked into protection plugin: {plugin}", Map.of("plugin", pluginName)));
        } catch (Throwable t) {
            plugin.getLogger().warning(LanguageManager.log("log-protection-hook-failed",
                "Failed to hook protection plugin {plugin}: {reason}",
                Map.of("plugin", pluginName, "reason", String.valueOf(t.getMessage()))));
        }
    }

    public boolean hasHooks() {
        return !hooks.isEmpty();
    }

    // Brushing counts as breaking.
    public boolean canBreak(Player player, Location location) {
        if (hooks.isEmpty() || player == null || location == null) {
            return true;
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }
        for (AbstractProtectionHook hook : hooks) {
            if (!hook.canBreak(player, location)) {
                return false;
            }
        }
        return true;
    }

    public boolean canPlace(Player player, Location location) {
        if (hooks.isEmpty() || player == null || location == null) {
            return true;
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }
        for (AbstractProtectionHook hook : hooks) {
            if (!hook.canPlace(player, location)) {
                return false;
            }
        }
        return true;
    }
}
