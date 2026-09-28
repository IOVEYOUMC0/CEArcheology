package cn.mymc.cearcheology.behavior;

import it.unimi.dsi.fastutil.ints.IntList;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Packet-only item display for a brushing block. Nothing is added to the server's entity list: the
// client is told about an item_display whose id comes from CraftEngine's entity counter, so no real
// entity is saved, ticked, or visible to other plugins. CraftEngine tracks the block entity per
// player and calls show/hide, so the packets only go to players who can see the block.
final class BrushDisplayElement implements BlockEntityElement {

    private final BrushableBlockEntityController controller;
    private final int entityId = EntityUtils.ENTITY_COUNTER.incrementAndGet();
    private final UUID uuid = UUID.randomUUID();
    // Built on first hide. Most archeology blocks are never brushed, and this element exists for
    // every one of them, so the packet is not worth allocating up front.
    private volatile Object removePacket;

    BrushDisplayElement(BrushableBlockEntityController controller) {
        this.controller = controller;
    }

    private Object removePacket() {
        Object packet = this.removePacket;
        if (packet == null) {
            packet = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(this.entityId));
            this.removePacket = packet;
        }
        return packet;
    }

    // Called by CraftEngine when the block's chunk starts being tracked for this player.
    @Override
    public void show(@NotNull Player player) {
        BrushableBlockEntityController.DisplayState state = this.controller.displayState();
        if (state != null) {
            showTo(player, state);
        }
    }

    @Override
    public void hide(@NotNull Player player) {
        player.sendPacket(removePacket(), false);
    }

    @Override
    public void update(@NotNull Player player) {
        show(player);
    }

    // Spawns the display for a player who is already tracking the block.
    void showTo(@NotNull Player player, BrushableBlockEntityController.DisplayState state) {
        player.sendPackets(List.of(spawnPacket(state), metadataPacket(state)), false);
    }

    // Moves an already spawned display. Used while brushing as the item travels out of the block.
    void moveTo(@NotNull Player player, BrushableBlockEntityController.DisplayState state) {
        player.sendPacket(EntityUtils.createUpdatePosPacket(
            this.entityId, state.x(), state.y(), state.z(), 0.0f, 0.0f, false), false);
    }

    void hideFrom(@NotNull Player player) {
        player.sendPacket(removePacket(), false);
    }

    private Object spawnPacket(BrushableBlockEntityController.DisplayState state) {
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
            this.entityId, this.uuid, state.x(), state.y(), state.z(), 0.0f, 0.0f,
            EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0.0d
        );
    }

    private Object metadataPacket(BrushableBlockEntityController.DisplayState state) {
        List<Object> data = new ArrayList<>(4);
        DisplayData.ItemDisplayData.ItemStack.addEntityData(
            BukkitItemManager.instance().wrap(state.item()).minecraftItem(), data);
        DisplayData.Scale.addEntityData(new Vector3f(state.scale(), state.scale(), state.scale()), data);
        DisplayData.LeftRotation.addEntityData(leftRotation(state), data);
        // Billboard 0 = FIXED, the vanilla default for a spawned item_display.
        DisplayData.BillboardConstraints.addEntityData((byte) 0, data);
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(this.entityId, data);
    }

    private static Quaternionf leftRotation(BrushableBlockEntityController.DisplayState state) {
        Quaternionf rotation = new Quaternionf();
        if (state.rotated()) {
            rotation.rotateY((float) Math.toRadians(90.0d));
        }
        return rotation;
    }
}
