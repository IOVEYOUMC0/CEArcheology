package cn.mymc.cearcheology.util;

import cn.mymc.cearcheology.config.ConfigManager;
import org.bukkit.Bukkit;

public final class DebugUtils {

    private static volatile boolean debugModeCached = false;
    private static volatile boolean debugModeChecked = false;
    private static final Object DEBUG_LOCK = new Object();

    private DebugUtils() {
    }

    public static boolean isDebugMode() {
        if (!debugModeChecked) {
            synchronized (DEBUG_LOCK) {
                if (!debugModeChecked) {
                    ConfigManager config = ConfigManager.getInstance();
                    debugModeCached = config != null && config.isDebugMode();
                    debugModeChecked = true;
                }
            }
        }
        return debugModeCached;
    }

    public static void clearCache() {
        synchronized (DEBUG_LOCK) {
            debugModeChecked = false;
            debugModeCached = false;
        }
    }

    public static void debug(String message) {
        if (isDebugMode()) {
            Bukkit.getLogger().info("[CEArcheology] " + message);
        }
    }

    // Lazy variant: skips the message concatenation entirely when debug is off.
    public static void debug(java.util.function.Supplier<String> message) {
        if (isDebugMode()) {
            Bukkit.getLogger().info("[CEArcheology] " + message.get());
        }
    }

    public static void debug(String format, Object... args) {
        if (isDebugMode()) {
            Bukkit.getLogger().info("[CEArcheology] " + String.format(format, args));
        }
    }
}
