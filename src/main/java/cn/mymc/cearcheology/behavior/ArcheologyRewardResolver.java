package cn.mymc.cearcheology.behavior;

import cn.mymc.cearcheology.config.ConfigManager;
import cn.mymc.cearcheology.locale.LanguageManager;
import cn.mymc.cearcheology.manager.LootTableManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

final class ArcheologyRewardResolver {

    private static final java.util.Random RANDOM = new java.util.Random();

    private final Player player;
    private final World world;
    private final Location blockCenter;
    private final Block block;
    private final BlockFace brushFace;

    ArcheologyRewardResolver(Player player, World world, Location blockCenter, Block block, BlockFace brushFace) {
        this.player = player;
        this.world = world;
        this.blockCenter = blockCenter;
        this.block = block;
        this.brushFace = brushFace;
    }

    ItemStack createDisplayItem(List<LootTableManager.LootEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        for (LootTableManager.LootEntry entry : entries) {
            ItemStack item = entry.createItemStack();
            if (item != null && item.getType() != Material.AIR) {
                return LootTableManager.applyLootBundle(item, entries);
            }
        }
        return null;
    }

    void resolveRewards(List<LootTableManager.LootEntry> entries, ItemStack fallbackItem) {
        if (entries != null && !entries.isEmpty()) {
            for (LootTableManager.LootEntry entry : entries) {
                resolveReward(entry);
            }
            return;
        }

        if (fallbackItem != null && fallbackItem.getType() != Material.AIR) {
            dropItem(fallbackItem);
        }
    }

    void dropItems(List<ItemStack> items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                dropItem(item.clone());
            }
        }
    }

    void damageBrush(ItemStack brushItem, EquipmentSlot brushHand) {
        if (brushItem == null || brushItem.getType() == Material.AIR) {
            return;
        }
        if (!(brushItem.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) {
            return;
        }

        if (damageable.isUnbreakable()) {
            return;
        }

        // Prefer the minecraft:max_damage component: CraftEngine items often override it, while
        // Material#getMaxDurability only knows the base material and would delete a 500-durability
        // custom brush after 64 uses.
        int maxDurability = damageable.hasMaxDamage()
            ? damageable.getMaxDamage()
            : brushItem.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return;
        }

        // Unbreaking level n skips the durability cost with probability n/(n+1), as vanilla tools do.
        int unbreaking = brushItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
        if (unbreaking > 0 && RANDOM.nextInt(unbreaking + 1) != 0) {
            return;
        }

        // Let other plugins (protection, durability displays, anti-cheat) veto this damage.
        org.bukkit.event.player.PlayerItemDamageEvent damageEvent =
            new org.bukkit.event.player.PlayerItemDamageEvent(player, brushItem, 1, 1);
        Bukkit.getPluginManager().callEvent(damageEvent);
        if (damageEvent.isCancelled()) {
            return;
        }

        int newDamage = damageable.getDamage() + Math.max(0, damageEvent.getDamage());
        if (newDamage >= maxDurability) {
            Bukkit.getPluginManager().callEvent(
                new org.bukkit.event.player.PlayerItemBreakEvent(player, brushItem));
            if (brushHand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            world.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            return;
        }

        damageable.setDamage(newDamage);
        brushItem.setItemMeta(damageable);
        if (brushHand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(brushItem);
        } else {
            player.getInventory().setItemInMainHand(brushItem);
        }
    }

    private void resolveReward(LootTableManager.LootEntry entry) {
        if (entry == null) {
            return;
        }

        String type = entry.type();
        if ("entity".equals(type)) {
            spawnEntity(entry);
            return;
        }
        if ("command".equals(type)) {
            executeCommand(entry);
            return;
        }

        ItemStack item = entry.createRewardItemStack();
        if (item != null && item.getType() != Material.AIR) {
            dropItem(item);
        }
    }

    private void dropItem(ItemStack item) {
        org.bukkit.util.Vector offset = faceOffset(brushFace).multiply(0.7);
        Location dropLoc = blockCenter.clone().add(offset);
        world.dropItemNaturally(dropLoc, item);
    }

    private void spawnEntity(LootTableManager.LootEntry entry) {
        if (entry.entity() == null) {
            return;
        }

        try {
            org.bukkit.entity.EntityType entityType = resolveEntityType(entry.entity());
            if (entityType == null) {
                throw new IllegalArgumentException(entry.entity());
            }
            Location spawnLoc = blockCenter.clone().add(0, 0.5, 0);
            world.spawnEntity(spawnLoc, entityType);
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[CEArcheology] " + LanguageManager.log("log-reward-invalid-entity",
                "Invalid entity type: {type}", Map.of("type", String.valueOf(entry.entity()))));
        }
    }

    // Registry first so a config can write the natural "minecraft:zombie" form; valueOf only
    // accepts the bare constant name. Locale.ROOT matters because a tr_TR default locale maps
    // 'i' to a dotted capital and breaks the constant lookup.
    private static org.bukkit.entity.EntityType resolveEntityType(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            org.bukkit.NamespacedKey key = id.indexOf(':') >= 0
                ? org.bukkit.NamespacedKey.fromString(id.toLowerCase(java.util.Locale.ROOT))
                : org.bukkit.NamespacedKey.minecraft(id.toLowerCase(java.util.Locale.ROOT));
            if (key != null) {
                org.bukkit.entity.EntityType fromRegistry = org.bukkit.Registry.ENTITY_TYPE.get(key);
                if (fromRegistry != null) {
                    return fromRegistry;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the legacy constant-name form
        }
        try {
            return org.bukkit.entity.EntityType.valueOf(id.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void executeCommand(LootTableManager.LootEntry entry) {
        if (entry.command() == null) {
            return;
        }

        String playerName = player.getName();
        if (playerName == null || playerName.length() > 16 || !playerName.matches("^[a-zA-Z0-9_]+$")) {
            Bukkit.getLogger().warning("[CEArcheology] " + LanguageManager.log("log-reward-invalid-player-name",
                "Player name validation failed, command refused: {player}",
                Map.of("player", String.valueOf(playerName))));
            return;
        }

        String worldName = world.getName();
        if (worldName == null || !worldName.matches("^[a-zA-Z0-9_\\-]+$")) {
            Bukkit.getLogger().warning("[CEArcheology] " + LanguageManager.log("log-reward-invalid-world-name",
                "World name validation failed, command refused: {world}",
                Map.of("world", String.valueOf(worldName))));
            return;
        }

        String command = entry.command()
            .replace("{player}", playerName)
            .replace("{x}", String.valueOf(block.getX()))
            .replace("{y}", String.valueOf(block.getY()))
            .replace("{z}", String.valueOf(block.getZ()))
            .replace("{world}", worldName);

        ConfigManager config = ConfigManager.getInstance();
        if (config != null && !config.isCommandAllowed(entry.command(), command)) {
            Bukkit.getLogger().warning("[CEArcheology] " + LanguageManager.log("log-reward-command-not-allowed",
                "Command is not whitelisted, execution blocked: {command}", Map.of("command", command)));
            return;
        }

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[CEArcheology] " + LanguageManager.log("log-reward-command-failed",
                "Failed to execute command: {command} - {error}",
                Map.of("command", command, "error", String.valueOf(e.getMessage()))));
        }
    }

    private org.bukkit.util.Vector faceOffset(BlockFace face) {
        return switch (face) {
            case DOWN -> new org.bukkit.util.Vector(0, -1, 0);
            case NORTH -> new org.bukkit.util.Vector(0, 0, -1);
            case SOUTH -> new org.bukkit.util.Vector(0, 0, 1);
            case WEST -> new org.bukkit.util.Vector(-1, 0, 0);
            case EAST -> new org.bukkit.util.Vector(1, 0, 0);
            default -> new org.bukkit.util.Vector(0, 1, 0);
        };
    }
}
