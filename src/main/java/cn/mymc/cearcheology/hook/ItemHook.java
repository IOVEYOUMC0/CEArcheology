package cn.mymc.cearcheology.hook;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.locale.LanguageManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class ItemHook {

    private static volatile ItemHook instance;
    private static final Object LOCK = new Object();
    
    private final CEArcheology plugin;
    private final Map<String, ItemProvider> providers = new ConcurrentHashMap<>();
    
    private static final Map<String, MethodCache> methodCaches = new ConcurrentHashMap<>();

    // computeIfAbsent stores nothing when the mapping function returns null, so a failed init
    // would re-run reflection and re-spam the warning on every loot roll. This sentinel caches
    // the negative result; callers treat it as "no provider".
    private static final MethodCache UNAVAILABLE = new MethodCache(Object.class, new Method[0]);

    public ItemHook(CEArcheology plugin) {
        this.plugin = plugin;
        synchronized (LOCK) {
            instance = this;
        }
        registerDefaultProviders();
    }

    private void registerDefaultProviders() {
        registerProvider("craftengine", (itemId, count) -> {
            if (!itemId.contains(":")) return null;
            String[] parts = itemId.split(":");
            if (parts.length != 2) return null;
            Key key = Key.of(parts[0], parts[1]);
            BukkitItemDefinition customItem = CraftEngineItems.byId(key);
            if (customItem != null) {
                ItemStack item = customItem.buildBukkitItem();
                item.setAmount(count);
                return item;
            }
            return null;
        });
        
        registerProvider("minecraft", (itemId, count) -> {
            Material material = Material.matchMaterial(itemId);
            if (material != null) {
                return new ItemStack(material, count);
            }
            return null;
        });
        
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            registerProvider("itemsadder", (itemId, count) -> {
                try {
                    MethodCache cache = methodCaches.computeIfAbsent("itemsadder", k -> {
                        try {
                            Class<?> itemsAdderClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                            Method getInstanceMethod = itemsAdderClass.getMethod("getInstance", String.class);
                            Method getItemStackMethod = itemsAdderClass.getMethod("getItemStack");
                            return new MethodCache(itemsAdderClass, getInstanceMethod, getItemStackMethod);
                        } catch (Exception e) {
                            plugin.getLogger().warning(LanguageManager.log("log-item-hook-init-failed",
                                "{plugin} hook initialization failed: {reason}",
                                Map.of("plugin", "ItemsAdder", "reason", String.valueOf(e.getMessage()))));
                            return UNAVAILABLE;
                        }
                    });

                    if (cache == null || cache == UNAVAILABLE) return null;
                    
                    Object customStack = cache.getMethod(1).invoke(null, itemId);
                    if (customStack != null) {
                        Object itemStack = cache.getMethod(2).invoke(customStack);
                        if (itemStack instanceof ItemStack item) {
                            item.setAmount(count);
                            return item;
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(LanguageManager.log("log-item-hook-failed",
                        "{plugin} hook failed: {reason}",
                        Map.of("plugin", "ItemsAdder", "reason", String.valueOf(e.getMessage()))));
                }
                return null;
            });
            plugin.getLogger().info(LanguageManager.log("log-item-hook-registered",
                "Hooked into {plugin}", Map.of("plugin", "ItemsAdder")));
        }
        
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
            registerProvider("oraxen", (itemId, count) -> {
                try {
                    MethodCache cache = methodCaches.computeIfAbsent("oraxen", k -> {
                        try {
                            Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
                            Method getItemByIdMethod = oraxenItemsClass.getMethod("getItemById", String.class);
                            return new MethodCache(oraxenItemsClass, getItemByIdMethod);
                        } catch (Exception e) {
                            plugin.getLogger().warning(LanguageManager.log("log-item-hook-init-failed",
                                "{plugin} hook initialization failed: {reason}",
                                Map.of("plugin", "Oraxen", "reason", String.valueOf(e.getMessage()))));
                            return UNAVAILABLE;
                        }
                    });

                    if (cache == null || cache == UNAVAILABLE) return null;
                    
                    // OraxenItems.getItemById returns an ItemBuilder, never an Optional.
                    // Testing for Optional made this provider always return null, so an
                    // oraxen: loot entry silently fell through to a stone block.
                    Object builder = cache.getMethod(1).invoke(null, itemId);
                    if (builder != null) {
                        Object built = builder.getClass().getMethod("build").invoke(builder);
                        if (built instanceof ItemStack itemStack) {
                            itemStack.setAmount(count);
                            return itemStack;
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(LanguageManager.log("log-item-hook-failed",
                        "{plugin} hook failed: {reason}",
                        Map.of("plugin", "Oraxen", "reason", String.valueOf(e.getMessage()))));
                }
                return null;
            });
            plugin.getLogger().info(LanguageManager.log("log-item-hook-registered",
                "Hooked into {plugin}", Map.of("plugin", "Oraxen")));
        }
        
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            registerProvider("mythicmobs", (itemId, count) -> {
                try {
                    MethodCache cache = methodCaches.computeIfAbsent("mythicmobs", k -> {
                        try {
                            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                            Method instMethod = mythicBukkitClass.getMethod("inst");
                            Method getItemManagerMethod = mythicBukkitClass.getMethod("getItemManager");
                            return new MethodCache(mythicBukkitClass, instMethod, getItemManagerMethod);
                        } catch (Exception e) {
                            plugin.getLogger().warning(LanguageManager.log("log-item-hook-init-failed",
                                "{plugin} hook initialization failed: {reason}",
                                Map.of("plugin", "MythicMobs", "reason", String.valueOf(e.getMessage()))));
                            return UNAVAILABLE;
                        }
                    });

                    if (cache == null || cache == UNAVAILABLE) return null;
                    
                    Object mythicInstance = cache.getMethod(1).invoke(null);
                    Object itemManager = cache.getMethod(2).invoke(mythicInstance);
                    // ItemManager.getItemStack returns an ItemStack directly, not an Optional.
                    Method getItemStackMethod = itemManager.getClass().getMethod("getItemStack", String.class);
                    Object mythicItem = getItemStackMethod.invoke(itemManager, itemId);
                    if (mythicItem instanceof ItemStack itemStack) {
                        itemStack.setAmount(count);
                        return itemStack;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(LanguageManager.log("log-item-hook-failed",
                        "{plugin} hook failed: {reason}",
                        Map.of("plugin", "MythicMobs", "reason", String.valueOf(e.getMessage()))));
                }
                return null;
            });
            plugin.getLogger().info(LanguageManager.log("log-item-hook-registered",
                "Hooked into {plugin}", Map.of("plugin", "MythicMobs")));
        }
        
        if (Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            registerProvider("mmoitems", (itemId, count) -> {
                try {
                    String[] parts = itemId.split(":");
                    if (parts.length != 2) return null;
                    
                    MethodCache cache = methodCaches.computeIfAbsent("mmoitems", k -> {
                        try {
                            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
                            return new MethodCache(mmoItemsClass, new Method[0]);
                        } catch (Exception e) {
                            plugin.getLogger().warning(LanguageManager.log("log-item-hook-init-failed",
                                "{plugin} hook initialization failed: {reason}",
                                Map.of("plugin", "MMOItems", "reason", String.valueOf(e.getMessage()))));
                            return UNAVAILABLE;
                        }
                    });

                    if (cache == null || cache == UNAVAILABLE) return null;
                    
                    // MMOItems.plugin.getItem(String type, String id) is the documented entry
                    // point; going through Type.get + ItemManager.getItem(Type, String) added a
                    // reflective hop that breaks whenever either signature moves.
                    Class<?> mmoItemsClass = cache.getTargetClass();
                    Object pluginObj = mmoItemsClass.getField("plugin").get(null);
                    Object item = pluginObj.getClass()
                        .getMethod("getItem", String.class, String.class)
                        .invoke(pluginObj, parts[0], parts[1]);
                    if (item instanceof ItemStack itemStack) {
                        itemStack.setAmount(count);
                        return itemStack;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(LanguageManager.log("log-item-hook-failed",
                        "{plugin} hook failed: {reason}",
                        Map.of("plugin", "MMOItems", "reason", String.valueOf(e.getMessage()))));
                }
                return null;
            });
            plugin.getLogger().info(LanguageManager.log("log-item-hook-registered",
                "Hooked into {plugin}", Map.of("plugin", "MMOItems")));
        }
        
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            registerProvider("nexo", (itemId, count) -> {
                try {
                    MethodCache cache = methodCaches.computeIfAbsent("nexo", k -> {
                        try {
                            // Nexo's public API class is com.nexomc.nexo.api.NexoItems and the
                            // lookup is itemFromId; the previous names resolved to nothing, so
                            // this provider was permanently dead.
                            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");
                            Method itemFromIdMethod = nexoItemsClass.getMethod("itemFromId", String.class);
                            return new MethodCache(nexoItemsClass, itemFromIdMethod);
                        } catch (Exception e) {
                            plugin.getLogger().warning(LanguageManager.log("log-item-hook-init-failed",
                                "{plugin} hook initialization failed: {reason}",
                                Map.of("plugin", "Nexo", "reason", String.valueOf(e.getMessage()))));
                            return UNAVAILABLE;
                        }
                    });

                    if (cache == null || cache == UNAVAILABLE) return null;
                    
                    Object builder = cache.getMethod(1).invoke(null, itemId);
                    if (builder != null) {
                        Object built = builder.getClass().getMethod("build").invoke(builder);
                        if (built instanceof ItemStack item) {
                            item.setAmount(count);
                            return item;
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(LanguageManager.log("log-item-hook-failed",
                        "{plugin} hook failed: {reason}",
                        Map.of("plugin", "Nexo", "reason", String.valueOf(e.getMessage()))));
                }
                return null;
            });
            plugin.getLogger().info(LanguageManager.log("log-item-hook-registered",
                "Hooked into {plugin}", Map.of("plugin", "Nexo")));
        }
    }

    public void registerProvider(String namespace, ItemProvider provider) {
        providers.put(namespace.toLowerCase(), provider);
    }

    public ItemStack getItem(String itemId, int count) {
        if (itemId == null || itemId.isEmpty()) return null;
        
        if (itemId.contains(":")) {
            String[] parts = itemId.split(":", 2);
            if (parts.length == 2) {
                String namespace = parts[0].toLowerCase();
                String id = parts[1];
                
                ItemProvider provider = providers.get(namespace);
                if (provider != null) {
                    ItemStack item = provider.create(id, count);
                    if (item != null) return item;
                }
                
                ItemProvider ceProvider = providers.get("craftengine");
                if (ceProvider != null) {
                    ItemStack item = ceProvider.create(itemId, count);
                    if (item != null) return item;
                }
            }
        }
        
        Material material = Material.matchMaterial(itemId);
        if (material != null) {
            return new ItemStack(material, count);
        }
        
        return null;
    }

    public static ItemHook getInstance() {
        return instance;
    }
    
    public static void clearCache() {
        synchronized (LOCK) {
            instance = null;
            methodCaches.clear();
        }
    }

    @FunctionalInterface
    public interface ItemProvider {
        ItemStack create(String itemId, int count);
    }
    
    private static class MethodCache {
        private final Class<?> targetClass;
        private final Method[] methods;
        private final Class<?>[] extraClasses;
        
        public MethodCache(Class<?> targetClass, Method... methods) {
            this.targetClass = targetClass;
            this.methods = methods;
            this.extraClasses = new Class<?>[0];
        }
        
        public MethodCache(Class<?> targetClass, Class<?>... extraClasses) {
            this.targetClass = targetClass;
            this.methods = new Method[0];
            this.extraClasses = extraClasses;
        }
        
        public Class<?> getTargetClass() {
            return targetClass;
        }
        
        public Method getMethod(int index) {
            return methods[index - 1];
        }
        
        public Class<?> getExtraClass(int index) {
            return extraClasses[index];
        }
    }
}
