package cn.mymc.cearcheology.command.subcommand;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.SubCommand;
import cn.mymc.cearcheology.util.KeyUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class GiveCommand extends SubCommand {

    private final CEArcheology plugin;
    private static final NamespacedKey PRESET_LOOT_KEY = new NamespacedKey("cearcheology", "preset_loot");
    private static final NamespacedKey PRESET_LOOT_TABLE_KEY = new NamespacedKey("cearcheology", "preset_loot_table");
    private static final int MAX_SERIALIZED_SIZE = 65536;
    private static final int MAX_GIVE_AMOUNT = 64;
    private static final int MAX_MATERIAL_SUGGESTIONS = 50;
    // Material.values() clones the whole array on every call, so cache once at class load
    private static final List<String> ITEM_MATERIAL_NAMES = Arrays.stream(Material.values())
        .filter(material -> material.isItem())
        .map(material -> material.name().toLowerCase())
        .toList();

    public GiveCommand(CEArcheology plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "give";
    }

    @Override
    public String getDescription() {
        return rawMessage("command-description-give", "Give a player an archeology block or tool");
    }

    @Override
    public String getPermission() {
        return "cearcheology.command.give";
    }

    @Override
    public String getUsage() {
        return rawMessage("give-usage-detail", """
            §e/cea give block <blockId> [player] [amount]
            §e/cea give block <blockId> craftengine <ceItemId> [player] [amount]
            §e/cea give block <blockId> table <lootTableId> [player] [amount]
            §e/cea give block <blockId> item <vanillaItemId> [player] [amount]
            §e/cea give tool <toolId> [player] [amount]""");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(getPermission())) {
            sender.sendMessage(message("no-permission", "&cYou do not have permission to run this command."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(message("give-usage", "&cUsage: {usage}", Map.of("usage", getUsage())));
            return;
        }

        String type = args[0].toLowerCase();
        String id = args[1];

        if ("tool".equals(type)) {
            int argIndex = 2;
            ParsedTarget parsedTarget = parsePlayer(sender, args, argIndex);
            if (parsedTarget == null) {
                return;
            }
            Player target = parsedTarget.player();
            argIndex = parsedTarget.nextIndex();

            int amount = 1;
            if (args.length > argIndex) {
                Integer parsedAmount = parseAmount(sender, args[argIndex]);
                if (parsedAmount == null) {
                    return;
                }
                amount = parsedAmount;
            }

            giveTool(sender, target, id, amount);
            return;
        }

        if ("block".equals(type)) {
            int argIndex = 2;
            String presetType = null;
            String presetValue = null;

            if (args.length > argIndex) {
                String arg2 = args[argIndex].toLowerCase();
                if ("craftengine".equals(arg2) || "table".equals(arg2) || "item".equals(arg2)) {
                    presetType = arg2;
                    argIndex++;
                    if (args.length > argIndex) {
                        presetValue = args[argIndex];
                        argIndex++;
                    } else {
                        sender.sendMessage(message("give-missing-preset", "&cMissing preset value."));
                        return;
                    }
                }
            }

            ParsedTarget parsedTarget = parsePlayer(sender, args, argIndex);
            if (parsedTarget == null) {
                return;
            }
            Player target = parsedTarget.player();
            argIndex = parsedTarget.nextIndex();

            int amount = 1;
            if (args.length > argIndex) {
                Integer parsedAmount = parseAmount(sender, args[argIndex]);
                if (parsedAmount == null) {
                    return;
                }
                amount = parsedAmount;
            }

            giveBlock(sender, target, id, amount, presetType, presetValue);
            return;
        }

        sender.sendMessage(message("give-unknown-type", "&cUnknown type: {type} (available: block, tool)",
            Map.of("type", type)));
    }

    private ParsedTarget parsePlayer(CommandSender sender, String[] args, int startIndex) {
        for (int i = startIndex; i < args.length; i++) {
            // Digit-only tokens are always the amount: Bukkit.getPlayer prefix-matches, so with a
            // player named 1nferno online "/cea give tool <id> 1" would hand the item to them.
            if (isNumeric(args[i])) {
                continue;
            }
            Player player = Bukkit.getPlayer(args[i]);
            if (player != null) {
                // Only advance when the name sits at the current slot, else the amount arg gets skipped too
                return new ParsedTarget(player, i == startIndex ? startIndex + 1 : startIndex);
            }
        }

        if (sender instanceof Player player) {
            return new ParsedTarget(player, startIndex);
        }

        sender.sendMessage(message("give-console-requires-player", "&cThe console must specify a player."));
        return null;
    }

    private record ParsedTarget(Player player, int nextIndex) {}

    private static boolean isNumeric(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void giveBlock(CommandSender sender, Player target, String blockId, int amount, String presetType, String presetValue) {
        Key key = KeyUtils.parseKey(blockId);
        if (key == null || !plugin.isArcheologyBlock(key)) {
            sender.sendMessage(message("give-invalid-block", "&cUnknown archeology block: {id}",
                Map.of("id", blockId)));
            return;
        }

        BukkitItemDefinition customItem = CraftEngineItems.byId(key);
        if (customItem == null) {
            sender.sendMessage(message("give-block-item-missing", "&cCannot resolve block item: {id}",
                Map.of("id", blockId)));
            return;
        }

        ItemStack item = customItem.buildBukkitItem();
        item.setAmount(amount);

        if (presetType != null && presetValue != null) {
            ItemStack preset = null;

            switch (presetType) {
                case "craftengine" -> {
                    Key ceKey = KeyUtils.parseKey(presetValue);
                    if (ceKey == null) {
                        sender.sendMessage(message("give-invalid-craftengine-item", "&cInvalid CraftEngine item ID: {id}",
                            Map.of("id", presetValue)));
                        return;
                    }

                    BukkitItemDefinition ceItem = CraftEngineItems.byId(ceKey);
                    if (ceItem == null) {
                        sender.sendMessage(message("give-invalid-craftengine-item", "&cInvalid CraftEngine item ID: {id}",
                            Map.of("id", presetValue)));
                        return;
                    }
                    preset = ceItem.buildBukkitItem();
                }
                case "table" -> {
                    if (!plugin.getLootTableManager().hasLootTable(presetValue)) {
                        sender.sendMessage(message("give-invalid-loot-table", "&cUnknown loot table: {id}",
                            Map.of("id", presetValue)));
                        return;
                    }
                    setPresetLootTable(item, presetValue);
                    giveOrDrop(target, item);
                    sender.sendMessage(message("give-block-success-table", "&aGave {player} {amount}x {block} (loot table: {preset})",
                        Map.of("player", target.getName(), "amount", String.valueOf(amount),
                            "block", blockId, "preset", presetValue)));
                    return;
                }
                case "item" -> {
                    Material material = parseVanillaMaterial(presetValue);
                    if (material == null) {
                        sender.sendMessage(message("give-invalid-item", "&cUnknown vanilla item: {id}",
                            Map.of("id", presetValue)));
                        return;
                    }
                    preset = new ItemStack(material);
                }
                default -> {
                    return;
                }
            }

            if (preset != null) {
                setPresetLoot(item, preset);
            }
        }

        giveOrDrop(target, item);
        String presetInfo = presetType != null ? " (" + presetType + ":" + presetValue + ")" : "";
        sender.sendMessage(message("give-block-success", "&aGave {player} {amount}x {block}{preset_info}",
            Map.of("player", target.getName(), "amount", String.valueOf(amount),
                "block", blockId, "preset_info", presetInfo)));
    }

    private void giveTool(CommandSender sender, Player target, String toolId, int amount) {
        if (!plugin.getToolManager().canCreateTool(toolId)) {
            sender.sendMessage(message("give-invalid-tool", "&cUnknown archeology tool: {id}",
                Map.of("id", toolId)));
            return;
        }

        for (int i = 0; i < amount; i++) {
            ItemStack tool = plugin.getToolManager().createTool(toolId);
            if (tool == null) {
                sender.sendMessage(message("give-invalid-tool", "&cUnknown archeology tool: {id}",
                    Map.of("id", toolId)));
                return;
            }
            giveOrDrop(target, tool);
        }

        sender.sendMessage(message("give-tool-success", "&aGave {player} {amount}x {tool}",
            Map.of("player", target.getName(), "amount", String.valueOf(amount), "tool", toolId)));
    }

    private void giveOrDrop(Player target, ItemStack item) {
        // Drop the overflow at the player's feet so nothing is silently voided
        for (ItemStack leftover : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
    }

    private Material parseVanillaMaterial(String value) {
        Material material = Material.matchMaterial(value);
        if (material == null && value != null && value.toLowerCase().startsWith("minecraft:")) {
            material = Material.matchMaterial(value.substring("minecraft:".length()));
        }
        return material != null && material.isItem() ? material : null;
    }

    private Integer parseAmount(CommandSender sender, String rawValue) {
        int amount;
        try {
            amount = Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            sender.sendMessage(message("give-invalid-amount", "&cAmount must be an integer between 1 and {max}.",
                Map.of("max", String.valueOf(MAX_GIVE_AMOUNT))));
            return null;
        }

        if (amount < 1 || amount > MAX_GIVE_AMOUNT) {
            sender.sendMessage(message("give-invalid-amount", "&cAmount must be an integer between 1 and {max}.",
                Map.of("max", String.valueOf(MAX_GIVE_AMOUNT))));
            return null;
        }

        return amount;
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

    private void setPresetLoot(ItemStack item, ItemStack loot) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                String serialized = serializeItemStack(loot);
                meta.getPersistentDataContainer().set(PRESET_LOOT_KEY, PersistentDataType.STRING, serialized);
                item.setItemMeta(meta);
            } catch (Exception e) {
                plugin.getLogger().warning(cn.mymc.cearcheology.locale.LanguageManager.log(
                    "log-give-preset-loot-serialize-failed",
                    "Failed to serialize preset loot: {error}",
                    Map.of("error", String.valueOf(e.getMessage()))));
            }
        }
    }

    private void setPresetLootTable(ItemStack item, String lootTable) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(PRESET_LOOT_TABLE_KEY, PersistentDataType.STRING, lootTable);
            item.setItemMeta(meta);
        }
    }

    public static ItemStack getPresetLoot(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        String serialized = meta.getPersistentDataContainer().get(PRESET_LOOT_KEY, PersistentDataType.STRING);
        if (serialized == null) {
            return null;
        }

        try {
            return deserializeItemStack(serialized);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getPresetLootTable(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(PRESET_LOOT_TABLE_KEY, PersistentDataType.STRING);
    }

    private static String serializeItemStack(ItemStack item) throws IOException {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }

        Map<String, Object> serialized = item.serialize();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        for (Map.Entry<String, Object> entry : serialized.entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        outputStream.write(config.saveToString().getBytes(StandardCharsets.UTF_8));
        String result = Base64.getEncoder().encodeToString(outputStream.toByteArray());

        if (result.length() > MAX_SERIALIZED_SIZE) {
            throw new IOException("Item data too large to serialize (size: " + result.length() + " bytes)");
        }

        return result;
    }

    private static ItemStack deserializeItemStack(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return null;
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(serialized);
            String yaml = new String(bytes, StandardCharsets.UTF_8);
            org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
            config.loadFromString(yaml);
            return ItemStack.deserialize(config.getValues(false));
        } catch (Exception e) {
            return null;
        }
    }

    private void addMaterialSuggestions(List<String> completions, String prefix) {
        // Over a thousand materials, so truncate rather than resend the whole list per keystroke;
        // returning nothing on an empty prefix would leave this arg slot with no suggestions at all.
        int added = 0;
        for (String name : ITEM_MATERIAL_NAMES) {
            if (prefix.isEmpty() || name.startsWith(prefix)) {
                completions.add(name);
                if (++added >= MAX_MATERIAL_SUGGESTIONS) {
                    return;
                }
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String type : List.of("block", "tool")) {
                if (type.startsWith(prefix)) {
                    completions.add(type);
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            String type = args[0].toLowerCase();
            if ("block".equals(type)) {
                for (Key blockId : net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.loadedBlocks().keySet()) {
                    if (plugin.isArcheologyBlock(blockId)) {
                        String idStr = blockId.toString();
                        if (idStr.toLowerCase().startsWith(prefix)) {
                            completions.add(idStr);
                        }
                    }
                }
            } else if ("tool".equals(type)) {
                if ("minecraft:brush".startsWith(prefix) && plugin.getToolManager().canCreateTool("minecraft:brush")) {
                    completions.add("minecraft:brush");
                }
                for (String toolId : plugin.getToolManager().getToolConfigs().keySet()) {
                    if (toolId.toLowerCase().startsWith(prefix)) {
                        completions.add(toolId);
                    }
                }
            }
        } else if (args.length == 3) {
            String prefix = args[2].toLowerCase();
            String type = args[0].toLowerCase();
            if ("block".equals(type)) {
                for (String presetType : List.of("craftengine", "table", "item")) {
                    if (presetType.startsWith(prefix)) {
                        completions.add(presetType);
                    }
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
            } else if ("tool".equals(type)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
            }
        } else if (args.length == 4) {
            String prefix = args[3].toLowerCase();
            String type = args[0].toLowerCase();
            String presetType = args[2].toLowerCase();

            if ("block".equals(type)) {
                if ("table".equals(presetType)) {
                    for (String tableId : plugin.getLootTableManager().getLootTableIds()) {
                        if (tableId.toLowerCase().startsWith(prefix)) {
                            completions.add(tableId);
                        }
                    }
                } else if ("item".equals(presetType)) {
                    addMaterialSuggestions(completions, prefix);
                } else {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase().startsWith(prefix)) {
                            completions.add(player.getName());
                        }
                    }
                    completions.add("1");
                    completions.add("64");
                }
            } else if ("tool".equals(type)) {
                completions.add("1");
                completions.add("64");
            }
        } else if (args.length == 5) {
            String prefix = args[4].toLowerCase();
            String type = args[0].toLowerCase();
            String presetType = args[2].toLowerCase();

            if ("block".equals(type) && ("craftengine".equals(presetType) || "table".equals(presetType) || "item".equals(presetType))) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
                completions.add("1");
                completions.add("64");
            }
        } else if (args.length == 6) {
            completions.add("1");
            completions.add("64");
        }

        return completions;
    }
}
