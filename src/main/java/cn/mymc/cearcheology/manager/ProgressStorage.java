package cn.mymc.cearcheology.manager;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.behavior.BrushableBlockBehavior;
import cn.mymc.cearcheology.behavior.BrushableBlockEntityController;
import cn.mymc.cearcheology.locale.LanguageManager;
import cn.mymc.cearcheology.util.LocationUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Brush progress is memory-only; the CraftEngine block state is the authoritative copy, so entries
// here are pruned on clearLocation or once their chunk/world is gone.
// Preset loot lives in the block entity (BrushableBlockEntityController) rather than storage.yml so
// it persists with the chunk; every preset method delegates there and must run on the main thread.
public class ProgressStorage {

    private final CEArcheology plugin;
    private final Map<String, BlockProgress> progressMap = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();

    public ProgressStorage(CEArcheology plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    // No-op: presets already persist with the block entity. Kept for existing onDisable/reload callers.
    public void saveAll() {
    }

    private void startCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            Set<String> validWorlds = new HashSet<>();
            for (World world : Bukkit.getWorlds()) {
                validWorlds.add(world.getName());
            }
            cleanupWithValidWorlds(validWorlds);
        }, 20L * 60L * 10L, 20L * 60L * 10L);
    }

    private void cleanupWithValidWorlds(Set<String> validWorlds) {
        synchronized (saveLock) {
            progressMap.keySet().removeIf(key -> shouldPrune(key, validWorlds));
        }
    }

    private boolean shouldPrune(String key, Set<String> validWorlds) {
        if (shouldPruneWorld(LocationUtils.getWorldName(key), validWorlds)) {
            return true;
        }

        // An unloaded chunk means the brushing was abandoned; memory-only progress would otherwise
        // accumulate until shutdown.
        Location loc = LocationUtils.fromKey(key);
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private boolean shouldPruneWorld(String worldName, Set<String> validWorlds) {
        if (worldName == null || worldName.isBlank()) {
            return true;
        }
        if (validWorlds.contains(worldName)) {
            return false;
        }

        File worldFolder = new File(plugin.getServer().getWorldContainer(), worldName);
        return !worldFolder.exists();
    }

    public int getProgress(Location loc) {
        String key = LocationUtils.toKey(loc);
        if (key == null) {
            return 0;
        }
        BlockProgress progress = progressMap.get(key);
        return progress != null ? progress.progress() : 0;
    }

    public void setProgress(Location loc, int progress) {
        String key = LocationUtils.toKey(loc);
        if (key == null) {
            return;
        }

        synchronized (saveLock) {
            if (progress <= 0) {
                progressMap.remove(key);
            } else {
                progressMap.put(key, new BlockProgress(progress));
            }
        }
    }

    public ItemStack getPresetLoot(Location loc) {
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        return controller != null ? controller.getLootItem() : null;
    }

    public void setPresetLoot(Location loc, ItemStack item) {
        BrushableBlockEntityController controller =
            BrushableBlockBehavior.controllerAt(loc, item != null);
        if (controller != null) {
            controller.setLootItem(item);
        } else if (item != null) {
            warnPresetDropped(loc);
        }
    }

    public void removePresetLoot(Location loc) {
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        if (controller != null) {
            controller.setLootItem(null);
        }
    }

    public String getPresetLootTable(Location loc) {
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        return controller != null ? controller.getLootTable() : null;
    }

    public void setPresetLootTable(Location loc, String lootTableId) {
        BrushableBlockEntityController controller =
            BrushableBlockBehavior.controllerAt(loc, lootTableId != null);
        if (controller != null) {
            controller.setLootTable(lootTableId);
        } else if (lootTableId != null) {
            warnPresetDropped(loc);
        }
    }

    private void warnPresetDropped(Location loc) {
        plugin.getLogger().warning(LanguageManager.log("log-preset-block-entity-unavailable",
            "Cannot store preset reward: block entity unavailable (chunk not loaded, or not an archeology block): {location}",
            Map.of("location", String.valueOf(loc))));
    }

    public void removePresetLootTable(Location loc) {
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        if (controller != null) {
            controller.setLootTable(null);
        }
    }

    // Vanilla loot already rolled at this location, as real items with their NBT; empty when none.
    public List<ItemStack> getPresetRewardItems(Location loc) {
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        return controller != null ? controller.getRewardItems() : List.of();
    }

    public void setPresetRewardItems(Location loc, List<ItemStack> items) {
        boolean has = items != null && !items.isEmpty();
        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, has);
        if (controller != null) {
            controller.setRewardItems(items);
        } else if (has) {
            warnPresetDropped(loc);
        }
    }

    public void clearLocation(Location loc) {
        String key = LocationUtils.toKey(loc);
        if (key != null) {
            synchronized (saveLock) {
                progressMap.remove(key);
            }
        }

        BrushableBlockEntityController controller = BrushableBlockBehavior.controllerAt(loc, false);
        if (controller != null) {
            controller.setLootItem(null);
            controller.setLootTable(null);
            controller.setRewardItems(null);
        }
    }

    public void cleanupInvalidWorlds() {
        Set<String> validWorlds = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            validWorlds.add(world.getName());
        }
        cleanupWithValidWorlds(validWorlds);
    }

    public record BlockProgress(int progress) {}
}
