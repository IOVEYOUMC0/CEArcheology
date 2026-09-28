package cn.mymc.cearcheology.util;

import net.momirealms.craftengine.core.util.Key;

public final class KeyUtils {

    private KeyUtils() {
    }

    public static Key parseKey(String keyStr) {
        if (keyStr == null) return null;
        String[] parts = keyStr.split(":");
        if (parts.length == 2) {
            return Key.of(parts[0], parts[1]);
        }
        return Key.of("minecraft", keyStr);
    }
}
