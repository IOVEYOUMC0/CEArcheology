package cn.mymc.cearcheology.command.subcommand;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.SubCommand;
import cn.mymc.cearcheology.util.KeyUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PlaceCommand extends SubCommand {

    private final CEArcheology plugin;
    private static final int MAX_MATERIAL_SUGGESTIONS = 50;
    // Material.values() clones the whole array on every call, so cache once at class load
    private static final List<String> ITEM_MATERIAL_NAMES = Arrays.stream(Material.values())
        .filter(material -> material.isItem())
        .map(material -> material.name().toLowerCase())
        .toList();

    public PlaceCommand(CEArcheology plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "place";
    }

    @Override
    public String getDescription() {
        return rawMessage("command-description-place", "Place an archeology block");
    }

    @Override
    public String getPermission() {
        return "cearcheology.command.place";
    }

    @Override
    public String getUsage() {
        return rawMessage("place-usage-detail", "/cearch place <blockId> [x y z|world x y z] [itemId/lootTable]");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(message("place-usage", "&cUsage: {usage}", Map.of("usage", getUsage())));
            return;
        }

        String blockIdStr = args[0];
        Key blockId = KeyUtils.parseKey(blockIdStr);
        if (blockId == null || !plugin.isArcheologyBlock(blockId)) {
            sender.sendMessage(message("place-invalid-block", "&cUnknown archeology block: {id}",
                Map.of("id", blockIdStr)));
            return;
        }

        Location loc;
        String presetItem = null;
        String presetLootTable = null;

        int presetIndex = -1;
        if (args.length >= 5) {
            World explicitWorld = Bukkit.getWorld(args[1]);
            if (explicitWorld != null) {
                loc = parseCoordinates(sender, explicitWorld, args[2], args[3], args[4]);
                if (loc == null) {
                    return;
                }
                presetIndex = 5;
            } else if (sender instanceof Player player) {
                loc = parseCoordinates(sender, player.getWorld(), args[1], args[2], args[3]);
                if (loc == null) {
                    return;
                }
                presetIndex = 4;
            } else {
                sender.sendMessage(message("place-invalid-world", "&cUnknown world: {world}",
                    Map.of("world", args[1])));
                return;
            }
        } else if (args.length >= 4) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("place-console-requires-world", "&cThe console must use /cearch place <blockId> <world> <x> <y> <z> [preset]."));
                return;
            }
            loc = parseCoordinates(sender, player.getWorld(), args[1], args[2], args[3]);
            if (loc == null) {
                return;
            }
            presetIndex = 4;
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("place-console-requires-coordinates", "&cThe console must specify coordinates."));
                return;
            }
            loc = player.getLocation().clone().subtract(0, 1, 0);

            if (args.length >= 2) {
                ParsedPreset preset = requirePreset(sender, args[1]);
                if (preset == null) {
                    return;
                }
                presetItem = preset.itemMaterial();
                presetLootTable = preset.lootTableId();
            }
        }

        if (presetIndex >= 0 && args.length > presetIndex) {
            ParsedPreset preset = requirePreset(sender, args[presetIndex]);
            if (preset == null) {
                return;
            }
            presetItem = preset.itemMaterial();
            presetLootTable = preset.lootTableId();
        }

        boolean success = CraftEngineBlocks.place(loc, blockId, true);
        if (!success) {
            sender.sendMessage(message("place-failed", "&cPlacement failed."));
            return;
        }

        plugin.getProgressStorage().clearLocation(loc);
        if (presetItem != null) {
            Material material = Material.matchMaterial(presetItem);
            if (material != null) {
                plugin.getProgressStorage().setPresetLoot(loc, new ItemStack(material));
                sender.sendMessage(message("place-success-item", "&aPlaced {block} at {location} (item: {preset})",
                    Map.of("location", formatLocation(loc), "block", blockIdStr, "preset", presetItem)));
            } else {
                sender.sendMessage(message("place-invalid-material-after-parse", "&ePlaced {block} at {location} (warning: invalid item {preset})",
                    Map.of("location", formatLocation(loc), "block", blockIdStr, "preset", presetItem)));
            }
        } else if (presetLootTable != null) {
            plugin.getProgressStorage().setPresetLootTable(loc, presetLootTable);
            sender.sendMessage(message("place-success-table", "&aPlaced {block} at {location} (loot table: {preset})",
                Map.of("location", formatLocation(loc), "block", blockIdStr, "preset", presetLootTable)));
        } else {
            sender.sendMessage(message("place-success", "&aPlaced {block} at {location}",
                Map.of("location", formatLocation(loc), "block", blockIdStr)));
        }
    }

    private ParsedPreset requirePreset(CommandSender sender, String rawValue) {
        ParsedPreset preset = parsePreset(rawValue);
        if (preset == null) {
            sender.sendMessage(message("place-invalid-preset", "&cUnknown item or loot table: {id}",
                Map.of("id", rawValue)));
        }
        return preset;
    }

    private String formatLocation(Location loc) {
        return String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
    }

    private String message(String path, String fallback) {
        return cn.mymc.cearcheology.locale.LanguageManager.translate(path, fallback);
    }

    private String message(String path, String fallback, Map<String, String> placeholders) {
        return cn.mymc.cearcheology.locale.LanguageManager.translate(path, fallback, placeholders);
    }

    private String rawMessage(String path, String fallback) {
        return cn.mymc.cearcheology.locale.LanguageManager.translateRaw(path, fallback);
    }

    private Location parseCoordinates(CommandSender sender, World world, String xArg, String yArg, String zArg) {
        try {
            double x = Double.parseDouble(xArg);
            double y = Double.parseDouble(yArg);
            double z = Double.parseDouble(zArg);

            if (y < world.getMinHeight() || y > world.getMaxHeight()) {
                sender.sendMessage(message("place-y-out-of-range", "&cY coordinate is outside the world height limit ({min} - {max})",
                    Map.of("min", String.valueOf(world.getMinHeight()), "max", String.valueOf(world.getMaxHeight()))));
                return null;
            }
            if (Math.abs(x) > 30000000 || Math.abs(z) > 30000000) {
                sender.sendMessage(message("place-out-of-border", "&cCoordinates are outside the world border (max 30000000)"));
                return null;
            }

            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            sender.sendMessage(message("place-invalid-coordinates", "&cInvalid coordinate format."));
            return null;
        }
    }

    private ParsedPreset parsePreset(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        if (plugin.getLootTableManager().getLootTableIds().contains(value)) {
            return new ParsedPreset(null, value);
        }

        Material material = Material.matchMaterial(value);
        if (material == null && value.toLowerCase().startsWith("minecraft:")) {
            material = Material.matchMaterial(value.substring("minecraft:".length()));
        }

        if (material != null && material.isItem()) {
            return new ParsedPreset(material.name(), null);
        }

        return null;
    }

    private record ParsedPreset(String itemMaterial, String lootTableId) {}

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Key blockId : CraftEngineBlocks.loadedBlocks().keySet()) {
                if (plugin.isArcheologyBlock(blockId)) {
                    String idStr = blockId.toString();
                    if (idStr.toLowerCase().startsWith(prefix)) {
                        completions.add(idStr);
                    }
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            addWorldSuggestions(completions, prefix);
            addCoordinateSuggestion(sender, completions, prefix, CoordinateAxis.X);
            addPresetSuggestions(completions, prefix);
        } else if (args.length == 3) {
            addCoordinateSuggestion(
                sender,
                completions,
                args[2].toLowerCase(),
                hasExplicitWorld(args[1]) ? CoordinateAxis.X : CoordinateAxis.Y
            );
        } else if (args.length == 4) {
            addCoordinateSuggestion(
                sender,
                completions,
                args[3].toLowerCase(),
                hasExplicitWorld(args[1]) ? CoordinateAxis.Y : CoordinateAxis.Z
            );
        } else if (args.length == 5) {
            String prefix = args[4].toLowerCase();
            if (hasExplicitWorld(args[1])) {
                addCoordinateSuggestion(sender, completions, prefix, CoordinateAxis.Z);
            } else {
                addPresetSuggestions(completions, prefix);
            }
        } else if (args.length == 6 && hasExplicitWorld(args[1])) {
            addPresetSuggestions(completions, args[5].toLowerCase());
        }

        return completions;
    }

    private boolean hasExplicitWorld(String value) {
        return value != null && Bukkit.getWorld(value) != null;
    }

    private void addWorldSuggestions(List<String> completions, String prefix) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().toLowerCase().startsWith(prefix)) {
                completions.add(world.getName());
            }
        }
    }

    private void addPresetSuggestions(List<String> completions, String prefix) {
        for (String tableId : plugin.getLootTableManager().getLootTableIds()) {
            if (tableId.toLowerCase().startsWith(prefix)) {
                completions.add(tableId);
            }
        }

        // Over a thousand materials: suggest none on an empty prefix rather than resend the whole list per keystroke
        if (prefix.isEmpty()) {
            return;
        }

        int added = 0;
        for (String name : ITEM_MATERIAL_NAMES) {
            if (name.startsWith(prefix)) {
                completions.add(name);
                if (++added >= MAX_MATERIAL_SUGGESTIONS) {
                    return;
                }
            }
        }
    }

    private void addCoordinateSuggestion(CommandSender sender, List<String> completions, String prefix, CoordinateAxis axis) {
        if (!(sender instanceof Player player)) {
            return;
        }

        Location loc = player.getLocation();
        String suggestion = switch (axis) {
            case X -> String.valueOf((int) loc.getX());
            case Y -> String.valueOf((int) loc.getY());
            case Z -> String.valueOf((int) loc.getZ());
        };

        if (suggestion.startsWith(prefix)) {
            completions.add(suggestion);
        }
    }

    private enum CoordinateAxis {
        X, Y, Z
    }
}
