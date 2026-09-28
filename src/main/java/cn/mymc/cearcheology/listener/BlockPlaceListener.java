package cn.mymc.cearcheology.listener;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.subcommand.GiveCommand;
import cn.mymc.cearcheology.locale.LanguageManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

public class BlockPlaceListener implements Listener {
    
    private final CEArcheology plugin;
    
    public BlockPlaceListener(CEArcheology plugin) {
        this.plugin = plugin;
    }

    // Runs at LOW so it precedes the MONITOR handler that writes presets.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArcheologyPlaceProtection(BlockPlaceEvent event) {
        var hookManager = plugin.getProtectionHookManager();
        if (hookManager == null || !hookManager.hasHooks()) {
            return; // No protection plugin installed: skip the CE lookup on every block placement.
        }
        ItemStack item = event.getItemInHand();
        if (item == null || !item.hasItemMeta()) return; // Archeology block items always carry meta.

        var block = event.getBlockPlaced();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) return;
        if (!plugin.isArcheologyBlock(state.owner().value().id())) return;

        if (!hookManager.canPlace(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(LanguageManager.translate("protection-place-denied",
                "&cYou cannot place archeology blocks here (area is protected)."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || !item.hasItemMeta()) return;
        
        var block = event.getBlockPlaced();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) return;
        
        Key blockId = state.owner().value().id();
        if (!plugin.isArcheologyBlock(blockId)) return;
        
        ItemStack presetLoot = GiveCommand.getPresetLoot(item);
        String presetLootTable = GiveCommand.getPresetLootTable(item);
        
        Location loc = block.getLocation();
        plugin.getProgressStorage().clearLocation(loc);
        
        if (presetLoot != null) {
            plugin.getProgressStorage().setPresetLoot(loc, presetLoot);
        }
        if (presetLootTable != null) {
            plugin.getProgressStorage().setPresetLootTable(loc, presetLootTable);
        }
    }
}
