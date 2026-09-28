package cn.mymc.cearcheology.hook.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

// PlotSquared, via reflection so there is no compile-time dependency.
// Checks the plot at the TARGET block, not adapt(player).getCurrentPlot() as an earlier version
// did: keying off where the player stands lets someone on a road edit blocks inside another
// player's plot, and blocks road/unclaimed-plot edits inside a managed plot area.
public class ProtectionPlotSquaredHook extends AbstractProtectionHook {

    private Method adaptLocationMethod; // BukkitUtil.adapt(org.bukkit.Location) -> PlotSquared Location
    private Method isPlotRoadMethod;    // Location.isPlotRoad() -> boolean
    private Method isPlotAreaMethod;    // Location.isPlotArea() -> boolean
    private Method getPlotMethod;       // Location.getPlot() -> Plot
    private Method isAddedMethod;       // Plot.isAdded(UUID) -> boolean
    private boolean available;

    public ProtectionPlotSquaredHook() {
        super("PlotSquared");
        try {
            Class<?> bukkitUtil = Class.forName("com.plotsquared.bukkit.util.BukkitUtil");
            this.adaptLocationMethod = bukkitUtil.getMethod("adapt", Location.class);
            Class<?> psLocation = Class.forName("com.plotsquared.core.location.Location");
            this.isPlotRoadMethod = psLocation.getMethod("isPlotRoad");
            this.isPlotAreaMethod = psLocation.getMethod("isPlotArea");
            this.getPlotMethod = psLocation.getMethod("getPlot");
            Class<?> plot = Class.forName("com.plotsquared.core.plot.Plot");
            this.isAddedMethod = plot.getMethod("isAdded", UUID.class);
            this.available = true;
        } catch (Throwable t) {
            this.available = false;
        }
    }

    private boolean canModify(Player player, Location location) {
        if (!available || location == null) {
            return true;
        }
        try {
            Object psLocation = adaptLocationMethod.invoke(null, location);
            if (psLocation == null) {
                return true;
            }
            // PlotSquared roads are part of a managed plot area and are not editable by default.
            // Only locations outside PlotSquared-managed areas should be treated as unrestricted.
            if (Boolean.TRUE.equals(isPlotRoadMethod.invoke(psLocation))) {
                return false;
            }
            if (!Boolean.TRUE.equals(isPlotAreaMethod.invoke(psLocation))) {
                return true;
            }
            Object plot = getPlotMethod.invoke(psLocation);
            if (plot == null) {
                // PlotSquared plot area without a claimed plot is not editable.
                return false;
            }
            Object added = isAddedMethod.invoke(plot, player.getUniqueId());
            return !(added instanceof Boolean) || (Boolean) added;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canBreak(Player player, Location location) {
        return canModify(player, location);
    }

    @Override
    public boolean canPlace(Player player, Location location) {
        return canModify(player, location);
    }
}
