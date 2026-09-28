package cn.mymc.cearcheology.manager;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.locale.LanguageManager;
import cn.mymc.cearcheology.util.KeyUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ToolManager {

    private final CEArcheology plugin;
    private final Map<String, ToolConfig> toolConfigs = new HashMap<>();
    private boolean allowVanillaBrush = true;
    private boolean allowAnyCEBrush = false;

    private final NamespacedKey toolIdKey;
    private final NamespacedKey archeologyToolKey;

    // lootTable is optional: when set it overrides the block's own loot-table for this tool,
    // so a better brush can roll a better table rather than only rolling faster.
    public record ToolConfig(String id, String name, double speed, int bonusProgress, int brushLevel,
                             String lootTable) {}

    public ToolManager(CEArcheology plugin) {
        this.plugin = plugin;
        this.toolIdKey = new NamespacedKey(plugin, "tool_id");
        this.archeologyToolKey = new NamespacedKey(plugin, "archeology_tool");
    }

    public void load() {
        toolConfigs.clear();

        allowVanillaBrush = plugin.getConfig().getBoolean("tools.allow-vanilla-brush", true);
        allowAnyCEBrush = plugin.getConfig().getBoolean("tools.allow-any-ce-brush", false);

        File toolsFolder = new File(plugin.getDataFolder(), "tools");
        if (!toolsFolder.exists()) {
            toolsFolder.mkdirs();
        }

        File defaultFile = new File(toolsFolder, "default_brush.yml");
        if (!defaultFile.exists()) {
            plugin.saveResource("tools/default_brush.yml", false);
        }

        File[] files = toolsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            // Example files are documentation and point at a CraftEngine item that does not exist;
            // loading them would make /cearch give tool hand out a renamed vanilla brush.
            if (file.getName().endsWith("_example.yml")) {
                continue;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String toolId = config.getString("id");
            if (toolId == null) {
                continue;
            }

            String name = config.getString("name", "Archeology Tool");
            double speed = config.getDouble("speed", 1.0);
            int bonusProgress = config.getInt("bonus-progress", 0);
            int brushLevel = config.getInt("brush-level", 1);
            String lootTable = config.getString("loot-table");
            if (lootTable != null && lootTable.isBlank()) {
                lootTable = null;
            }

            toolConfigs.put(toolId, new ToolConfig(toolId, name, speed, bonusProgress, brushLevel, lootTable));
            plugin.getLogger().info(LanguageManager.log("log-tool-loaded",
                "Loaded archeology tool {id} (speed={speed}, bonusProgress={bonus}, brushLevel={level})",
                Map.of("id", String.valueOf(toolId), "speed", String.valueOf(speed),
                    "bonus", String.valueOf(bonusProgress), "level", String.valueOf(brushLevel))));
        }

        plugin.getLogger().info(LanguageManager.log("log-tools-loaded",
            "Loaded {count} tool configurations",
            Map.of("count", String.valueOf(toolConfigs.size()))));
        if (allowVanillaBrush) {
            plugin.getLogger().info(LanguageManager.log("log-vanilla-brush-enabled",
                "Vanilla brush support is enabled"));
        }
        if (allowAnyCEBrush) {
            plugin.getLogger().info(LanguageManager.log("log-any-ce-brush-enabled",
                "Tagged CraftEngine custom brushes are enabled"));
        }
    }

    public boolean isArcheologyTool(ItemStack item) {
        if (item == null) {
            return false;
        }

        if (isVanillaBrushWithoutCustomId(item)) {
            // Tagged brushes were handed out by /cearch give tool, so they stay usable even with
            // vanilla brushes disabled; otherwise canCreateTool reports success but gives a dead brush.
            return allowVanillaBrush || hasArcheologyTag(item);
        }

        String toolId = resolveToolId(item);
        if (toolId == null) {
            return false;
        }

        return toolConfigs.containsKey(toolId) || allowAnyCEBrush;
    }

    public ToolConfig getToolConfig(ItemStack item) {
        if (item == null) {
            return null;
        }

        if (isVanillaBrushWithoutCustomId(item) && (allowVanillaBrush || hasArcheologyTag(item))) {
            String vanillaToolId = "minecraft:brush";
            ToolConfig config = toolConfigs.get(vanillaToolId);
            if (config != null) {
                return config;
            }
            return new ToolConfig(vanillaToolId, "Vanilla Brush", 1.0, 0, 1, null);
        }

        String toolId = resolveToolId(item);
        if (toolId == null) {
            return null;
        }

        ToolConfig config = toolConfigs.get(toolId);
        if (config != null) {
            return config;
        }

        if (allowAnyCEBrush) {
            return new ToolConfig(toolId, "CraftEngine Brush", 1.0, 0, 1, null);
        }

        return null;
    }

    public String getToolId(ItemStack item) {
        if (!isArcheologyTool(item)) {
            return null;
        }
        return getResolvedToolId(item);
    }

    public boolean canCreateTool(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return false;
        }

        if ("minecraft:brush".equals(toolId)) {
            return allowVanillaBrush || toolConfigs.containsKey(toolId);
        }

        if (toolConfigs.containsKey(toolId)) {
            return true;
        }

        if (!allowAnyCEBrush) {
            return false;
        }

        Key key = KeyUtils.parseKey(toolId);
        return key != null && CraftEngineItems.byId(key) != null;
    }

    public ItemStack createTool(String toolId) {
        Key key = KeyUtils.parseKey(toolId);
        if (key != null) {
            BukkitItemDefinition customItem = CraftEngineItems.byId(key);
            if (customItem != null && (toolConfigs.containsKey(toolId) || allowAnyCEBrush)) {
                ItemStack item = customItem.buildBukkitItem();
                return applyToolTags(item, toolId);
            }
        }

        if ("minecraft:brush".equals(toolId) && (allowVanillaBrush || toolConfigs.containsKey(toolId))) {
            return applyToolTags(new ItemStack(Material.BRUSH), toolId);
        }

        ToolConfig config = toolConfigs.get(toolId);
        if (config != null) {
            ItemStack brush = new ItemStack(Material.BRUSH);
            ItemMeta meta = brush.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + config.name());
                brush.setItemMeta(meta);
            }
            return applyToolTags(brush, toolId);
        }

        return null;
    }

    private boolean isVanillaBrushWithoutCustomId(ItemStack item) {
        return item.getType() == Material.BRUSH && CraftEngineItems.getCustomItemId(item) == null;
    }

    private String getResolvedToolId(ItemStack item) {
        if (isVanillaBrushWithoutCustomId(item)) {
            return "minecraft:brush";
        }

        return resolveToolId(item);
    }

    // Our PDC tag first, then the CraftEngine item id. Without that second step the ids configured in
    // tools/*.yml never match brushes obtained via /ce give, recipes, shops or kits, since those were
    // never tagged by us, and allow-any-ce-brush would be dead too.
    private String resolveToolId(ItemStack item) {
        String tagged = getTaggedToolId(item);
        if (tagged != null) {
            return tagged;
        }
        return getCraftEngineToolId(item);
    }

    private boolean hasArcheologyTag(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null
            && meta.getPersistentDataContainer().has(archeologyToolKey, PersistentDataType.BYTE);
    }

    private String getCraftEngineToolId(ItemStack item) {
        Key ceKey = CraftEngineItems.getCustomItemId(item);
        if (ceKey == null) {
            return null;
        }
        return ceKey.namespace() + ":" + ceKey.value();
    }

    private String getTaggedToolId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(archeologyToolKey, PersistentDataType.BYTE)) {
            return null;
        }

        String toolId = container.get(toolIdKey, PersistentDataType.STRING);
        if (toolId != null && !toolId.isBlank()) {
            return toolId;
        }

        String ceToolId = getCraftEngineToolId(item);
        if (ceToolId != null) {
            return ceToolId;
        }

        return item.getType() == Material.BRUSH ? "minecraft:brush" : null;
    }

    private ItemStack applyToolTags(ItemStack item, String toolId) {
        if (item == null) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(archeologyToolKey, PersistentDataType.BYTE, (byte) 1);
        if (toolId != null && !toolId.isBlank()) {
            container.set(toolIdKey, PersistentDataType.STRING, toolId);
        }
        item.setItemMeta(meta);
        return item;
    }

    public Map<String, ToolConfig> getToolConfigs() {
        return toolConfigs;
    }

    public int getToolCount() {
        return toolConfigs.size();
    }

    public boolean isAllowVanillaBrush() {
        return allowVanillaBrush;
    }

    public boolean isAllowAnyCEBrush() {
        return allowAnyCEBrush;
    }
}
