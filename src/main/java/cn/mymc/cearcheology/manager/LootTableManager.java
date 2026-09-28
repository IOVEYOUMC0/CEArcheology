package cn.mymc.cearcheology.manager;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.hook.ItemHook;
import cn.mymc.cearcheology.locale.LanguageManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class LootTableManager {

    private final CEArcheology plugin;
    private final Map<String, LootTable> lootTables = new HashMap<>();

    public static final NamespacedKey LOOT_TYPE_KEY = new NamespacedKey("cearcheology", "loot_type");
    public static final NamespacedKey LOOT_ENTITY_KEY = new NamespacedKey("cearcheology", "loot_entity");
    public static final NamespacedKey LOOT_COMMAND_KEY = new NamespacedKey("cearcheology", "loot_command");
    public static final NamespacedKey LOOT_BUNDLE_KEY = new NamespacedKey("cearcheology", "loot_bundle");

    public LootTableManager(CEArcheology plugin) {
        this.plugin = plugin;
    }

    public void load() {
        lootTables.clear();

        File lootFolder = new File(plugin.getDataFolder(), "loot_tables");
        if (!lootFolder.exists()) {
            lootFolder.mkdirs();
        }

        File defaultFile = new File(lootFolder, "default.yml");
        if (!defaultFile.exists()) {
            plugin.saveResource("loot_tables/default.yml", false);
        }

        File[] files = lootFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            loadLootTables(config);
        }

        plugin.getLogger().info(LanguageManager.log("log-loot-tables-loaded",
            "Loaded {count} loot tables.", Map.of("count", String.valueOf(lootTables.size()))));
    }

    private void loadLootTables(YamlConfiguration config) {
        for (String key : config.getKeys(false)) {
            // Block configs reference loot tables by full id, so any valid namespace:path must be accepted.
            if (!isValidTableId(key)) {
                plugin.getLogger().warning(LanguageManager.log("log-loot-table-invalid-id",
                    "Ignoring invalid loot table ID (expected namespace:path): {id}",
                    Map.of("id", String.valueOf(key))));
                continue;
            }
            ConfigurationSection tableSection = config.getConfigurationSection(key);
            if (tableSection != null) {
                lootTables.put(key, parseLootTable(key, tableSection));
            } else {
                plugin.getLogger().warning(LanguageManager.log("log-loot-table-empty-section",
                    "Loot table {id} has no content and was skipped.",
                    Map.of("id", String.valueOf(key))));
            }
        }
    }

    private boolean isValidTableId(String key) {
        if (key == null || key.indexOf(':') <= 0) {
            return false;
        }
        return NamespacedKey.fromString(key.toLowerCase(Locale.ROOT)) != null;
    }

    private LootTable parseLootTable(String id, ConfigurationSection section) {
        List<LootPool> pools = new ArrayList<>();

        ConfigurationSection poolsSection = section.getConfigurationSection("pools");
        if (poolsSection != null) {
            for (String poolKey : poolsSection.getKeys(false)) {
                ConfigurationSection poolSection = poolsSection.getConfigurationSection(poolKey);
                if (poolSection != null) {
                    pools.add(parseLootPool(poolSection));
                }
            }
        }

        if (pools.isEmpty()) {
            List<Map<?, ?>> poolsList = section.getMapList("pools");
            for (Map<?, ?> poolMap : poolsList) {
                pools.add(parseLootPoolFromMap(poolMap));
            }
        }

        validateTable(id, pools);
        return new LootTable(id, pools);
    }

    // Empty tables and unreachable weights only surface at runtime as "brushing yields nothing",
    // so they have to be reported while loading.
    private void validateTable(String id, List<LootPool> pools) {
        if (pools.isEmpty() || pools.stream().allMatch(pool -> pool.entries().isEmpty())) {
            plugin.getLogger().warning(LanguageManager.log("log-loot-table-no-entries",
                "Loot table {id} has no pools or entries and will never produce an item.",
                Map.of("id", String.valueOf(id))));
            return;
        }

        int poolIndex = 0;
        for (LootPool pool : pools) {
            poolIndex++;
            if (pool.entries().isEmpty()) {
                plugin.getLogger().warning(LanguageManager.log("log-loot-pool-no-entries",
                    "Pool {index} of loot table {id} has no entries.",
                    Map.of("id", String.valueOf(id), "index", String.valueOf(poolIndex))));
                continue;
            }
            for (LootEntry entry : pool.entries()) {
                if (entry.weight() <= 0) {
                    String name = entry.itemId() != null ? entry.itemId() : entry.type();
                    plugin.getLogger().warning(LanguageManager.log("log-loot-entry-zero-weight",
                        "Entry {entry} in pool {index} of loot table {id} has weight {weight} (<= 0) and will never be rolled.",
                        Map.of("id", String.valueOf(id), "index", String.valueOf(poolIndex),
                            "entry", String.valueOf(name), "weight", String.valueOf(entry.weight()))));
                }
            }
            if (pool.cachedTotalWeight() <= 0) {
                plugin.getLogger().warning(LanguageManager.log("log-loot-pool-zero-weight",
                    "Pool {index} of loot table {id} has a total weight of 0 and will never produce an item.",
                    Map.of("id", String.valueOf(id), "index", String.valueOf(poolIndex))));
            }
        }
    }

    private LootPool parseLootPool(ConfigurationSection section) {
        int rolls = section.getInt("rolls", 1);
        List<LootEntry> entries = new ArrayList<>();

        ConfigurationSection entriesSection = section.getConfigurationSection("entries");
        if (entriesSection != null) {
            for (String entryKey : entriesSection.getKeys(false)) {
                ConfigurationSection entrySection = entriesSection.getConfigurationSection(entryKey);
                if (entrySection != null) {
                    entries.add(parseLootEntry(entrySection));
                }
            }
        }

        if (entries.isEmpty()) {
            List<Map<?, ?>> entriesList = section.getMapList("entries");
            for (Map<?, ?> entryMap : entriesList) {
                entries.add(parseLootEntryFromMap(entryMap));
            }
        }

        return new LootPool(rolls, entries);
    }

    @SuppressWarnings("unchecked")
    private LootPool parseLootPoolFromMap(Map<?, ?> map) {
        int rolls = 1;
        Object rollsObj = map.get("rolls");
        if (rollsObj instanceof Number num) {
            rolls = num.intValue();
        }

        List<LootEntry> entries = new ArrayList<>();
        Object entriesObj = map.get("entries");
        if (entriesObj instanceof List<?> list) {
            for (Object entryObj : list) {
                if (entryObj instanceof Map<?, ?> entryMap) {
                    entries.add(parseLootEntryFromMap(entryMap));
                }
            }
        }

        return new LootPool(rolls, entries);
    }

    private LootEntry parseLootEntry(ConfigurationSection section) {
        String type = section.getString("type", "item");
        String itemId = section.getString("item", "minecraft:stone");
        double weight = section.getDouble("weight", 1.0D);
        IntRange count = parseCount(section.get("count"), section.get("amount"));
        String entity = section.getString("entity", null);
        String command = section.getString("command", null);
        String displayItemId = parseDisplayItem(section.get("display-item"));

        return new LootEntry(type, itemId, count, weight, entity, command, displayItemId);
    }

    @SuppressWarnings("unchecked")
    private LootEntry parseLootEntryFromMap(Map<?, ?> map) {
        String type = "item";
        Object typeObj = map.get("type");
        if (typeObj instanceof String str) {
            type = str;
        }

        String itemId = "minecraft:stone";
        Object itemObj = map.get("item");
        if (itemObj instanceof String str) {
            itemId = str;
        }

        double weight = 1.0D;
        Object weightObj = map.get("weight");
        if (weightObj instanceof Number num) {
            weight = num.doubleValue();
        }

        IntRange count = parseCount(map.get("count"), map.get("amount"));

        String entity = null;
        Object entityObj = map.get("entity");
        if (entityObj instanceof String str) {
            entity = str;
        }

        String command = null;
        Object commandObj = map.get("command");
        if (commandObj instanceof String str) {
            command = str;
        }

        return new LootEntry(type, itemId, count, weight, entity, command, parseDisplayItem(map.get("display-item")));
    }

    /**
     * Accepts a number, a {@code "min~max"} string, or a {@code {min:, max:}} section/map.
     * {@code amount} is an alias for {@code count}.
     */
    private static IntRange parseCount(Object countValue, Object amountValue) {
        Object value = countValue != null ? countValue : amountValue;
        if (value == null) {
            return IntRange.fixed(1);
        }
        if (value instanceof Number number) {
            return IntRange.fixed(number.intValue());
        }
        if (value instanceof String text) {
            return parseCountString(text);
        }
        // Sections come from the file loader; maps from the list-of-maps fallback and the bundle.
        Integer min = null;
        Integer max = null;
        if (value instanceof ConfigurationSection section) {
            min = readInt(section.get("min"));
            max = readInt(section.get("max"));
        } else if (value instanceof Map<?, ?> map) {
            min = readInt(map.get("min"));
            max = readInt(map.get("max"));
        } else {
            return IntRange.fixed(1);
        }
        if (min == null && max == null) {
            return IntRange.fixed(1);
        }
        int low = min != null ? min : 1;
        return new IntRange(low, max != null ? max : low);
    }

    private static IntRange parseCountString(String text) {
        String trimmed = text.trim();
        int separator = trimmed.indexOf('~');
        if (separator < 0) {
            Integer single = readInt(trimmed);
            return IntRange.fixed(single != null ? single : 1);
        }
        Integer min = readInt(trimmed.substring(0, separator));
        Integer max = readInt(trimmed.substring(separator + 1));
        if (min == null && max == null) {
            return IntRange.fixed(1);
        }
        int low = min != null ? min : 1;
        return new IntRange(low, max != null ? max : low);
    }

    private static Integer readInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** display-item is either a bare item id or a section holding it under item/material/id. */
    private static String parseDisplayItem(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        String[] keys = {"item", "material", "id", "type"};
        if (value instanceof ConfigurationSection section) {
            for (String key : keys) {
                String candidate = section.getString(key);
                if (candidate != null && !candidate.isBlank()) {
                    return candidate.trim();
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : keys) {
                Object candidate = map.get(key);
                if (candidate instanceof String text && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    public ItemStack rollLoot(String lootTableId) {
        if (lootTableId == null) {
            return null;
        }

        LootTable table = lootTables.get(lootTableId);
        if (table == null) {
            plugin.getLogger().warning(LanguageManager.log("log-loot-table-missing",
                "Loot table not found: {id}", Map.of("id", String.valueOf(lootTableId))));
            return null;
        }

        return table.roll();
    }

    public LootEntry rollLootEntry(String lootTableId) {
        if (lootTableId == null) {
            return null;
        }

        LootTable table = lootTables.get(lootTableId);
        if (table == null) {
            plugin.getLogger().warning(LanguageManager.log("log-loot-table-missing",
                "Loot table not found: {id}", Map.of("id", String.valueOf(lootTableId))));
            return null;
        }

        return table.rollEntry();
    }

    public List<LootEntry> rollLootEntries(String lootTableId) {
        if (lootTableId == null) {
            return List.of();
        }

        LootTable table = lootTables.get(lootTableId);
        if (table == null) {
            plugin.getLogger().warning(LanguageManager.log("log-loot-table-missing",
                "Loot table not found: {id}", Map.of("id", String.valueOf(lootTableId))));
            return List.of();
        }

        return table.rollEntries();
    }

    // True when the key is not one of our own tables but Bukkit can resolve it,
    // e.g. minecraft:archaeology/desert_well.
    public boolean isVanillaLootTable(String key) {
        if (key == null || key.isBlank() || lootTables.containsKey(key)) {
            return false;
        }
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        if (nk == null) {
            return false;
        }
        try {
            return Bukkit.getLootTable(nk) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // Rolls through Bukkit so the real items keep their full NBT; empty list on failure.
    public List<ItemStack> rollVanillaLoot(String key, Location location) {
        if (key == null || location == null) {
            return List.of();
        }
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        if (nk == null) {
            return List.of();
        }
        org.bukkit.loot.LootTable table = Bukkit.getLootTable(nk);
        if (table == null) {
            plugin.getLogger().warning(LanguageManager.log("log-vanilla-loot-table-missing",
                "Vanilla loot table not found: {id}", Map.of("id", String.valueOf(key))));
            return List.of();
        }
        try {
            org.bukkit.loot.LootContext context = new org.bukkit.loot.LootContext.Builder(location).build();
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack item : table.populateLoot(java.util.concurrent.ThreadLocalRandom.current(), context)) {
                if (item != null && item.getType() != Material.AIR) {
                    result.add(item);
                }
            }
            return result;
        } catch (Throwable t) {
            plugin.getLogger().warning(LanguageManager.log("log-vanilla-loot-roll-failed",
                "Failed to roll vanilla loot table {id}: {error}",
                Map.of("id", String.valueOf(key), "error", String.valueOf(t.getMessage()))));
            return List.of();
        }
    }

    public Set<String> getLootTableIds() {
        return lootTables.keySet();
    }

    public boolean hasLootTable(String lootTableId) {
        return lootTableId != null && lootTables.containsKey(lootTableId);
    }

    public int getLootTableCount() {
        return lootTables.size();
    }

    public static LootEntry extractLootEntry(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(LOOT_TYPE_KEY, PersistentDataType.STRING);
        if (type == null) {
            return null;
        }

        String entity = pdc.get(LOOT_ENTITY_KEY, PersistentDataType.STRING);
        String command = pdc.get(LOOT_COMMAND_KEY, PersistentDataType.STRING);

        return new LootEntry(type, null, 1, 1, entity, command);
    }

    public static List<LootEntry> extractLootEntries(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return List.of();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }

        String serialized = meta.getPersistentDataContainer().get(LOOT_BUNDLE_KEY, PersistentDataType.STRING);
        if (serialized == null || serialized.isEmpty()) {
            LootEntry singleEntry = extractLootEntry(item);
            return singleEntry != null ? List.of(singleEntry) : List.of();
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(serialized);
        } catch (Exception e) {
            return List.of();
        }

        List<LootEntry> entries = new ArrayList<>();
        List<Map<?, ?>> entryMaps = config.getMapList("entries");
        for (Map<?, ?> entryMap : entryMaps) {
            entries.add(parseSerializedLootEntry(entryMap));
        }
        return entries;
    }

    public static ItemStack applyLootBundle(ItemStack item, List<LootEntry> entries) {
        if (item == null || item.getType() == Material.AIR || entries == null || entries.isEmpty()) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> serializedEntries = new ArrayList<>();
        for (LootEntry entry : entries) {
            serializedEntries.add(serializeLootEntry(entry));
        }
        config.set("entries", serializedEntries);

        meta.getPersistentDataContainer().set(LOOT_BUNDLE_KEY, PersistentDataType.STRING, config.saveToString());
        item.setItemMeta(meta);
        return item;
    }

    private static Map<String, Object> serializeLootEntry(LootEntry entry) {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("type", entry.type());
        serialized.put("item", entry.itemId());
        // A fixed amount as a plain number, otherwise "min~max".
        serialized.put("count", entry.count().isFixed()
            ? entry.count().min()
            : entry.count().min() + "~" + entry.count().max());
        serialized.put("weight", entry.weight());
        serialized.put("entity", entry.entity());
        serialized.put("command", entry.command());
        serialized.put("display-item", entry.displayItemId());
        return serialized;
    }

    private static LootEntry parseSerializedLootEntry(Map<?, ?> map) {
        String type = map.get("type") instanceof String str ? str : "item";
        String itemId = map.get("item") instanceof String str ? str : null;
        IntRange count = parseCount(map.get("count"), map.get("amount"));
        double weight = map.get("weight") instanceof Number num ? num.doubleValue() : 1.0D;
        String entity = map.get("entity") instanceof String str ? str : null;
        String command = map.get("command") instanceof String str ? str : null;
        return new LootEntry(type, itemId, count, weight, entity, command, parseDisplayItem(map.get("display-item")));
    }

    public record LootTable(String id, List<LootPool> pools) {
        public ItemStack roll() {
            List<LootEntry> entries = rollEntries();
            if (entries.isEmpty()) {
                return null;
            }

            ItemStack item = null;
            LootEntry primaryEntry = null;
            for (LootEntry entry : entries) {
                ItemStack candidate = entry.createItemStack();
                if (candidate != null && candidate.getType() != Material.AIR) {
                    item = candidate;
                    primaryEntry = entry;
                    break;
                }
            }

            if (item == null) {
                return null;
            }

            if (primaryEntry != null) {
                applyLootBundle(item, entries);
            }
            return item;
        }

        public LootEntry rollEntry() {
            List<LootEntry> entries = rollEntries();
            return entries.isEmpty() ? null : entries.get(0);
        }

        public List<LootEntry> rollEntries() {
            List<LootEntry> results = new ArrayList<>();
            for (LootPool pool : pools) {
                results.addAll(pool.rollEntries());
            }
            return results;
        }
    }

    public record LootPool(int rolls, List<LootEntry> entries, double cachedTotalWeight) {
        private static final Random RANDOM = new Random();

        public LootPool(int rolls, List<LootEntry> entries) {
            this(rolls, entries, calculateTotalWeight(entries));
        }

        private static double calculateTotalWeight(List<LootEntry> entries) {
            return entries.stream()
                .mapToDouble(e -> Math.max(0.0D, e.weight()))
                .sum();
        }

        public ItemStack roll() {
            LootEntry entry = rollEntry();
            return entry != null ? entry.createItemStack() : null;
        }

        public LootEntry rollEntry() {
            List<LootEntry> results = rollEntries();
            return results.isEmpty() ? null : results.get(0);
        }

        public List<LootEntry> rollEntries() {
            List<LootEntry> results = new ArrayList<>();
            // Clamp negatives only. Forcing a minimum of 1 made rolls: 0 impossible, so a pool
            // an admin meant to disable kept producing loot on every brush. Unset rolls still
            // defaults to 1 at parse time.
            int effectiveRolls = Math.max(0, rolls);
            for (int i = 0; i < effectiveRolls; i++) {
                LootEntry entry = rollSingleEntry();
                if (entry != null) {
                    results.add(entry);
                }
            }
            return results;
        }

        private LootEntry rollSingleEntry() {
            if (entries.isEmpty()) {
                return null;
            }

            double totalWeight = cachedTotalWeight;
            if (totalWeight <= 0) {
                return null;
            }

            double randomValue = RANDOM.nextDouble() * totalWeight;
            double currentWeight = 0.0D;
            LootEntry lastCandidate = null;
            for (LootEntry entry : entries) {
                double weight = Math.max(0.0D, entry.weight());
                if (weight <= 0) {
                    continue;
                }
                lastCandidate = entry;
                currentWeight += weight;
                if (randomValue < currentWeight) {
                    return "empty".equals(entry.type()) ? null : entry.withRolledCount();
                }
            }

            // Floating-point accumulation can leave randomValue past the last entry.
            if (lastCandidate != null) {
                return "empty".equals(lastCandidate.type()) ? null : lastCandidate.withRolledCount();
            }
            return null;
        }
    }

    /**
     * Inclusive integer range for an entry's amount. A plain number is min == max.
     * Parsed from {@code 1}, {@code "1~2"} or {@code {min: 1, max: 2}}.
     */
    public record IntRange(int min, int max) {
        public static IntRange fixed(int value) {
            int clamped = Math.max(0, value);
            return new IntRange(clamped, clamped);
        }

        public IntRange {
            min = Math.max(0, min);
            max = Math.max(0, max);
            if (max < min) {
                int swap = min;
                min = max;
                max = swap;
            }
        }

        public int roll() {
            return min == max ? min : min + ThreadLocalRandom.current().nextInt(max - min + 1);
        }

        public boolean isFixed() {
            return min == max;
        }
    }

    public record LootEntry(String type, String itemId, IntRange count, double weight,
                            String entity, String command, String displayItemId) {

        /** Fixed count, no display item. */
        public LootEntry(String type, String itemId, int count, double weight, String entity, String command) {
            this(type, itemId, IntRange.fixed(count), weight, entity, command, null);
        }

        /** Resolves the range to a fixed amount. Called once per roll so preview and payout agree. */
        public LootEntry withRolledCount() {
            return new LootEntry(type, itemId, IntRange.fixed(count.roll()), weight, entity, command, displayItemId);
        }

        /** Item shown while brushing; falls back to the reward item. */
        public ItemStack createItemStack() {
            return build(displayItemId != null && !displayItemId.isEmpty() ? displayItemId : itemId);
        }

        /** Item granted when brushing completes. */
        public ItemStack createRewardItemStack() {
            return build(itemId);
        }

        private ItemStack build(String id) {
            if ("empty".equals(type)) {
                return null;
            }

            int amount = count.roll();
            ItemStack item = null;

            if ("entity".equals(type) || "command".equals(type)) {
                if (id == null || id.isEmpty()) {
                    item = new ItemStack(Material.PAPER);
                }
            }

            if (item == null) {
                if (id == null || id.isEmpty()) {
                    return createFallbackItem(LanguageManager.log("log-loot-item-id-empty", "item ID is empty"));
                }

                if (id.contains(":") && !id.startsWith("minecraft:")) {
                    String[] parts = id.split(":");
                    if (parts.length == 2) {
                        Key key = Key.of(parts[0], parts[1]);
                        BukkitItemDefinition customItem = CraftEngineItems.byId(key);
                        if (customItem != null) {
                            item = customItem.buildBukkitItem();
                            item.setAmount(amount);
                        }
                    }
                }

                if (item == null) {
                    ItemHook itemHook = ItemHook.getInstance();
                    if (itemHook != null) {
                        item = itemHook.getItem(id, amount);
                    }
                }

                if (item == null) {
                    Material material = Material.matchMaterial(id);
                    if (material != null) {
                        item = new ItemStack(material, amount);
                    }
                }
            }

            if (item == null) {
                return createFallbackItem(LanguageManager.log("log-loot-item-not-found",
                    "cannot find item: {id}", Map.of("id", String.valueOf(id))));
            }

            if ("entity".equals(type) || "command".equals(type)) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(LOOT_TYPE_KEY, PersistentDataType.STRING, type);
                    if (entity != null) {
                        pdc.set(LOOT_ENTITY_KEY, PersistentDataType.STRING, entity);
                    }
                    if (command != null) {
                        pdc.set(LOOT_COMMAND_KEY, PersistentDataType.STRING, command);
                    }
                    item.setItemMeta(meta);
                }
            }

            return item;
        }

        private ItemStack createFallbackItem(String reason) {
            CEArcheology.getInstance().getLogger().warning(LanguageManager.log("log-loot-fallback-stone",
                "[LootTable] {reason}, falling back to stone.", Map.of("reason", String.valueOf(reason))));
            return new ItemStack(Material.STONE, 1);
        }
    }
}
