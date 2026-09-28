package cn.mymc.cearcheology.listener;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.locale.LanguageManager;
import cn.mymc.cearcheology.manager.BrushInteractionTracker;
import cn.mymc.cearcheology.util.DebugUtils;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.plugin.network.event.ByteBufPacketEvent;
import net.momirealms.craftengine.core.plugin.network.listener.ByteBufferPacketListener;
import net.momirealms.craftengine.core.plugin.network.listener.ByteBufferPacketListenerHolder;
import net.momirealms.craftengine.core.util.FriendlyByteBuf;
import org.bukkit.inventory.EquipmentSlot;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BrushPacketListener {

    private static final String LISTENER_PREFIX = "CEArcheology:";
    private static final int RELEASE_USE_ITEM = 5;

    private final CEArcheology plugin;
    private final Map<Integer, ByteBufferPacketListenerHolder> originalListeners = new LinkedHashMap<>();
    private ByteBufferPacketListenerHolder[] serverboundPlayListeners;
    private boolean registered;

    public BrushPacketListener(CEArcheology plugin) {
        this.plugin = plugin;
    }

    public static void clearDebugCache() {
        DebugUtils.clearCache();
    }

    private static void debug(String message) {
        DebugUtils.debug(message);
    }

    private static void debug(java.util.function.Supplier<String> message) {
        DebugUtils.debug(message);
    }

    public void register() {
        if (registered) {
            return;
        }

        // Hooking CE's packet listeners relies on its internals via reflection. On an incompatible
        // CE version we degrade instead of crashing: brushing falls back to hand-raise detection.
        try {
            serverboundPlayListeners = serverboundPlayListeners();
            wrapListener(
                BukkitNetworkManager.PACKET_IDS.serverboundUseItemOnPacket(),
                "ServerboundUseItemOnPacket",
                this::handleUseItemOn
            );
            wrapListener(
                BukkitNetworkManager.PACKET_IDS.serverboundPlayerActionPacket(),
                "ServerboundPlayerActionPacket",
                this::handlePlayerAction
            );
            registered = true;
        } catch (Throwable t) {
            // If the second wrapListener throws, the first wrapper is already in CraftEngine's
            // static table; without restoring it stays there forever and unregister can't reach it.
            restoreOriginals();
            serverboundPlayListeners = null;
            originalListeners.clear();
            registered = false;
            plugin.getLogger().warning(LanguageManager.log("log-packet-hook-failed",
                "Failed to hook CraftEngine packet listeners (incompatible CraftEngine version?); "
                    + "brushing falls back to less accurate hand-raise detection. Reason: {reason}",
                Map.of("reason", String.valueOf(t.getMessage()))));
        }
    }

    // /ce reload runs BukkitBlockManager.delayedLoad -> registerBlockStatePacketListeners, which
    // writes CE's own UseItemOn/PlayerAction listeners back into the slots, silently overwriting
    // our wrappers. register() early-returns on the registered flag, so without re-attaching we
    // would degrade to hand-raise detection permanently. wrapListener skips our own wrappers, so
    // this is idempotent.
    public void reattach() {
        if (!registered || serverboundPlayListeners == null) {
            register();
            return;
        }

        try {
            wrapListener(
                BukkitNetworkManager.PACKET_IDS.serverboundUseItemOnPacket(),
                "ServerboundUseItemOnPacket",
                this::handleUseItemOn
            );
            wrapListener(
                BukkitNetworkManager.PACKET_IDS.serverboundPlayerActionPacket(),
                "ServerboundPlayerActionPacket",
                this::handlePlayerAction
            );
        } catch (Throwable t) {
            plugin.getLogger().warning(LanguageManager.log("log-packet-hook-reattach-failed",
                "Failed to re-hook CraftEngine packet listeners after reload: {reason}",
                Map.of("reason", String.valueOf(t.getMessage()))));
        }
    }

    // Only restore slots still owned by this plugin; another plugin may have wrapped us since.
    private void restoreOriginals() {
        if (serverboundPlayListeners == null) {
            return;
        }
        for (Map.Entry<Integer, ByteBufferPacketListenerHolder> entry : originalListeners.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < serverboundPlayListeners.length) {
                ByteBufferPacketListenerHolder current = serverboundPlayListeners[id];
                if (current != null && current.id().startsWith(LISTENER_PREFIX)) {
                    serverboundPlayListeners[id] = entry.getValue();
                }
            }
        }
    }

    public void unregister() {
        if (!registered || serverboundPlayListeners == null) {
            return;
        }

        restoreOriginals();

        originalListeners.clear();
        serverboundPlayListeners = null;
        registered = false;
    }

    private void wrapListener(int packetId, String packetName, PacketHandler handler) {
        if (packetId < 0 || packetId >= serverboundPlayListeners.length) {
            debug(() -> "CraftEngine packet id unavailable for " + packetName + ": " + packetId);
            return;
        }

        ByteBufferPacketListenerHolder current = serverboundPlayListeners[packetId];
        if (current != null && current.id().startsWith(LISTENER_PREFIX)) {
            return;
        }

        originalListeners.put(packetId, current);
        // CraftEngine keeps one listener per packet id; wrap the existing listener so CE behavior still runs.
        ByteBufferPacketListener delegate = current == null ? null : current.listener();
        ByteBufferPacketListener listener = new ChainedPacketListener(handler, delegate);
        serverboundPlayListeners[packetId] = new ByteBufferPacketListenerHolder(LISTENER_PREFIX + packetName, listener);
    }

    private static ByteBufferPacketListenerHolder[] serverboundPlayListeners() {
        try {
            Field field = BukkitNetworkManager.class.getDeclaredField("c2sPlayPacketListeners");
            field.setAccessible(true);
            return (ByteBufferPacketListenerHolder[]) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access the CraftEngine packet listener table", e);
        }
    }

    private BrushInteractionTracker tracker() {
        return plugin.getBrushInteractionTracker();
    }

    private void handleUseItemOn(NetWorkUser user, ByteBufPacketEvent event) {
        EquipmentSlot slot = toEquipmentSlot(readUseItemOnHand(event));
        tracker().markUsing(user.uuid(), slot);
        debug(() -> "USE_ITEM_ON: player=" + user.name() + ", hand=" + slot);
    }

    private void handlePlayerAction(NetWorkUser user, ByteBufPacketEvent event) {
        int action = readPlayerAction(event);
        if (action == RELEASE_USE_ITEM) {
            clearPlayer(user.uuid());
        }
    }

    private static int readUseItemOnHand(ByteBufPacketEvent event) {
        FriendlyByteBuf buf = event.getBuffer();
        return buf.readVarInt();
    }

    private static int readPlayerAction(ByteBufPacketEvent event) {
        FriendlyByteBuf buf = event.getBuffer();
        return buf.readVarInt();
    }

    private static EquipmentSlot toEquipmentSlot(int hand) {
        return hand == 1 ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
    }

    public static boolean isPlayerUsingItem(UUID playerId) {
        CEArcheology plugin = CEArcheology.getInstance();
        return plugin != null
            && plugin.getBrushInteractionTracker() != null
            && plugin.getBrushInteractionTracker().isUsing(playerId);
    }

    // See BrushInteractionTracker.isActivelyUsing.
    public static boolean isPlayerActivelyUsing(UUID playerId) {
        CEArcheology plugin = CEArcheology.getInstance();
        return plugin != null
            && plugin.getBrushInteractionTracker() != null
            && plugin.getBrushInteractionTracker().isActivelyUsing(playerId);
    }

    public static EquipmentSlot getBrushHand(UUID playerId) {
        CEArcheology plugin = CEArcheology.getInstance();
        if (plugin == null || plugin.getBrushInteractionTracker() == null) {
            return EquipmentSlot.HAND;
        }
        return plugin.getBrushInteractionTracker().getActiveHand(playerId);
    }

    public static EquipmentSlot getRecentBrushHand(UUID playerId) {
        CEArcheology plugin = CEArcheology.getInstance();
        if (plugin == null || plugin.getBrushInteractionTracker() == null) {
            return null;
        }
        return plugin.getBrushInteractionTracker().getRecentHand(playerId);
    }

    public static void clearPlayer(UUID playerId) {
        CEArcheology plugin = CEArcheology.getInstance();
        if (plugin != null && plugin.getBrushInteractionTracker() != null) {
            plugin.getBrushInteractionTracker().clear(playerId);
        }
    }

    public static void clearAll() {
        CEArcheology plugin = CEArcheology.getInstance();
        if (plugin != null && plugin.getBrushInteractionTracker() != null) {
            plugin.getBrushInteractionTracker().clearAll();
        }
    }

    private interface PacketHandler {
        void handle(NetWorkUser user, ByteBufPacketEvent event);
    }

    private static final class ChainedPacketListener implements ByteBufferPacketListener {
        private final PacketHandler beforeDelegate;
        private final ByteBufferPacketListener delegate;

        private ChainedPacketListener(PacketHandler beforeDelegate, ByteBufferPacketListener delegate) {
            this.beforeDelegate = beforeDelegate;
            this.delegate = delegate;
        }

        @Override
        public void onPacketReceive(NetWorkUser user, ByteBufPacketEvent event) {
            FriendlyByteBuf buffer = event.getBuffer();
            int readerIndex = buffer.readerIndex();
            try {
                beforeDelegate.handle(user, event);
            } catch (RuntimeException e) {
                debug(() -> "CEArcheology packet listener failed: " + e.getMessage());
            } finally {
                buffer.readerIndex(readerIndex);
            }
            if (!event.isCancelled() && delegate != null) {
                delegate.onPacketReceive(user, event);
            }
        }

        @Override
        public void onPacketSend(NetWorkUser user, ByteBufPacketEvent event) {
            if (delegate != null) {
                delegate.onPacketSend(user, event);
            }
        }
    }
}
