package cn.mymc.cearcheology.behavior;

import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Persists the preset loot item and loot table id with the block entity, which CraftEngine saves
// and loads along with the chunk. This replaces the old storage.yml.
// Data lives under the private key cearcheology:brushable so it cannot clash with other controllers.
public class BrushableBlockEntityController extends BlockEntityController {

    private static final String DATA_KEY = "cearcheology:brushable";

    private static volatile Method saveItemAsTagMethod;

    // What the packet display should currently render; null means nothing is shown.
    private final BrushDisplayElement displayElement;
    private volatile DisplayState displayState;

    @Nullable
    private ItemStack lootItem;
    @Nullable
    private String lootTable;
    // Real items rolled from a vanilla loot table, keeping their full NBT; stays empty for custom
    // cearcheology: tables.
    private final List<ItemStack> rewardItems = new ArrayList<>();

    public BrushableBlockEntityController(BlockEntity blockEntity) {
        super(blockEntity);
        this.displayElement = new BrushDisplayElement(this);
    }

    public record DisplayState(ItemStack item, double x, double y, double z, float scale, boolean rotated) {}

    // Claiming an element makes CraftEngine build a dynamic renderer for this block entity, which is
    // what routes show/hide to the element as players start and stop tracking the chunk.
    @Override
    public boolean hasElement() {
        return true;
    }

    @Override
    public void gatherElements(Consumer<BlockEntityElement> consumer) {
        consumer.accept(this.displayElement);
    }

    @Nullable
    public DisplayState displayState() {
        return this.displayState;
    }

    public void showDisplay(ItemStack item, double x, double y, double z, float scale, boolean rotated) {
        if (item == null || item.getType().isAir()) {
            hideDisplay();
            return;
        }
        DisplayState next = new DisplayState(item.clone(), x, y, z, scale, rotated);
        this.displayState = next;
        forEachTrackingPlayer(player -> this.displayElement.showTo(player, next));
    }

    public void moveDisplay(double x, double y, double z) {
        DisplayState current = this.displayState;
        if (current == null) {
            return;
        }
        if (current.x() == x && current.y() == y && current.z() == z) {
            return;
        }
        DisplayState next = new DisplayState(current.item(), x, y, z, current.scale(), current.rotated());
        this.displayState = next;
        forEachTrackingPlayer(player -> this.displayElement.moveTo(player, next));
    }

    public void hideDisplay() {
        if (this.displayState == null) {
            return;
        }
        this.displayState = null;
        forEachTrackingPlayer(this.displayElement::hideFrom);
    }

    private void forEachTrackingPlayer(Consumer<Player> action) {
        if (super.blockEntity.world == null) {
            return;
        }
        CEChunk chunk = super.blockEntity.world.getChunkAtIfLoaded(
            super.blockEntity.pos.x >> 4, super.blockEntity.pos.z >> 4);
        if (chunk == null) {
            return;
        }
        for (Player player : chunk.getTrackedBy()) {
            if (player.isDynamicBlockEntityVisible(super.blockEntity.pos)) {
                action.accept(player);
            }
        }
    }

    public List<ItemStack> getRewardItems() {
        List<ItemStack> copy = new ArrayList<>(rewardItems.size());
        for (ItemStack item : rewardItems) {
            copy.add(item != null ? item.clone() : null);
        }
        return copy;
    }

    public void setRewardItems(@Nullable List<ItemStack> items) {
        this.rewardItems.clear();
        if (items != null) {
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir()) {
                    this.rewardItems.add(item.clone());
                }
            }
        }
        markChanged();
    }

    @Nullable
    public ItemStack getLootItem() {
        return lootItem != null ? lootItem.clone() : null;
    }

    public void setLootItem(@Nullable ItemStack item) {
        this.lootItem = item != null ? item.clone() : null;
        markChanged();
    }

    @Nullable
    public String getLootTable() {
        return lootTable;
    }

    public void setLootTable(@Nullable String lootTable) {
        this.lootTable = lootTable;
        markChanged();
    }

    private void markChanged() {
        if (super.blockEntity.world != null) {
            super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
        }
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        boolean hasItem = lootItem != null && !lootItem.getType().isAir();
        boolean hasTable = lootTable != null && !lootTable.isBlank();
        boolean hasRewards = !rewardItems.isEmpty();
        if (!hasItem && !hasTable && !hasRewards) {
            return;
        }

        CompoundTag data = new CompoundTag();
        data.put("data_version", new IntTag(Config.itemDataFixerUpperFallbackVersion()));
        if (hasItem) {
            Tag itemTag = saveBukkitItemAsTag(lootItem);
            if (itemTag != null) {
                data.put("loot_item", itemTag);
            }
        }
        if (hasTable) {
            data.putString("loot_table", lootTable);
        }
        if (hasRewards) {
            data.put("reward_items", ItemStackUtils.saveBukkitItemsAsListTag(rewardItems.toArray(new ItemStack[0])));
        }
        tag.put(DATA_KEY, data);
    }

    // Reflective on purpose: the return type of ItemStackUtils.saveBukkitItemAsTag narrowed
    // (Tag -> CompoundTag) between CraftEngine versions, and a direct call bakes that type into the
    // bytecode descriptor, throwing NoSuchMethodError on the other version. Resolving by name plus
    // parameter types works with either return type.
    private static Tag saveBukkitItemAsTag(ItemStack item) {
        try {
            Method method = saveItemAsTagMethod;
            if (method == null) {
                method = ItemStackUtils.class.getMethod("saveBukkitItemAsTag", ItemStack.class);
                saveItemAsTagMethod = method;
            }
            return (Tag) method.invoke(null, item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("CraftEngine saveBukkitItemAsTag unavailable", e);
        }
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        this.rewardItems.clear();
        CompoundTag data = tag.getCompound(DATA_KEY);
        if (data == null) {
            this.lootItem = null;
            this.lootTable = null;
            return;
        }

        int dataVersion = data.getInt("data_version", Config.itemDataFixerUpperFallbackVersion());
        Tag itemTag = data.get("loot_item");
        this.lootItem = itemTag != null ? ItemStackUtils.parseBukkitItem(itemTag, dataVersion) : null;
        this.lootTable = data.getString("loot_table");
        if (this.lootTable != null && this.lootTable.isBlank()) {
            this.lootTable = null;
        }

        ListTag rewardList = data.getList("reward_items");
        if (rewardList != null && !rewardList.isEmpty()) {
            ItemStack[] parsed = ItemStackUtils.parseBukkitItems(rewardList, rewardList.size(), dataVersion);
            for (ItemStack item : parsed) {
                if (item != null && !item.getType().isAir()) {
                    this.rewardItems.add(item);
                }
            }
        }
    }
}
