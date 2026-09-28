package cn.mymc.cearcheology.manager;

import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BrushInteractionTracker {

    private static final long RECENT_INTERACTION_WINDOW_MILLIS = 400L;

    private final Set<UUID> playersUsingItem = ConcurrentHashMap.newKeySet();
    private final Map<UUID, EquipmentSlot> activeHands = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentHandTimestamps = new ConcurrentHashMap<>();

    public void markUsing(UUID playerId, EquipmentSlot hand) {
        if (playerId == null || hand == null) {
            return;
        }

        playersUsingItem.add(playerId);
        activeHands.put(playerId, hand);
        recentHandTimestamps.put(playerId, System.currentTimeMillis());
    }

    public boolean isUsing(UUID playerId) {
        return playerId != null && playersUsingItem.contains(playerId);
    }

    // isUsing is sticky: it only clears on RELEASE_USE_ITEM, which clients send only for items that
    // have a use animation. A CraftEngine brush built on an animation-less base item would keep the
    // flag set forever and let a brushing session run to completion on its own. Holding right-click
    // resends UseItemOn every few ticks, so the recency window separates "still held" from a stale flag.
    public boolean isActivelyUsing(UUID playerId) {
        if (playerId == null || !playersUsingItem.contains(playerId)) {
            return false;
        }
        Long timestamp = recentHandTimestamps.get(playerId);
        return timestamp != null
            && System.currentTimeMillis() - timestamp <= RECENT_INTERACTION_WINDOW_MILLIS;
    }

    public EquipmentSlot getActiveHand(UUID playerId) {
        if (playerId == null) {
            return EquipmentSlot.HAND;
        }
        return activeHands.getOrDefault(playerId, EquipmentSlot.HAND);
    }

    public EquipmentSlot getRecentHand(UUID playerId) {
        if (playerId == null) {
            return null;
        }

        Long timestamp = recentHandTimestamps.get(playerId);
        if (timestamp == null) {
            return null;
        }
        if (System.currentTimeMillis() - timestamp > RECENT_INTERACTION_WINDOW_MILLIS) {
            recentHandTimestamps.remove(playerId, timestamp);
            return null;
        }
        return activeHands.get(playerId);
    }

    public void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }

        playersUsingItem.remove(playerId);
        activeHands.remove(playerId);
        recentHandTimestamps.remove(playerId);
    }

    public void clearAll() {
        playersUsingItem.clear();
        activeHands.clear();
        recentHandTimestamps.clear();
    }
}
