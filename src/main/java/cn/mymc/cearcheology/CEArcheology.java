package cn.mymc.cearcheology;

import cn.mymc.cearcheology.behavior.BrushableBlockBehavior;
import cn.mymc.cearcheology.command.MainCommand;
import cn.mymc.cearcheology.config.ConfigManager;
import cn.mymc.cearcheology.listener.BlockPlaceListener;
import cn.mymc.cearcheology.listener.BrushPacketListener;
import cn.mymc.cearcheology.manager.BrushInteractionTracker;
import cn.mymc.cearcheology.manager.LootTableManager;
import cn.mymc.cearcheology.manager.ProgressStorage;
import cn.mymc.cearcheology.manager.ToolManager;
import cn.mymc.cearcheology.locale.LanguageManager;
import cn.mymc.cearcheology.util.DebugUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class CEArcheology extends JavaPlugin {

    private static volatile CEArcheology instance;
    private LootTableManager lootTableManager;
    private ToolManager toolManager;
    private ProgressStorage progressStorage;
    private BrushInteractionTracker brushInteractionTracker;
    private cn.mymc.cearcheology.hook.ProtectionHookManager protectionHookManager;
    private boolean craftEngineIntegrated = false;
    private BrushPacketListener brushPacketListener;
    // Set in onLoad when CraftEngine still holds a behavior factory from a previous class
    // loader (hot reload); the plugin disables itself in onEnable.
    private boolean staleBehaviorDetected = false;
    private boolean resourcesDeployed = false;

    @Override
    public void onLoad() {
        registerBlockBehaviors();

        // Config and resource deployment must happen in onLoad: CraftEngine declares
        // load: STARTUP and scans resources/ from its own onEnable, which runs before ours,
        // so deploying in onEnable means no archeology blocks exist on first boot.
        // Factory.create also reads ConfigManager while CE is parsing those configs.
        try {
            getDataFolder().mkdirs();
            saveDefaultConfig();
            reloadConfig();
            ConfigManager.init(this);
            LanguageManager.init(this);
            saveAllResources();
            deployToCraftEngine();
            resourcesDeployed = true;
        } catch (Exception e) {
            getLogger().warning(LanguageManager.log("log-onload-init-failed",
                "Failed to initialize config/resources during onLoad, retrying in onEnable: {error}",
                Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    @Override
    public void onEnable() {
        if (staleBehaviorDetected) {
            getLogger().severe(LanguageManager.log("log-stale-behavior-detected",
                "A brushable_block behavior factory from a previous load is still registered in CraftEngine."));
            getLogger().severe(LanguageManager.log("log-stale-behavior-explain",
                "CraftEngine's behavior registry cannot unregister entries, so the newly loaded classes will never receive interactions;"));
            getLogger().severe(LanguageManager.log("log-stale-behavior-restart",
                "CEArcheology does not support hot reloading (plugman/reload), please fully restart the server."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!checkCraftEngine()) {
            getLogger().severe(LanguageManager.log("log-craftengine-missing",
                "CraftEngine plugin not found, please make sure CraftEngine is installed correctly."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // onLoad may have thrown midway, leaving only one manager initialized.
        if (ConfigManager.getInstance() == null) {
            saveDefaultConfig();
            reloadConfig();
            ConfigManager.init(this);
        }
        if (LanguageManager.getInstance() == null) {
            LanguageManager.init(this);
        }
        // Re-running a successful onLoad deployment would only duplicate its log output.
        if (!resourcesDeployed) {
            saveAllResources();
            deployToCraftEngine();
        }

        initManagers();

        // Must come after initManagers: once instance is visible CraftEngine can dispatch
        // useOnBlock, and the behavior immediately grabs ToolManager/ProgressStorage; assigning
        // earlier made every interaction NPE when onEnable threw midway. Must also come before
        // registerPacketListener, whose callbacks read getInstance() on the Netty thread.
        instance = this;

        registerCommands();
        registerPacketListener();
        registerCraftEngineReloadListener();

        getServer().getScheduler().runTaskLater(this, this::checkLoadedBlocks, 20L);

        getLogger().info(LanguageManager.log("log-plugin-enabled", "CEArcheology enabled."));
    }

    @Override
    public void onDisable() {
        BrushableBlockBehavior.cleanup();

        if (progressStorage != null) {
            progressStorage.saveAll();
        }

        if (brushPacketListener != null) {
            try {
                brushPacketListener.unregister();
                getLogger().info(LanguageManager.log("log-packet-listener-closed",
                    "CraftEngine packet listener closed."));
            } catch (Exception e) {
                getLogger().warning(LanguageManager.log("log-packet-listener-close-failed",
                    "Failed to close the CraftEngine packet listener: {error}",
                    Map.of("error", String.valueOf(e.getMessage()))));
            }
        }

        // The behavior cannot be unregistered from CraftEngine's registry and CE keeps dispatching
        // useOnBlock; nulling instance is the only way the behavior can tell it must stand down.
        instance = null;

        getLogger().info(LanguageManager.log("log-plugin-disabled", "CEArcheology disabled."));
    }

    // /ce reload goes through BukkitBlockManager.delayedLoad -> registerBlockStatePacketListeners
    // and writes CE's own listener back into c2sPlayPacketListeners, overwriting our wrapper;
    // without reattaching we degrade permanently to hand-raise detection. loadedBlocks() is also
    // still empty during onEnable, so the block count must be taken after this event.
    private void registerCraftEngineReloadListener() {
        try {
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
                public void onCraftEngineReload(net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent event) {
                    if (brushPacketListener != null) {
                        brushPacketListener.reattach();
                    }
                    checkLoadedBlocks();
                }
            }, this);
        } catch (Throwable t) {
            getLogger().warning(LanguageManager.log("log-reload-listener-failed",
                "Failed to register the CraftEngine reload listener, a server restart may be needed after /ce reload: {error}",
                Map.of("error", String.valueOf(t.getMessage()))));
        }
    }

    private boolean checkCraftEngine() {
        return getServer().getPluginManager().getPlugin("CraftEngine") != null;
    }

    private void registerPacketListener() {
        brushPacketListener = new BrushPacketListener(this);
        brushPacketListener.register();
        getLogger().info(LanguageManager.log("log-packet-listener-registered",
            "Registered the CraftEngine brushing packet listener."));
    }

    private void registerBlockBehaviors() {
        try {
            Key behaviorKey = Key.of("cearcheology:brushable_block");

            var existing = net.momirealms.craftengine.core.registry.BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(behaviorKey);
            if (existing != null) {
                // CraftEngine's behavior registry has no unregister, so entries survive until CE's
                // class loader dies. An existing entry therefore means we were hot reloaded and CE
                // still holds the old class loader's factory: interactions land in the old classes
                // whose static tables are empty and whose ticker never runs, silently breaking
                // brushing. Only flag it here: disablePlugin is a no-op before the plugin is
                // enabled, so the actual shutdown has to wait for onEnable.
                staleBehaviorDetected = true;
                return;
            }

            BlockBehaviors.register(behaviorKey, BrushableBlockBehavior.FACTORY);
            getLogger().info(LanguageManager.log("log-behavior-registered",
                "Registered the brushable_block behavior."));
        } catch (Exception e) {
            getLogger().warning(LanguageManager.log("log-behavior-register-failed",
                "Failed to register the brushable_block behavior: {error}",
                Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void saveAllResources() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        saveDefaultLootTables();
        saveDefaultTools();
        getLogger().info(LanguageManager.log("log-default-configs-saved",
            "Default configuration files checked and saved."));
    }

    private void saveDefaultLootTables() {
        File lootFolder = new File(getDataFolder(), "loot_tables");
        if (!lootFolder.exists()) {
            lootFolder.mkdirs();
        }
        // Checked per file, not per directory, or upgraded installs never receive newly bundled files.
        if (!new File(lootFolder, "default.yml").exists()) {
            saveResource("loot_tables/default.yml", false);
        }
    }

    private void saveDefaultTools() {
        File toolsFolder = new File(getDataFolder(), "tools");
        if (!toolsFolder.exists()) {
            toolsFolder.mkdirs();
        }
        // Checked per file, not per directory, or upgraded installs never receive newly bundled files.
        if (!new File(toolsFolder, "default_brush.yml").exists()) {
            saveResource("tools/default_brush.yml", false);
        }
        // The example used to ship in the jar but was never extracted, hiding the custom brush format.
        if (!new File(toolsFolder, "custom_brush_example.yml").exists()) {
            saveResource("tools/custom_brush_example.yml", false);
        }
        // Tiered brushes; each needs its CraftEngine item from cearcheology/configuration/brushes.yml.
        for (String tool : new String[]{"brush_copper.yml", "brush_diamond.yml", "brush_netherite.yml"}) {
            if (!new File(toolsFolder, tool).exists()) {
                saveResource("tools/" + tool, false);
            }
        }
    }

    private File findCraftEngineResourcesDir() {
        File pluginsDir = getDataFolder().getParentFile();

        String[] possibleNames = {"CraftEngine", "craftengine", "Craftengine", "CECraftEngine"};
        for (String name : possibleNames) {
            File dir = new File(pluginsDir, name);
            if (dir.exists() && dir.isDirectory()) {
                File resourcesDir = new File(dir, "resources");
                if (resourcesDir.exists()) {
                    return resourcesDir;
                }
            }
        }

        File[] files = pluginsDir.listFiles(File::isDirectory);
        if (files != null) {
            for (File dir : files) {
                if (dir.getName().toLowerCase().contains("craftengine")
                    || dir.getName().toLowerCase().equals("ce")) {
                    File resourcesDir = new File(dir, "resources");
                    if (resourcesDir.exists()) {
                        return resourcesDir;
                    }
                }
            }
        }

        return null;
    }

    private void deployToCraftEngine() {
        File ceResourcesDir = findCraftEngineResourcesDir();
        if (ceResourcesDir == null) {
            getLogger().warning(LanguageManager.log("log-ce-resources-missing",
                "CraftEngine resources directory not found."));
            getLogger().warning(LanguageManager.log("log-ce-resources-manual-copy",
                "Please copy the cearcheology resource directory into CraftEngine/resources/ manually."));
            return;
        }
        File targetDir = new File(ceResourcesDir, "cearcheology");
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        int deployed = 0;
        deployed += deployPackYml(targetDir);
        deployed += deployCategories(targetDir);
        deployed += deployLangs(targetDir);
        deployed += deploySubpacks(targetDir);
        deployed += deployItemConfigs(targetDir);
        deployed += deployResourcePack(targetDir);

        if (deployed > 0) {
            getLogger().info(LanguageManager.log("log-deploy-summary",
                "Deployed {count} missing CraftEngine default resource files.",
                Map.of("count", String.valueOf(deployed))));
        } else {
            DebugUtils.debug("CraftEngine resources checked, nothing missing: " + ceResourcesDir.getAbsolutePath());
        }
    }

    private int deployPackYml(File targetDir) {
        return deployResourceFromJar(targetDir, "cearcheology/pack.yml", "pack.yml");
    }

    // Natural generation lives in a subpack that pack.yml leaves disabled, so shipping it
    // costs nothing until an operator opts in.
    private int deploySubpacks(File targetDir) {
        return deployResourceFromJar(targetDir,
            "cearcheology/subpacks/worldgen/configuration/features.yml",
            "subpacks/worldgen/configuration/features.yml");
    }

    // Block and category names are declared as <lang:...> keys resolved by CraftEngine's
    // client-side localization, so every player sees them in their own client language.
    // Without these files the raw keys would show up in-game.
    private int deployLangs(File targetDir) {
        File langsDir = new File(targetDir, "configuration/langs");
        if (!langsDir.exists()) {
            langsDir.mkdirs();
        }

        int deployed = 0;
        for (String lang : new String[]{"zh_cn.yml", "en_us.yml"}) {
            deployed += deployResourceFromJar(targetDir,
                "cearcheology/configuration/langs/" + lang,
                "configuration/langs/" + lang);
        }
        return deployed;
    }

    private int deployCategories(File targetDir) {
        File categoriesDir = new File(targetDir, "configuration/categories");
        if (!categoriesDir.exists()) {
            categoriesDir.mkdirs();
        }
        return deployResourceFromJar(targetDir, "cearcheology/configuration/archeology.yml", "configuration/categories/archeology.yml");
    }

    private int deployResourceFromJar(File targetDir, String resourcePath, String targetPath) {
        File targetFile = new File(targetDir, targetPath);
        if (!targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        try (java.io.InputStream is = getResource(resourcePath)) {
            if (is == null) {
                getLogger().warning(LanguageManager.log("log-bundled-resource-missing",
                    "Bundled resource not found: {path}", Map.of("path", resourcePath)));
                return 0;
            }
            boolean copied = copyResourceIfMissing(is, targetFile);
            if (!copied) {
                return 0;
            }
            getLogger().info(LanguageManager.log("log-deploy-resource",
                "Deployed resource file: {path}", Map.of("path", targetPath)));
            return 1;
        } catch (IOException e) {
            getLogger().warning(LanguageManager.log("log-deploy-resource-failed",
                "Failed to deploy resource file {path}: {error}",
                Map.of("path", targetPath, "error", String.valueOf(e.getMessage()))));
            return 0;
        }
    }
    private boolean copyResourceIfMissing(java.io.InputStream inputStream, File targetFile) throws IOException {
        if (targetFile.exists()) {
            return false;
        }
        Files.copy(inputStream, targetFile.toPath());
        return true;
    }

    private int deployItemConfigs(File targetDir) {
        File itemsDir = new File(targetDir, "configuration/items");
        if (!itemsDir.exists()) {
            itemsDir.mkdirs();
        }

        File configDir = new File(targetDir, "configuration");
        String[] itemConfigs = {
            "suspicious_stone.yml",
            "suspicious_deepslate.yml",
            "suspicious_dirt.yml",
            "suspicious_end_stone.yml",
            "suspicious_netherrack.yml",
            "suspicious_sculk.yml",
            // Tiered brushes.
            "brushes.yml"
        };

        int deployed = 0;
        for (String config : itemConfigs) {
            // CraftEngine scans configuration/ recursively, so the old flat layout and the new
            // items/ layout would define the same id twice, tripping resource.duplicated_id and
            // dropping one of them. The old file already provides these definitions.
            File oldFile = new File(configDir, config);
            if (oldFile.exists()) {
                getLogger().warning(LanguageManager.log("log-legacy-flat-config",
                    "Found legacy flat-layout config configuration/{file}, skipped deploying configuration/items/{file} to avoid a duplicate id; delete the old file and restart to use the new layout.",
                    Map.of("file", config)));
                continue;
            }

            String resourcePath = "cearcheology/configuration/" + config;
            try (java.io.InputStream is = getResource(resourcePath)) {
                if (is == null) {
                    continue;
                }
                File target = new File(itemsDir, config);
                if (target.exists()) {
                    continue;
                }
                copyResourceIfMissing(is, target);
                getLogger().info(LanguageManager.log("log-deploy-item-config",
                    "Deployed item config: {file}", Map.of("file", config)));
                deployed++;
            } catch (IOException e) {
                getLogger().warning(LanguageManager.log("log-deploy-item-config-failed",
                    "Failed to deploy item config {file}: {error}",
                    Map.of("file", config, "error", String.valueOf(e.getMessage()))));
            }
        }
        if (deployed > 0) {
            getLogger().info(LanguageManager.log("log-deploy-item-config-summary",
                "Deployed {count} item config files in total.",
                Map.of("count", String.valueOf(deployed))));
        }
        return deployed;
    }

    private int deployResourcePack(File targetDir) {
        return deployTexturesFromJar(targetDir) + deployModelsFromJar(targetDir);
    }

    private int deployTexturesFromJar(File targetDir) {
        File texturesDir = new File(targetDir, "resourcepack/assets/cearcheology/textures/block");
        if (!texturesDir.exists()) {
            texturesDir.mkdirs();
        }

        String[] blocks = {"stone", "deepslate", "dirt", "end_stone", "netherrack", "sculk"};
        int deployed = 0;

        for (String block : blocks) {
            for (int i = 0; i <= 3; i++) {
                String texture = "suspicious_" + block + "_" + i + ".png";
                String resourcePath = "cearcheology/resourcepack/assets/cearcheology/textures/block/" + texture;
                // Do not continue here: an already-present texture still needs its .mcmeta.
                // The sculk textures are 16x64 animation strips and render as one squashed
                // static texture without it.
                try (java.io.InputStream is = getResource(resourcePath)) {
                    if (is != null) {
                        File target = new File(texturesDir, texture);
                        if (!target.exists()) {
                            copyResourceIfMissing(is, target);
                            deployed++;
                        }
                    }
                } catch (IOException e) {
                    getLogger().warning(LanguageManager.log("log-deploy-texture-failed",
                        "Failed to deploy texture {file}: {error}",
                        Map.of("file", texture, "error", String.valueOf(e.getMessage()))));
                }

                String mcmeta = texture + ".mcmeta";
                String mcmetaPath = "cearcheology/resourcepack/assets/cearcheology/textures/block/" + mcmeta;
                try (java.io.InputStream is = getResource(mcmetaPath)) {
                    if (is == null) {
                        continue;
                    }
                    File target = new File(texturesDir, mcmeta);
                    if (target.exists()) {
                        continue;
                    }
                    copyResourceIfMissing(is, target);
                } catch (IOException e) {
                    // mcmeta is optional
                }
            }
        }
        if (deployed > 0) {
            getLogger().info(LanguageManager.log("log-deploy-texture-summary",
                "Deployed {count} texture files in total.",
                Map.of("count", String.valueOf(deployed))));
        }
        return deployed;
    }

    private int deployModelsFromJar(File targetDir) {
        File blockModelsDir = new File(targetDir, "resourcepack/assets/cearcheology/models/block");

        if (!blockModelsDir.exists()) {
            blockModelsDir.mkdirs();
        }

        String[] blocks = {"stone", "deepslate", "dirt", "end_stone", "netherrack", "sculk"};
        int deployed = 0;

        for (String block : blocks) {
            // Block items use the block model, so there is no models/item pass.
            for (int i = 0; i <= 3; i++) {
                String blockModel = "suspicious_" + block + "_" + i + ".json";
                String blockResourcePath = "cearcheology/resourcepack/assets/cearcheology/models/block/" + blockModel;
                try (java.io.InputStream is = getResource(blockResourcePath)) {
                    if (is != null) {
                        File target = new File(blockModelsDir, blockModel);
                        if (!target.exists()) {
                            copyResourceIfMissing(is, target);
                            deployed++;
                        }
                    }
                } catch (IOException e) {
                    getLogger().warning(LanguageManager.log("log-deploy-block-model-failed",
                        "Failed to deploy block model {file}: {error}",
                        Map.of("file", blockModel, "error", String.valueOf(e.getMessage()))));
                }
            }
        }
        if (deployed > 0) {
            getLogger().info(LanguageManager.log("log-deploy-model-summary",
                "Deployed {count} model files in total.",
                Map.of("count", String.valueOf(deployed))));
        }
        return deployed;
    }

    private int checkLoadedBlocks() {
        Map<Key, BlockDefinition> loadedBlocks = CraftEngineBlocks.loadedBlocks();
        craftEngineIntegrated = false;

        DebugUtils.debug("Checking loaded blocks, total: " + loadedBlocks.size());

        int loadedCount = 0;
        for (Map.Entry<Key, BlockDefinition> entry : loadedBlocks.entrySet()) {
            var customBlock = entry.getValue();
            var state = customBlock.defaultState();
            if (state != null) {
                // Resolved via CraftEngine's getFirst: a block with a second behavior gets a
                // Dual/CompositeBlockBehavior as its root, so comparing class names would miss it.
                boolean isBrushable = cn.mymc.cearcheology.behavior.BrushableBlockBehavior.brushableBehavior(state) != null;
                DebugUtils.debug("Block: " + entry.getKey() + ", isBrushable: " + isBrushable);
                if (isBrushable) {
                    loadedCount++;
                    getLogger().info(LanguageManager.log("log-archeology-block-loaded",
                        "Loaded archeology block: {id}", Map.of("id", String.valueOf(entry.getKey()))));
                }
            }
        }

        if (loadedCount > 0) {
            craftEngineIntegrated = true;
            getLogger().info(LanguageManager.log("log-archeology-blocks-integrated",
                "Successfully integrated {count} archeology blocks.",
                Map.of("count", String.valueOf(loadedCount))));
        } else {
            getLogger().warning(LanguageManager.log("log-no-archeology-blocks",
                "No archeology blocks detected, please check the following:"));
            getLogger().warning(LanguageManager.log("log-no-archeology-blocks-1",
                "1. CraftEngine is installed correctly."));
            getLogger().warning(LanguageManager.log("log-no-archeology-blocks-2",
                "2. Run /ce reload to reload CraftEngine."));
            getLogger().warning(LanguageManager.log("log-no-archeology-blocks-3",
                "3. Check that the configs under CraftEngine/resources/ include the brushable_block behavior."));
        }
        return loadedCount;
    }

    private void initManagers() {
        new cn.mymc.cearcheology.hook.ItemHook(this);

        this.progressStorage = new ProgressStorage(this);
        this.lootTableManager = new LootTableManager(this);
        this.toolManager = new ToolManager(this);
        this.brushInteractionTracker = new BrushInteractionTracker();
        this.protectionHookManager = new cn.mymc.cearcheology.hook.ProtectionHookManager(this);

        getServer().getServicesManager().register(LootTableManager.class, lootTableManager, this, org.bukkit.plugin.ServicePriority.Normal);

        this.lootTableManager.load();
        this.toolManager.load();

        getServer().getPluginManager().registerEvents(new BlockPlaceListener(this), this);
        BrushableBlockBehavior.registerListener();
        BrushableBlockBehavior.startPeriodicCleanup(this);
    }

    private void registerCommands() {
        MainCommand mainCommand = new MainCommand(this);
        getCommand("cearcheology").setExecutor(mainCommand);
        getCommand("cearcheology").setTabCompleter(mainCommand);
    }

    public ReloadSummary reload() {
        if (progressStorage != null) {
            progressStorage.saveAll();
            progressStorage.cleanupInvalidWorlds();
        }

        BrushableBlockBehavior.cleanup();
        if (brushInteractionTracker != null) {
            brushInteractionTracker.clearAll();
        }
        reloadConfig();
        ConfigManager.init(this);
        LanguageManager.init(this);
        cn.mymc.cearcheology.behavior.BrushableBlockBehavior.clearDebugCache();
        cn.mymc.cearcheology.listener.BrushPacketListener.clearDebugCache();
        saveAllResources();
        deployToCraftEngine();
        if (lootTableManager != null) {
            lootTableManager.load();
        }
        if (toolManager != null) {
            toolManager.load();
        }
        BrushableBlockBehavior.startPeriodicCleanup(this);
        int loadedBlockCount = checkLoadedBlocks();
        int lootTableCount = lootTableManager != null ? lootTableManager.getLootTableCount() : 0;
        int toolCount = toolManager != null ? toolManager.getToolCount() : 0;
        return new ReloadSummary(lootTableCount, toolCount, loadedBlockCount, craftEngineIntegrated);
    }

    public record ReloadSummary(int lootTableCount, int toolCount, int loadedBlockCount, boolean craftEngineIntegrated) {}

    public static CEArcheology getInstance() {
        return instance;
    }

    public LootTableManager getLootTableManager() {
        return lootTableManager;
    }

    public ToolManager getToolManager() {
        return toolManager;
    }

    public ProgressStorage getProgressStorage() {
        return progressStorage;
    }

    public BrushInteractionTracker getBrushInteractionTracker() {
        return brushInteractionTracker;
    }

    public cn.mymc.cearcheology.hook.ProtectionHookManager getProtectionHookManager() {
        return protectionHookManager;
    }

    public boolean isCraftEngineIntegrated() {
        return craftEngineIntegrated;
    }

    public boolean isArcheologyBlock(Key blockId) {
        if (blockId == null) {
            return false;
        }
        var customBlock = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.byId(blockId);
        if (customBlock == null) {
            return false;
        }
        var state = customBlock.defaultState();
        if (state == null) {
            return false;
        }
        return cn.mymc.cearcheology.behavior.BrushableBlockBehavior.brushableBehavior(state) != null;
    }
}
