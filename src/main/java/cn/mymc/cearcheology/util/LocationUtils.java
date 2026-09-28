package cn.mymc.cearcheology.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public final class LocationUtils {

    private static final String SEPARATOR = "|";

    private LocationUtils() {
    }

    public static String toKey(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return loc.getWorld().getName() + SEPARATOR + loc.getBlockX() + SEPARATOR + loc.getBlockY() + SEPARATOR + loc.getBlockZ();
    }

    public static String toKey(String worldName, int x, int y, int z) {
        if (worldName == null) {
            return null;
        }
        return worldName + SEPARATOR + x + SEPARATOR + y + SEPARATOR + z;
    }

    public static Location fromKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int lastSep = key.lastIndexOf(SEPARATOR);
        if (lastSep == -1) {
            return null;
        }
        int secondLastSep = key.lastIndexOf(SEPARATOR, lastSep - 1);
        if (secondLastSep == -1) {
            return null;
        }
        int thirdLastSep = key.lastIndexOf(SEPARATOR, secondLastSep - 1);
        if (thirdLastSep == -1) {
            return null;
        }
        
        String worldName = key.substring(0, thirdLastSep);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(key.substring(thirdLastSep + 1, secondLastSep));
            int y = Integer.parseInt(key.substring(secondLastSep + 1, lastSep));
            int z = Integer.parseInt(key.substring(lastSep + 1));
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String getWorldName(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int sepIndex = key.indexOf(SEPARATOR);
        if (sepIndex == -1) {
            return null;
        }
        return key.substring(0, sepIndex);
    }

    public static boolean isValidLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World world = loc.getWorld();
        double y = loc.getY();
        return y >= world.getMinHeight() && y <= world.getMaxHeight();
    }

    public static boolean isWithinWorldBorder(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World world = loc.getWorld();
        double x = loc.getX();
        double z = loc.getZ();
        return Math.abs(x) <= 30000000 && Math.abs(z) <= 30000000;
    }

    public static class BlockPositionKey {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final int hashCode;

        public BlockPositionKey(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.hashCode = Objects.hash(worldName, x, y, z);
        }

        public BlockPositionKey(Location loc) {
            if (loc == null || loc.getWorld() == null) {
                throw new IllegalArgumentException("Location or world cannot be null");
            }
            this.worldName = loc.getWorld().getName();
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.hashCode = Objects.hash(worldName, x, y, z);
        }

        public String getWorldName() {
            return worldName;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockPositionKey that = (BlockPositionKey) o;
            return x == that.x && y == that.y && z == that.z && Objects.equals(worldName, that.worldName);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return worldName + SEPARATOR + x + SEPARATOR + y + SEPARATOR + z;
        }
    }
}
