package cn.mymc.cearcheology.behavior;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.config.ConfigManager;
import cn.mymc.cearcheology.manager.LootTableManager;
import cn.mymc.cearcheology.manager.ProgressStorage;
import cn.mymc.cearcheology.util.LocationUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BrushableBlockBehavior extends BukkitBlockBehavior implements EntityBlock {

    public static final BlockBehaviorFactory<BrushableBlockBehavior> FACTORY = new Factory();
    private static final Random RANDOM = new Random();
    private static final int BLOCK_UPDATE_FLAG = 3;
    private static final long CLEANUP_INTERVAL_TICKS = 1200L;
    private static final long DEFAULT_SESSION_TICK_INTERVAL = 4L;
    private static final int LOOK_CHECK_INTERVAL_TICKS = 1;
    private static final int MAX_CACHE_SIZE = 10000;
    
    private static long getBrushTickInterval() {
        ConfigManager config = ConfigManager.getInstance();
        return config != null ? config.getBrushTickInterval() : DEFAULT_SESSION_TICK_INTERVAL;
    }
    
    private static long getSessionTickInterval() {
        return Math.max(1L, getBrushTickInterval());
    }
    
    private static long getRecoveryDelay() {
        ConfigManager config = ConfigManager.getInstance();
        return config != null ? config.getRecoveryDelay() : 20L;
    }
    
    private static long getRecoveryInterval() {
        ConfigManager config = ConfigManager.getInstance();
        return config != null ? config.getRecoveryInterval() : 5L;
    }
    
    private static int getSessionTimeout() {
        ConfigManager config = ConfigManager.getInstance();
        return config != null ? config.getSessionTimeout() : 300;
    }
    
    private static volatile boolean debugModeCached = false;
    private static volatile boolean debugModeChecked = false;
    private static final Object DEBUG_LOCK = new Object();
    
    private static boolean isDebugMode() {
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
    
    public static void clearDebugCache() {
        synchronized (DEBUG_LOCK) {
            debugModeChecked = false;
            debugModeCached = false;
        }
    }
    
    private static void debug(String message) {
        if (isDebugMode()) {
            Bukkit.getLogger().info("[CEArcheology] " + message);
        }
    }

    // Lazy variant: keeps string concatenation off the hot path when debug is disabled.
    private static void debug(java.util.function.Supplier<String> message) {
        if (isDebugMode()) {
            Bukkit.getLogger().info("[CEArcheology] " + message.get());
        }
    }

    private static void sendProtectionDenied(org.bukkit.entity.Player player) {
        long now = System.currentTimeMillis();
        Long last = lastProtectionDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < PROTECTION_DENY_MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastProtectionDenyMessage.put(player.getUniqueId(), now);
        var lang = cn.mymc.cearcheology.locale.LanguageManager.getInstance();
        if (lang != null) {
            player.sendMessage(lang.get("protection-brush-denied",
                "&cYou cannot brush here (the area is protected)."));
        }
    }
    
    private final IntegerProperty progressProperty;
    private final int maxProgress;
    private final int brushTime;
    private final int requiredBrushLevel;
    private final Key lootTable;
    private final Material resultBlock;
    private final Material dropBlock;
    private final int experience;
    private final int breakExperience;
    private final List<String> brushSounds;
    private final String particleType;
    private final int particleCount;
    private final int particleColor;
    private final float displayStartOffset;
    private final float displayEndOffset;
    private final float displayScale;
    private int controllerId;

    private static final Map<UUID, BrushSession> activeSessions = new ConcurrentHashMap<>();
    private static final Map<LocationUtils.BlockPositionKey, RecoveryTask> recoveryTasks = new ConcurrentHashMap<>();
    private static final Map<String, UUID> lockedBlocks = new ConcurrentHashMap<>();
    private static final Map<LocationUtils.BlockPositionKey, UUID> blockSessionIndex = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastProtectionDenyMessage = new ConcurrentHashMap<>();
    private static final long PROTECTION_DENY_MESSAGE_COOLDOWN_MS = 2000L;
    private static BukkitRunnable sessionTickerTask;
    private static BukkitRunnable recoveryTickerTask;
    private static long recoveryTickerTicks;
    private static BukkitTask cleanupTask;
    
    // Lets the static BlockBreakListener read this instance's config without field reflection.
    public IntegerProperty progressProperty() {
        return progressProperty;
    }

    public int maxProgress() {
        return maxProgress;
    }

    public Material dropBlock() {
        return dropBlock;
    }

    public int breakExperience() {
        return breakExperience;
    }

    private static String locationKey(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return LocationUtils.toKey(loc);
    }
    
    public static boolean isBlockLocked(Location loc) {
        return lockedBlocks.containsKey(locationKey(loc));
    }
    
    public static UUID getBlockLocker(Location loc) {
        return lockedBlocks.get(locationKey(loc));
    }
    
    public static void lockBlock(Location loc, UUID playerId) {
        String key = locationKey(loc);
        if (key != null) {
            lockedBlocks.put(key, playerId);
            cleanupIfTooLarge(lockedBlocks);
        }
    }
    
    public static void unlockBlock(Location loc) {
        lockedBlocks.remove(locationKey(loc));
    }

    private static ImmutableBlockState getCustomBlockState(Block block) {
        return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(block);
    }

    // Must go through CraftEngine's getFirst: as soon as a block declares a second behavior, CE
    // swaps the root for DualBlockBehavior/CompositeBlockBehavior, so instanceof or class-name
    // comparisons miss it.
    public static BrushableBlockBehavior brushableBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        var behavior = state.behavior();
        return behavior == null ? null : behavior.getFirst(BrushableBlockBehavior.class);
    }

    private static boolean isBrushableState(ImmutableBlockState state) {
        return brushableBehavior(state) != null;
    }
    
    private static void cleanupIfTooLarge(Map<String, ?> map) {
        if (map.size() > MAX_CACHE_SIZE) {
            int toRemove = map.size() - MAX_CACHE_SIZE / 2;
            List<String> keys = new ArrayList<>(map.keySet());
            for (int i = 0; i < toRemove && i < keys.size(); i++) {
                map.remove(keys.get(i));
            }
        }
    }
    
    private static void cleanupMapIfTooLarge(Map<?, ?> map) {
        if (map.size() > MAX_CACHE_SIZE) {
            int toRemove = map.size() - MAX_CACHE_SIZE / 2;
            List<?> keys = new ArrayList<>(map.keySet());
            for (int i = 0; i < toRemove && i < keys.size(); i++) {
                map.remove(keys.get(i));
            }
        }
    }
    
    public static void cleanup() {
        if (sessionTickerTask != null) {
            sessionTickerTask.cancel();
            sessionTickerTask = null;
        }
        if (recoveryTickerTask != null) {
            recoveryTickerTask.cancel();
            recoveryTickerTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        for (BrushSession session : activeSessions.values()) {
            if (session != null) {
                session.cancel();
            }
        }
        activeSessions.clear();
        blockSessionIndex.clear();
        
        for (RecoveryTask task : recoveryTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        recoveryTasks.clear();

        lockedBlocks.clear();
        lastProtectionDenyMessage.clear();
    }
    
    public static void startPeriodicCleanup(JavaPlugin plugin) {
        startSessionTicker(plugin);
        startRecoveryTicker(plugin);

        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long timeout = getSessionTimeout() * 50L;
                
                activeSessions.entrySet().removeIf(entry -> {
                    BrushSession session = entry.getValue();
                    if (session != null && now - session.lastRefreshTime > timeout) {
                        session.cancel();
                        blockSessionIndex.values().remove(entry.getKey());
                        return true;
                    }
                    return false;
                });
                
                recoveryTasks.entrySet().removeIf(entry -> {
                    RecoveryTask recoveryTask = entry.getValue();
                    if (recoveryTask == null || recoveryTask.cancelled) {
                        return true;
                    }
                    return false;
                });
                
                if (lockedBlocks.size() > MAX_CACHE_SIZE) {
                    int toRemove = lockedBlocks.size() - MAX_CACHE_SIZE / 2;
                    List<String> keys = new ArrayList<>(lockedBlocks.keySet());
                    for (int i = 0; i < toRemove && i < keys.size(); i++) {
                        lockedBlocks.remove(keys.get(i));
                    }
                }
            }
        }.runTaskTimer(plugin, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
    }

    private static void startSessionTicker(JavaPlugin plugin) {
        if (sessionTickerTask != null) {
            sessionTickerTask.cancel();
        }

        sessionTickerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (activeSessions.isEmpty()) {
                    return;
                }

                for (Map.Entry<UUID, BrushSession> entry : activeSessions.entrySet()) {
                    BrushSession session = entry.getValue();
                    if (session == null) {
                        activeSessions.remove(entry.getKey());
                        continue;
                    }

                    try {
                        session.tick();
                    } catch (Throwable t) {
                        // One broken session must not take down the ticker: every other player's
                        // session is in the same iteration.
                        Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                            "log-brush-session-error", "Brush session failed and was terminated: {error}",
                            Map.of("error", String.valueOf(t))));
                        try {
                            session.cancel(false);
                        } catch (Throwable ignored) {
                            session.cancelled = true;
                        }
                    }

                    if (session.cancelled) {
                        activeSessions.remove(entry.getKey(), session);
                    }
                }
            }
        };
        sessionTickerTask.runTaskTimer(plugin, 1L, getSessionTickInterval());
    }

    private static void startRecoveryTicker(JavaPlugin plugin) {
        if (recoveryTickerTask != null) {
            recoveryTickerTask.cancel();
        }

        recoveryTickerTicks = 0L;
        recoveryTickerTask = new BukkitRunnable() {
            @Override
            public void run() {
                recoveryTickerTicks++;
                if (recoveryTasks.isEmpty()) {
                    return;
                }

                for (Map.Entry<LocationUtils.BlockPositionKey, RecoveryTask> entry : recoveryTasks.entrySet()) {
                    RecoveryTask task = entry.getValue();
                    if (task == null || task.cancelled) {
                        recoveryTasks.remove(entry.getKey(), task);
                        continue;
                    }
                    task.tickIfDue(recoveryTickerTicks);
                }
            }
        };
        recoveryTickerTask.runTaskTimer(plugin, 1L, 1L);
    }
    
    private static boolean isInChunk(LocationUtils.BlockPositionKey key, org.bukkit.World world, int chunkX, int chunkZ) {
        return key != null
            && world != null
            && world.getName().equals(key.getWorldName())
            && (key.getX() >> 4) == chunkX
            && (key.getZ() >> 4) == chunkZ;
    }
    
    public BrushableBlockBehavior(BlockDefinition block, IntegerProperty progressProperty, 
                                   int maxProgress, int brushTime, int requiredBrushLevel, Key lootTable, Material resultBlock,
                                   Material dropBlock, int experience, int breakExperience,
                                   List<String> brushSounds,
                                   String particleType, int particleCount, int particleColor,
                                   float displayStartOffset, float displayEndOffset, float displayScale) {
        super(block);
        this.progressProperty = progressProperty;
        this.maxProgress = maxProgress;
        this.brushTime = brushTime > 0 ? brushTime : 96;
        this.requiredBrushLevel = requiredBrushLevel > 0 ? requiredBrushLevel : 1;
        this.lootTable = lootTable;
        this.resultBlock = resultBlock != null ? resultBlock : Material.AIR;
        this.dropBlock = dropBlock != null ? dropBlock : resultBlock;
        this.experience = experience;
        this.breakExperience = breakExperience;
        this.brushSounds = brushSounds != null && !brushSounds.isEmpty() ? brushSounds : List.of("gravel");
        this.particleType = particleType;
        this.particleCount = particleCount > 0 ? particleCount : 8;
        this.particleColor = particleColor;
        this.displayStartOffset = displayStartOffset;
        this.displayEndOffset = displayEndOffset;
        this.displayScale = displayScale;
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new BrushableBlockEntityController(blockEntity);
    }

    // create=true creates the block entity when the block supports one (write path); false returns
    // it only if it already exists (read path).
    public static BrushableBlockEntityController controllerAt(Location loc, boolean create) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        // Reading block state goes through ServerLevel.getBlockState, which force-loads and may even
        // generate the chunk. Callers on ChunkUnloadEvent and ProgressStorage rely on the
        // "read only, never load" contract of controllerAt(loc, false).
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }

        // CraftEngine assigns the controller index through initControllerId; it only happens to be 0
        // for single-behavior blocks, so read the real index off the state's behavior instance.
        BrushableBlockBehavior behavior = brushableBehavior(getCustomBlockState(loc.getBlock()));
        return controllerAt(loc, create, behavior != null ? behavior.controllerId : 0);
    }

    private static BrushableBlockEntityController controllerAt(Location loc, boolean create, int controllerId) {
        try {
            World ceWorld = (World) BukkitAdaptor.adapt(loc.getWorld());
            net.momirealms.craftengine.core.world.CEWorld storage = ceWorld.storageWorld();
            if (storage == null) {
                return null;
            }
            BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            BlockEntity be = storage.getBlockEntityAtIfLoaded(pos, create);
            if (be == null || be.controller == null) {
                return null;
            }
            return be.controller.let(BrushableBlockEntityController.class, controllerId, c -> c);
        } catch (Exception e) {
            debug(() -> "controllerAt failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        var player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        
        org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.platformPlayer();
        if (bukkitPlayer == null) {
            return InteractionResult.PASS;
        }
        
        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        ItemStack offHand = bukkitPlayer.getInventory().getItemInOffHand();
        
        if (mainHand.getType() == Material.AIR && offHand.getType() == Material.AIR) {
            return InteractionResult.PASS;
        }
        
        var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
        // The isEnabled check is required: a behavior cannot be unregistered from CraftEngine, so CE
        // keeps dispatching useOnBlock after the plugin is disabled and the managers may be gone.
        if (cePlugin == null || !cePlugin.isEnabled() || cePlugin.getToolManager() == null) {
            return InteractionResult.PASS;
        }

        if (!bukkitPlayer.hasPermission("cearcheology.play.brush")) {
            return InteractionResult.PASS;
        }

        UUID playerId = bukkitPlayer.getUniqueId();
        boolean isMainHandTool = cePlugin.getToolManager().isArcheologyTool(mainHand);
        boolean isOffHandTool = cePlugin.getToolManager().isArcheologyTool(offHand);
        
        debug(() -> "useOnBlock: player=" + bukkitPlayer.getName() + ", mainHand=" + mainHand.getType() + "(isTool=" + isMainHandTool + "), offHand=" + offHand.getType() + "(isTool=" + isOffHandTool + ")");
        
        if (!isMainHandTool && !isOffHandTool) {
            return InteractionResult.PASS;
        }

        // CraftEngine already resolved the hand for this exact interaction. Do not use the
        // packet listener's recent-hand cache here: an unrelated preceding click can otherwise
        // select the wrong brush when both hands hold valid tools.
        EquipmentSlot brushHand = context.getHand() == InteractionHand.OFF_HAND
            ? EquipmentSlot.OFF_HAND
            : EquipmentSlot.HAND;

        if (brushHand == EquipmentSlot.HAND && !isMainHandTool
            || brushHand == EquipmentSlot.OFF_HAND && !isOffHandTool) {
            return InteractionResult.PASS;
        }

        ItemStack activeBrush = brushHand == EquipmentSlot.OFF_HAND ? offHand : mainHand;
        // activeBrush is always mainHand or offHand, so reuse the checks above instead of re-reading NBT.
        boolean activeIsTool = brushHand == EquipmentSlot.OFF_HAND ? isOffHandTool : isMainHandTool;
        if (activeBrush.getType() == Material.AIR || !activeIsTool) {
            return InteractionResult.PASS;
        }

        int brushLevel = 1;
        var toolConfig = cePlugin.getToolManager().getToolConfig(activeBrush);
        if (toolConfig != null) {
            brushLevel = toolConfig.brushLevel();
        }
        
        if (brushLevel < requiredBrushLevel) {
            int deniedLevel = brushLevel;
            debug(() -> "useOnBlock: brush level " + deniedLevel + " < required " + requiredBrushLevel);
            return InteractionResult.PASS;
        }
        
        var world = context.getLevel();
        var blockPos = context.getClickedPos();
        var direction = context.getClickedFace();
        
        org.bukkit.World bukkitWorld = (org.bukkit.World) world.platformWorld();
        Block bukkitBlock = bukkitWorld.getBlockAt(blockPos.x, blockPos.y, blockPos.z);
        BlockFace bukkitFace = BlockFace.valueOf(direction.name());
        
        Location blockLoc = bukkitBlock.getLocation();

        if (isBlockLocked(blockLoc)) {
            UUID locker = getBlockLocker(blockLoc);
            if (locker != null && !locker.equals(playerId)) {
                debug(() -> "useOnBlock: block is locked by another player: " + locker);
                return InteractionResult.PASS;
            }
        }
        
        cancelRecovery(bukkitBlock.getLocation());
        
        BrushSession session = activeSessions.get(playerId);

        boolean hadExistingSession = session != null;
        debug(() -> "useOnBlock: checking session for " + bukkitPlayer.getName() + ", existing session: " + hadExistingSession);

        if (session != null) {
            if (!session.isValid(bukkitBlock)) {
                debug("useOnBlock: session invalid, cancelling");
                cancelSession(playerId);
                session = null;
            } else {
                debug("useOnBlock: session valid, refreshing");
                session.refresh();
            }
        } else {
            debug("useOnBlock: starting new session");
            // Region lookup runs once per session instead of once per use packet.
            var hookManager = cePlugin.getProtectionHookManager();
            if (hookManager != null && !hookManager.canBreak(bukkitPlayer, blockLoc)) {
                sendProtectionDenied(bukkitPlayer);
                return InteractionResult.PASS;
            }
            session = startSession(bukkitPlayer, bukkitBlock, bukkitFace, brushHand);
            // The constructor may bail out on a stale block state and mark itself cancelled.
            // Registering it anyway would lock the block forever and leave a dangling
            // blockSessionIndex entry, because cancel() is never called for it.
            if (session == null || session.cancelled) {
                debug("useOnBlock: failed to create session");
                return InteractionResult.PASS;
            }
            lockBlock(blockLoc, playerId);
            activeSessions.put(playerId, session);
            blockSessionIndex.put(new LocationUtils.BlockPositionKey(blockLoc), playerId);
            debug("useOnBlock: session created and stored");
        }
        
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        return InteractionResult.PASS;
    }

    private BrushSession startSession(org.bukkit.entity.Player player, Block block, BlockFace face, EquipmentSlot hand) {
        var plugin = Bukkit.getPluginManager().getPlugin("CEArcheology");
        if (!(plugin instanceof JavaPlugin jp)) {
            debug("startSession: plugin not found or not JavaPlugin");
            return null;
        }
        
        debug(() -> "startSession: creating session for " + player.getName());
        return new BrushSession(player, block, face, hand, jp);
    }

    private void cancelSession(UUID playerId) {
        BrushSession session = activeSessions.remove(playerId);
        if (session != null) {
            session.cancel();
        }
    }

    private static final Map<BlockFace, Vector> DISPLAY_FACE_OFFSETS = Map.of(
        BlockFace.UP, new Vector(0, 1, 0),
        BlockFace.DOWN, new Vector(0, -1, 0),
        BlockFace.NORTH, new Vector(0, 0, -1),
        BlockFace.SOUTH, new Vector(0, 0, 1),
        BlockFace.WEST, new Vector(-1, 0, 0),
        BlockFace.EAST, new Vector(1, 0, 0)
    );

    // The display sits half a block out from the block center, then travels further as progress rises.
    private static Location displayLocation(Location blockCenter, BlockFace face, float outwardDistance) {
        Vector offset = DISPLAY_FACE_OFFSETS.getOrDefault(face, new Vector(0, 1, 0)).clone()
            .multiply(0.5f + outwardDistance);
        return blockCenter.clone().add(offset);
    }

    private static boolean displayRotated(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH;
    }

    private void startRecovery(Block block, int currentProgress, BlockFace face) {
        LocationUtils.BlockPositionKey key = new LocationUtils.BlockPositionKey(block.getLocation());
        cancelRecovery(key);

        RecoveryTask task = new RecoveryTask(block, currentProgress, face);
        recoveryTasks.put(key, task);
        task.start();
    }

    private static void cancelRecovery(LocationUtils.BlockPositionKey key) {
        RecoveryTask task = recoveryTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
    }
    
    private static void cancelRecovery(Location loc) {
        cancelRecovery(new LocationUtils.BlockPositionKey(loc));
    }

    public static void registerListener() {
        var plugin = Bukkit.getPluginManager().getPlugin("CEArcheology");
        if (plugin instanceof JavaPlugin jp) {
            Bukkit.getPluginManager().registerEvents(new BlockBreakListener(), jp);
            Bukkit.getPluginManager().registerEvents(new ChunkUnloadListener(), jp);
            Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), jp);
            Bukkit.getPluginManager().registerEvents(new PlayerStateListener(), jp);
        }
    }

    private class BrushSession {
        private final org.bukkit.entity.Player player;
        private final Block block;
        private final BlockFace brushFace;
        private final EquipmentSlot brushHand;
        private final ItemStack brushItem;
        private final Location location;
        private final org.bukkit.World bukkitWorld;
        private World world;
        private BlockPos pos;
        private ImmutableBlockState state;
        private int progress;
        private boolean cancelled = false;
        private ItemStack lootItem;
        private LootTableManager.LootEntry lootEntry;
        private final List<LootTableManager.LootEntry> lootEntries = new ArrayList<>();
        // Real items rolled from a vanilla loot table, with NBT; empty for custom tables.
        private final List<ItemStack> rewardItems = new ArrayList<>();
        private BrushableBlockEntityController controller;
        private volatile long lastRefreshTime;
        private double currentBrushTime = 0;
        private int totalBrushTime;
        private double brushSpeed = 1.0;
        private int bonusProgress = 0;
        // long plus an explicit "unset" sentinel: holding getTicksLived() in an int overflows on the
        // first subtraction, which makes the throttle always hit and the ray trace never run.
        private long lastLookCheckTick = Long.MIN_VALUE;
        private boolean lastLookCheckResult = true;
        private final ArcheologyRewardResolver rewardResolver;

        public BrushSession(org.bukkit.entity.Player player, Block block, BlockFace face, EquipmentSlot hand, JavaPlugin plugin) {
            debug(() -> "BrushSession: constructor started for " + player.getName());
            this.player = player;
            this.block = block;
            this.brushFace = face;
            this.brushHand = hand != null ? hand : cn.mymc.cearcheology.listener.BrushPacketListener.getBrushHand(player.getUniqueId());
            this.brushItem = this.brushHand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
            this.location = block.getLocation().clone().add(0.5, 0.5, 0.5);
            this.bukkitWorld = block.getWorld();
            this.lastRefreshTime = System.currentTimeMillis();
            this.rewardResolver = new ArcheologyRewardResolver(player, bukkitWorld, location, block, face);
            
            var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
            if (cePlugin != null) {
                var toolConfig = cePlugin.getToolManager().getToolConfig(brushItem);
                if (toolConfig != null) {
                    this.brushSpeed = toolConfig.speed();
                    this.bonusProgress = Math.max(0, toolConfig.bonusProgress());
                }
            }
            
            ImmutableBlockState blockState = getCustomBlockState(block);
            if (blockState == null || blockState.isEmpty()) {
                debug("BrushSession: blockState is null or empty!");
                this.progress = 0;
                this.cancelled = true;
                return;
            }
            
            this.state = blockState;
            debug(() -> "BrushSession: blockState found, progressProperty=" + progressProperty);
            this.progress = state.get(progressProperty);
            this.totalBrushTime = brushTime;
            int effectiveBaseProgress = Math.max(0, progress - bonusProgress);
            this.currentBrushTime = (double) effectiveBaseProgress * totalBrushTime / maxProgress;
            debug(() -> "BrushSession: initial progress=" + progress + ", maxProgress=" + maxProgress + ", totalBrushTime=" + totalBrushTime);
            
            if (cePlugin != null) {
                ItemStack preset = cePlugin.getProgressStorage().getPresetLoot(block.getLocation());
                if (preset != null) {
                    this.lootItem = preset.clone();
                    // Vanilla loot: restore the rolled items, which are mutually exclusive with
                    // custom loot entries.
                    this.rewardItems.addAll(cePlugin.getProgressStorage().getPresetRewardItems(block.getLocation()));
                    if (this.rewardItems.isEmpty()) {
                        this.lootEntries.addAll(LootTableManager.extractLootEntries(this.lootItem));
                        this.lootEntry = !this.lootEntries.isEmpty() ? this.lootEntries.get(0) : LootTableManager.extractLootEntry(this.lootItem);
                    }
                    debug(() -> "BrushSession: Found preset loot: " + lootItem + ", entry: " + lootEntry + ", rewardItems=" + rewardItems.size());
                } else {
                    debug("BrushSession: No preset loot found");
                }
                
                if (this.lootItem == null) {
                    String presetTable = cePlugin.getProgressStorage().getPresetLootTable(block.getLocation());
                    if (presetTable != null) {
                        debug(() -> "BrushSession: Found preset loot table: " + presetTable);
                        this.lootEntries.addAll(rollLootEntriesFromTable(presetTable));
                        this.lootEntry = !this.lootEntries.isEmpty() ? this.lootEntries.get(0) : null;
                        if (this.lootEntry != null) {
                            this.lootItem = rewardResolver.createDisplayItem(this.lootEntries);
                            cePlugin.getProgressStorage().setPresetLoot(block.getLocation(), this.lootItem);
                            debug(() -> "BrushSession: Rolled loot from preset table: " + lootItem);
                        }
                    }
                }
            }
            
            if (lootItem == null && cePlugin != null && lootTable != null) {
                var manager = cePlugin.getLootTableManager();
                String tableId = lootTable.toString();
                if (manager != null && manager.isVanillaLootTable(tableId)) {
                    List<ItemStack> vanilla = manager.rollVanillaLoot(tableId, block.getLocation());
                    if (!vanilla.isEmpty()) {
                        this.rewardItems.addAll(vanilla);
                        this.lootItem = vanilla.get(0).clone();
                        cePlugin.getProgressStorage().setPresetLoot(block.getLocation(), this.lootItem);
                        cePlugin.getProgressStorage().setPresetRewardItems(block.getLocation(), this.rewardItems);
                        debug(() -> "BrushSession: Rolled vanilla loot: " + vanilla.size() + " items");
                    }
                }
            }

            if (lootItem == null) {
                this.lootEntries.addAll(rollLootEntries());
                this.lootEntry = !this.lootEntries.isEmpty() ? this.lootEntries.get(0) : null;
                this.lootItem = rewardResolver.createDisplayItem(this.lootEntries);
                debug(() -> "BrushSession: Rolled new loot: " + lootItem);
                if (this.lootItem != null && cePlugin != null) {
                    cePlugin.getProgressStorage().setPresetLoot(block.getLocation(), this.lootItem);
                    debug("BrushSession: Stored preset loot");
                }
            }
            
            this.controller = controllerAt(block.getLocation(), true);
            if (this.controller != null && lootItem != null && lootItem.getType() != Material.AIR) {
                updateDisplayPosition(true);
            }
            
            if (cancelled) {
                debug("BrushSession: session cancelled, not starting");
            }
        }
        
        public void refresh() {
            this.lastRefreshTime = System.currentTimeMillis();
        }

        private List<LootTableManager.LootEntry> rollLootEntries() {
            if (lootTable == null) return List.of();
            return rollLootEntriesFromTable(lootTable.toString());
        }
        
        private ItemStack rollLootFromTable(String tableId) {
            try {
                var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
                if (cePlugin != null && cePlugin.isEnabled()) {
                    var manager = cePlugin.getLootTableManager();
                    if (manager != null) {
                        return manager.rollLoot(tableId);
                    }
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                    "log-loot-roll-failed", "Failed to roll loot: {table} - {error}",
                    Map.of("table", tableId, "error", String.valueOf(e.getMessage()))));
            }
            return null;
        }
        
        private LootTableManager.LootEntry rollLootEntryFromTable(String tableId) {
            try {
                var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
                if (cePlugin != null && cePlugin.isEnabled()) {
                    var manager = cePlugin.getLootTableManager();
                    if (manager != null) {
                        return manager.rollLootEntry(tableId);
                    }
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                    "log-loot-entry-roll-failed", "Failed to roll loot entry: {table} - {error}",
                    Map.of("table", tableId, "error", String.valueOf(e.getMessage()))));
            }
            return null;
        }

        private List<LootTableManager.LootEntry> rollLootEntriesFromTable(String tableId) {
            try {
                var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
                if (cePlugin != null && cePlugin.isEnabled()) {
                    var manager = cePlugin.getLootTableManager();
                    if (manager != null) {
                        return manager.rollLootEntries(tableId);
                    }
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                    "log-loot-entries-roll-failed", "Failed to roll loot entry list: {table} - {error}",
                    Map.of("table", tableId, "error", String.valueOf(e.getMessage()))));
            }
            return List.of();
        }

        // show=false only moves an already visible display; show=true (re)spawns it with the current item.
        private void updateDisplayPosition(boolean show) {
            if (controller == null) {
                return;
            }
            float progressRatio = getVisualProgressRatio();
            float moveDistance = displayStartOffset + (displayEndOffset - displayStartOffset) * progressRatio;
            Location displayLoc = displayLocation(location, brushFace, moveDistance);

            if (show) {
                controller.showDisplay(lootItem, displayLoc.getX(), displayLoc.getY(), displayLoc.getZ(),
                    displayScale, displayRotated(brushFace));
            } else {
                controller.moveDisplay(displayLoc.getX(), displayLoc.getY(), displayLoc.getZ());
            }
        }

        private void hideDisplay() {
            if (controller != null) {
                controller.hideDisplay();
            }
        }

        public boolean isValid(Block currentBlock) {
            if (cancelled) return false;
            if (!player.isOnline()) return false;
            if (!block.getWorld().equals(currentBlock.getWorld())) return false;
            if (!block.getLocation().equals(currentBlock.getLocation())) return false;
            return true;
        }

        public void tick() {
            if (cancelled) return;

            long elapsed = System.currentTimeMillis() - lastRefreshTime;
            if (elapsed > getSessionTimeout() * 50L) {
                if (progress > 0) {
                    startRecovery(block, progress, brushFace);
                } else {
                    hideDisplay();
                }
                cancel(false);
                return;
            }
            
            boolean stillBrushing = isPlayerStillBrushing();
            if (!stillBrushing) {
                if (progress > 0) {
                    startRecovery(block, progress, brushFace);
                } else {
                    hideDisplay();
                }
                cancel(false);
                return;
            }
            
            lastRefreshTime = System.currentTimeMillis();
            currentBrushTime += getBrushTimeDelta();
            updateDisplayPosition(false);
            
            playBrushAnimation();
            
            int newProgress = calculateProgress();
            if (newProgress != progress) {
                progress = newProgress;
                debug(() -> "tick: progress=" + progress + "/" + maxProgress);
                
                updateProgress();
                playBrushEffects();
            }
            
            if (currentBrushTime >= totalBrushTime) {
                finish();
            }
        }
        
        private int calculateProgress() {
            if (totalBrushTime <= 0 || maxProgress <= 0) {
                return 0;
            }
            int stageTime = getStageTime();
            if (stageTime <= 0) {
                stageTime = 1;
            }
            int brushedProgress = (int) (currentBrushTime / stageTime);
            return Math.min(brushedProgress + Math.max(0, bonusProgress), maxProgress);
        }

        private int getStageTime() {
            return Math.max(1, totalBrushTime / maxProgress);
        }

        private double getBrushTimeDelta() {
            long intervalTicks = getSessionTickInterval();
            return brushSpeed * intervalTicks;
        }
        
        private boolean isPlayerStillBrushing() {
            if (!player.isOnline()) return false;

            // Both checks are needed. A custom brush without a use animation never raises the hand
            // but keeps resending UseItemOn while held; an item with a use animation such as
            // minecraft:brush stops resending packets but does raise the hand. Checking only the
            // sticky flag never ends the first case, checking only the pose cuts off the second.
            boolean usingItem = cn.mymc.cearcheology.listener.BrushPacketListener.isPlayerActivelyUsing(player.getUniqueId())
                || isHoldingBrushUsePose();
            if (!usingItem) {
                return false;
            }

            return isPlayerLookingAtBlock();
        }

        private boolean isHoldingBrushUsePose() {
            if (!player.isHandRaised()) {
                return false;
            }

            ItemStack activeItem = brushHand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

            var plugin = cn.mymc.cearcheology.CEArcheology.getInstance();
            return plugin != null && plugin.getToolManager().isArcheologyTool(activeItem);
        }
        
        private boolean isPlayerLookingAtBlock() {
            long currentTick = player.getTicksLived();
            // currentTick < lastLookCheckTick means ticksLived reset on respawn, so recheck.
            if (lastLookCheckTick != Long.MIN_VALUE
                && currentTick >= lastLookCheckTick
                && currentTick - lastLookCheckTick < LOOK_CHECK_INTERVAL_TICKS) {
                return lastLookCheckResult;
            }

            org.bukkit.Location eyeLoc = player.getEyeLocation();
            org.bukkit.util.RayTraceResult rayTrace = player.getWorld().rayTraceBlocks(
                eyeLoc,
                eyeLoc.getDirection(),
                4.5,
                org.bukkit.FluidCollisionMode.NEVER,
                true
            );
            
            boolean isLookingAtBlock = false;
            if (rayTrace != null) {
                Block hitBlock = rayTrace.getHitBlock();
                isLookingAtBlock = hitBlock != null && hitBlock.getLocation().equals(block.getLocation());
            }

            lastLookCheckTick = currentTick;
            lastLookCheckResult = isLookingAtBlock;
            return isLookingAtBlock;
        }
        
        private void playBrushAnimation() {
            player.swingHand(brushHand);
        }

        private float getVisualProgressRatio() {
            if (totalBrushTime <= 0) {
                return 0.0f;
            }
            double ratio = currentBrushTime / totalBrushTime;
            return (float) Math.max(0.0d, Math.min(1.0d, ratio));
        }

        private void updateProgress() {
            if (world == null) {
                world = (World) BukkitAdaptor.adapt(bukkitWorld);
                pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            }
            
            ImmutableBlockState newState = state.with(progressProperty, progress);
            if (newState != null && !newState.equals(state)) {
                world.setBlockState(pos, newState, BLOCK_UPDATE_FLAG);
                state = newState;
            }
        }

        private void finish() {
            cancelled = true;
            activeSessions.remove(player.getUniqueId());
            blockSessionIndex.remove(new LocationUtils.BlockPositionKey(block.getLocation()));
            unlockBlock(block.getLocation());
            
            rewardResolver.damageBrush(brushItem, brushHand);
            
            var cePlugin = Bukkit.getPluginManager().getPlugin("CEArcheology");
            if (cePlugin instanceof cn.mymc.cearcheology.CEArcheology ce) {
                ce.getProgressStorage().clearLocation(block.getLocation());
            }
            
            if (!rewardItems.isEmpty()) {
                // Vanilla loot table: drop the rolled items directly so their NBT survives.
                rewardResolver.dropItems(rewardItems);
            } else {
                rewardResolver.resolveRewards(lootEntries, lootItem);
            }

            if (experience > 0) {
                Location expLoc = location.clone().add(0, 0.5, 0);
                (bukkitWorld.spawn(expLoc, ExperienceOrb.class)).setExperience(experience);
            }
            
            hideDisplay();
            
            bukkitWorld.getBlockAt(block.getX(), block.getY(), block.getZ()).setType(resultBlock, false);
        }

        public void cancel() {
            cancel(true);
        }
        
        public void cancel(boolean startRecoveryTask) {
            cancelled = true;
            blockSessionIndex.remove(new LocationUtils.BlockPositionKey(block.getLocation()));
            unlockBlock(block.getLocation());
            
            if (progress > 0) {
                saveProgressBeforeRecovery(block, progress);
                if (startRecoveryTask) {
                    startRecovery(block, progress, brushFace);
                }
            } else {
                hideDisplay();
            }
        }
        
        private void saveProgressBeforeRecovery(Block block, int currentProgress) {
            var plugin = Bukkit.getPluginManager().getPlugin("CEArcheology");
            if (plugin instanceof JavaPlugin jp) {
                CEArcheology cePlugin = (CEArcheology) jp;
                ProgressStorage storage = cePlugin.getProgressStorage();
                if (storage != null) {
                    storage.setProgress(block.getLocation(), currentProgress);
                    if (lootItem != null) {
                        storage.setPresetLoot(block.getLocation(), lootItem);
                    }
                }
            }
        }

        private void playBrushEffects() {
            spawnBrushParticle();
            playBrushSound();
        }

        private void spawnBrushParticle() {
            try {
                Particle particle = resolveParticleType();
                
                if (particle == Particle.DUST) {
                    org.bukkit.Color color = particleColor > 0 
                        ? org.bukkit.Color.fromRGB(particleColor)
                        : org.bukkit.Color.fromRGB(139, 90, 43);
                    bukkitWorld.spawnParticle(Particle.DUST, location, particleCount, 0.2, 0.2, 0.2,
                        new Particle.DustOptions(color, 0.8f));
                } else {
                    bukkitWorld.spawnParticle(particle, location, particleCount, 0.2, 0.2, 0.2, 0);
                }
            } catch (Exception e) {
                debug(() -> "Failed to play brush particles: " + e.getMessage());
            }
        }

        private Particle resolveParticleType() {
            if (particleType == null || particleType.isEmpty()) {
                return Particle.DUST;
            }
            
            try {
                return Particle.valueOf(particleType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Particle.DUST;
            }
        }

        private void playBrushSound() {
            try {
                Sound bukkitSound = resolveBrushSound(getRandomSound());
                float pitch = 1.0f + (RANDOM.nextFloat() * 0.2f - 0.1f);
                bukkitWorld.playSound(location, bukkitSound, 0.5f, pitch);
            } catch (Exception e) {
                debug(() -> "Failed to play brush sound: " + e.getMessage());
            }
        }

        private Sound resolveBrushSound(String sound) {
            String soundName = sound.toUpperCase(Locale.ROOT).replace(".", "_");

            if (soundName.equals("GRAVEL") || soundName.equals("BLOCK_GRAVEL_BREAK")) {
                return Sound.BLOCK_GRAVEL_BREAK;
            } else if (soundName.equals("SAND") || soundName.equals("BLOCK_SAND_BREAK")) {
                return Sound.BLOCK_SAND_BREAK;
            } else if (soundName.equals("STONE") || soundName.equals("BLOCK_STONE_BREAK")) {
                return Sound.BLOCK_STONE_BREAK;
            }

            // Sound.valueOf is unusable: org.bukkit.Sound is an enum on 1.21 but an interface on
            // 1.21.3+, so the invokestatic compiled against the enum throws
            // IncompatibleClassChangeError on newer servers. Registry lookup works on both.
            Sound resolved = lookupSound(sound.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                resolved = lookupSound(soundName.toLowerCase(Locale.ROOT).replace('_', '.'));
            }
            return resolved != null ? resolved : Sound.BLOCK_GRAVEL_BREAK;
        }

        private Sound lookupSound(String id) {
            try {
                NamespacedKey key = id.indexOf(':') >= 0
                    ? NamespacedKey.fromString(id)
                    : NamespacedKey.minecraft(id);
                return key == null ? null : Registry.SOUNDS.get(key);
            } catch (Throwable t) {
                return null;
            }
        }

        private String getRandomSound() {
            if (brushSounds.isEmpty()) {
                return "gravel";
            }
            return brushSounds.get(RANDOM.nextInt(brushSounds.size()));
        }
    }

    private class RecoveryTask {
        private final Block block;
        private final int startProgress;
        private final BlockFace face;
        private final BrushableBlockEntityController controller;
        // Block and world never change during the task, so cache the CE world and BlockPos instead of
        // adapting and allocating them on every recovery tick.
        private final World ceWorld;
        private final BlockPos pos;
        private int currentProgress;
        private long nextRunTick;
        private boolean cancelled = false;

        public RecoveryTask(Block block, int startProgress, BlockFace face) {
            this.block = block;
            this.startProgress = startProgress;
            this.currentProgress = startProgress;
            this.face = face;
            this.controller = controllerAt(block.getLocation(), true);
            this.ceWorld = (World) BukkitAdaptor.adapt(block.getWorld());
            this.pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        }

        public void start() {
            nextRunTick = recoveryTickerTicks + Math.max(1L, getRecoveryDelay());
        }

        public void tickIfDue(long currentTick) {
            if (cancelled || currentTick < nextRunTick) {
                return;
            }
            try {
                tick();
            } catch (Throwable t) {
                // Letting it throw without advancing nextRunTick would rethrow every tick and starve
                // the recovery tasks that come later in the iteration.
                Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                    "log-recovery-task-error", "Recovery task failed and was cancelled: {error}",
                    Map.of("error", String.valueOf(t))));
                cancel();
                return;
            }
            if (!cancelled) {
                nextRunTick = currentTick + Math.max(1L, getRecoveryInterval());
            }
        }

        public void tick() {
            currentProgress--;
            
            if (currentProgress <= 0) {
                completeRecovery();
                return;
            }
            
            updateProgress();
            updateDisplayPosition();
        }

        // Property equality is identity based, so suspicious_stone's brush_progress is not
        // suspicious_dirt's: if the position was replaced with another custom block during the
        // recovery window (WorldEdit paste, /cea place, anything that fires no BlockBreakEvent),
        // with() would throw.
        private boolean ownsState(ImmutableBlockState state) {
            return state != null
                && !state.isEmpty()
                && brushableBehavior(state) == BrushableBlockBehavior.this;
        }

        private void updateProgress() {
            ImmutableBlockState state = getCustomBlockState(block);
            if (!ownsState(state)) {
                cancel();
                return;
            }

            ImmutableBlockState newState = state.with(progressProperty, currentProgress);
            if (newState != null) {
                ceWorld.setBlockState(pos, newState, BLOCK_UPDATE_FLAG);
            }
        }

        private void updateDisplayPosition() {
            if (controller == null) {
                return;
            }

            float progressRatio = (float) currentProgress / maxProgress;
            float moveDistance = displayStartOffset + (displayEndOffset - displayStartOffset) * progressRatio;
            Location location = displayLocation(
                block.getLocation().clone().add(0.5, 0.5, 0.5),
                face,
                moveDistance
            );
            controller.moveDisplay(location.getX(), location.getY(), location.getZ());
        }

        private void completeRecovery() {
            cancel();
            
            ImmutableBlockState state = getCustomBlockState(block);
            if (ownsState(state)) {
                ImmutableBlockState newState = state.with(progressProperty, 0);
                if (newState != null) {
                    ceWorld.setBlockState(pos, newState, BLOCK_UPDATE_FLAG);
                }
            }
            
            // Keep preset loot in storage so the same block can continue using it after recovery.
            
            if (controller != null) {
                controller.hideDisplay();
            }
            
            recoveryTasks.remove(new LocationUtils.BlockPositionKey(block.getLocation()));
        }

        public void cancel() {
            cancel(true);
        }
        
        public void cancel(boolean removeDisplay) {
            cancelled = true;
            recoveryTasks.remove(new LocationUtils.BlockPositionKey(block.getLocation()));
            
            if (currentProgress > 0) {
                var plugin = Bukkit.getPluginManager().getPlugin("CEArcheology");
                if (plugin instanceof JavaPlugin jp) {
                    CEArcheology cePlugin = (CEArcheology) jp;
                    ProgressStorage storage = cePlugin.getProgressStorage();
                    if (storage != null) {
                        storage.setProgress(block.getLocation(), currentProgress);
                    }
                }
            }
            
            if (removeDisplay && controller != null) {
                controller.hideDisplay();
            }
        }
    }

    private static class BlockBreakListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onBrushBreakAttempt(BlockBreakEvent event) {
            Block block = event.getBlock();
            Location loc = block.getLocation();
            
            ImmutableBlockState state = getCustomBlockState(block);
            if (!isBrushableState(state)) return;
            
            org.bukkit.entity.Player player = event.getPlayer();
            UUID playerId = player.getUniqueId();
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();
            EquipmentSlot brushHand = cn.mymc.cearcheology.listener.BrushPacketListener.getBrushHand(playerId);
            ItemStack activeBrush = brushHand == EquipmentSlot.OFF_HAND ? offHand : mainHand;
            boolean isArcheologyTool = cePlugin != null
                && cn.mymc.cearcheology.listener.BrushPacketListener.isPlayerUsingItem(playerId)
                && cePlugin.getToolManager().isArcheologyTool(activeBrush);
            
            if (isArcheologyTool) {
                debug("BlockBreakListener: player using archeology tool, cancelling break");
                event.setCancelled(true);
                org.bukkit.block.data.BlockData blockData = block.getBlockData();
                player.sendBlockChange(loc, blockData);
                return;
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onConfirmedBlockBreak(BlockBreakEvent event) {
            Block block = event.getBlock();
            Location loc = block.getLocation();

            ImmutableBlockState state = getCustomBlockState(block);
            BrushableBlockBehavior behavior = brushableBehavior(state);
            if (behavior == null) return;

            ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
            var cePlugin = cn.mymc.cearcheology.CEArcheology.getInstance();

            UUID sessionPlayerId = blockSessionIndex.remove(new LocationUtils.BlockPositionKey(loc));
            if (sessionPlayerId != null) {
                BrushSession session = activeSessions.remove(sessionPlayerId);
                if (session != null) {
                    session.cancel();
                }
                unlockBlock(loc);
            }
            
            cancelRecovery(loc);
            
            BrushableBlockEntityController breakController = controllerAt(loc, false);
            if (breakController != null) {
                breakController.hideDisplay();
            }
            IntegerProperty progressProp = behavior.progressProperty();
            Material dropBlockVal = behavior.dropBlock();

            if (progressProp == null) return;
            
            // The block is gone, so the preset reward must be cleared whichever drop path follows.
            if (cePlugin != null) {
                cePlugin.getProgressStorage().clearLocation(loc);
            }

            if (state.settings().requireCorrectTool()) {
                // Main hand only: vanilla dropResources and CraftEngine's GetDropsInterceptor both
                // use LootContextParams.TOOL, so counting the off hand would let empty hand plus an
                // off-hand pickaxe pass this gate but fail CE's, handing out free drops.
                var wrappedTool = net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(mainHand);
                boolean isCorrectTool = net.momirealms.craftengine.bukkit.util.BlockStateUtils.isCorrectTool(state, wrappedTool);
                if (!isCorrectTool) {
                    // Wrong tool: hand the drop decision back to CraftEngine/vanilla. Suppressing
                    // drops without replacing them would destroy the block for nothing.
                    return;
                }
            }

            // Must be read before setDropItems(false), otherwise we read back our own write.
            // Creative mode, and any lower-priority plugin that already cleared drops (anti-cheat,
            // land claims), must not gain items just because we take over dropping.
            boolean shouldDrop = event.isDropItems()
                && event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE;

            event.setDropItems(false);
            event.setExpToDrop(0);

            if (!shouldDrop) {
                return;
            }

            // Vanilla suspicious blocks drop themselves at any brush stage; gating on progress would
            // make the drop vanish once bonus-progress > 0 pushes a block to full progress early.
            if (dropBlockVal != null && dropBlockVal != Material.AIR) {
                Location dropLoc = block.getLocation().clone().add(0.5, 0.5, 0.5);
                block.getWorld().dropItemNaturally(dropLoc, new ItemStack(dropBlockVal));
            }

            int breakExp = behavior.breakExperience();
            if (breakExp > 0) {
                Location expLoc = block.getLocation().clone().add(0.5, 0.5, 0.5);
                block.getWorld().spawn(expLoc, ExperienceOrb.class).setExperience(breakExp);
            }
        }
    }

    private static class ChunkUnloadListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onChunkUnload(ChunkUnloadEvent event) {
            org.bukkit.Chunk chunk = event.getChunk();
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            org.bukkit.World chunkWorld = chunk.getWorld();
            
            for (Map.Entry<LocationUtils.BlockPositionKey, RecoveryTask> entry : recoveryTasks.entrySet()) {
                if (!isInChunk(entry.getKey(), chunkWorld, chunkX, chunkZ)) {
                    continue;
                }
                RecoveryTask task = entry.getValue();
                if (task != null) {
                    // Save progress without restarting the recovery task.
                    task.cancel(false);
                }
            }
            
            for (Map.Entry<UUID, BrushSession> entry : activeSessions.entrySet()) {
                BrushSession session = entry.getValue();
                if (session == null) {
                    continue;
                }
                LocationUtils.BlockPositionKey blockKey = new LocationUtils.BlockPositionKey(session.block.getLocation());
                if (!isInChunk(blockKey, chunkWorld, chunkX, chunkZ)) {
                    continue;
                }
                session.cancel(false);
                activeSessions.remove(entry.getKey(), session);
            }
        }
    }

    private static class PlayerQuitListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            org.bukkit.entity.Player player = event.getPlayer();
            UUID playerId = player.getUniqueId();
            
            BrushSession session = activeSessions.remove(playerId);
            if (session != null) {
                session.cancel();
            }

            lastProtectionDenyMessage.remove(playerId);
            cn.mymc.cearcheology.listener.BrushPacketListener.clearPlayer(playerId);
        }
    }
    
    private static class PlayerStateListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
            cn.mymc.cearcheology.listener.BrushPacketListener.clearPlayer(event.getEntity().getUniqueId());
        }
        
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
            cn.mymc.cearcheology.listener.BrushPacketListener.clearPlayer(event.getPlayer().getUniqueId());
        }
        
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
            cn.mymc.cearcheology.listener.BrushPacketListener.clearPlayer(event.getPlayer().getUniqueId());
        }
    }

    private static class Factory implements BlockBehaviorFactory<BrushableBlockBehavior> {
        @Override
        public BrushableBlockBehavior create(BlockDefinition block, ConfigSection section) {
            debug(() -> "Factory.create called for block: " + block.id());
            
            IntegerProperty progressProp = (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "brush_progress", Integer.class);
            
            debug("Found brush_progress property");
            
            int max = section.getInt("max-progress", 4);
            // max-progress must stay inside the range declared by the brush_progress property, or
            // ImmutableBlockState.with throws on an out-of-range value and breaks the ticker pass.
            if (progressProp != null) {
                int clamped = Math.min(Math.max(max, progressProp.min + 1), progressProp.max);
                if (clamped != max) {
                    Bukkit.getLogger().warning("[CEArcheology] " + cn.mymc.cearcheology.locale.LanguageManager.log(
                        "log-max-progress-clamped",
                        "max-progress={value} of {block} is outside the brush_progress range {min}~{max}, corrected to {clamped}.",
                        Map.of("block", String.valueOf(block.id()),
                            "value", String.valueOf(max),
                            "min", String.valueOf(progressProp.min),
                            "max", String.valueOf(progressProp.max),
                            "clamped", String.valueOf(clamped))));
                    max = clamped;
                }
            }

            int brushTimeTicks = section.getInt("brush-time", 96);
            
            int requiredBrushLevel = section.getInt("required-brush-level", 1);
            
            Key loot = section.getKey("loot-table");
            
            Material resultBlock = parseMaterial(section.getString("result-block"));
            
            Material dropBlock = resultBlock;
            String dropBlockId = section.getString("drop-block");
            if (dropBlockId != null) {
                dropBlock = parseMaterial(dropBlockId);
            }
            
            int experience = section.getInt("experience", 0);
            
            int breakExperience = section.getInt("break-experience", 0);
            
            List<String> sounds = new ArrayList<>();
            if (section.containsKey("brush-sounds")) {
                sounds.addAll(section.getStringList("brush-sounds"));
            } else {
                String brushSound = section.getString("brush-sound");
                if (brushSound != null && !brushSound.isBlank()) {
                    sounds.add(brushSound);
                }
            }
            if (sounds.isEmpty()) {
                sounds.add("gravel");
            }
            
            String particle = section.getString("particle");
            
            int particleCount = section.getInt("particle-count", 8);
            
            int particleColor = parseColor(section.get("particle-color"));
            
            ConfigManager config = ConfigManager.getInstance();
            float displayStartOffset = config != null ? config.getDefaultDisplayStartOffset() : 0.03f;
            displayStartOffset = section.getFloat("display-start-offset", displayStartOffset);
            
            float displayEndOffset = config != null ? config.getDefaultDisplayEndOffset() : 0.20f;
            displayEndOffset = section.getFloat("display-end-offset", displayEndOffset);
            
            float displayScale = config != null ? config.getDefaultDisplayScale() : 0.5f;
            displayScale = section.getFloat("display-scale", displayScale);
            
            return new BrushableBlockBehavior(block, progressProp, max, brushTimeTicks, requiredBrushLevel, loot, resultBlock, dropBlock, experience, breakExperience, sounds, particle, particleCount, particleColor, displayStartOffset, displayEndOffset, displayScale);
        }

        private Material parseMaterial(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            Material material = Material.matchMaterial(value);
            if (material == null && value.toLowerCase().startsWith("minecraft:")) {
                material = Material.matchMaterial(value.substring("minecraft:".length()));
            }
            return material;
        }

        private int parseColor(Object value) {
            if (value instanceof Number num) {
                return num.intValue();
            }
            if (value instanceof String str) {
                try {
                    return Integer.decode(str);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }
}
