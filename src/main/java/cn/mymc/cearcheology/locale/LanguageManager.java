package cn.mymc.cearcheology.locale;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// Loads languages/<language>.yml and resolves every user- and console-facing string.
// Lookup order: active language file -> bundled defaults for the same language -> caller fallback.
// No display text is hardcoded in Java; the fallbacks here exist only for a corrupt language file.
public final class LanguageManager {

    public static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final String[] BUNDLED_LANGUAGES = {"zh_CN", "en_US"};

    private static volatile LanguageManager instance;

    private final JavaPlugin plugin;
    private String language;
    private FileConfiguration messages;
    private FileConfiguration bundledDefaults;
    private String prefix;

    private LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public static void init(JavaPlugin plugin) {
        if (instance == null) {
            instance = new LanguageManager(plugin);
        } else {
            instance.reload();
        }
    }

    public static LanguageManager getInstance() {
        return instance;
    }

    // Null-safe accessor: falls back to the caller's literal when the manager is not up yet.
    public static String translate(String key, String fallback) {
        LanguageManager mgr = instance;
        return mgr != null ? mgr.get(key, fallback) : color(fallback);
    }

    public static String translate(String key, String fallback, Map<String, String> placeholders) {
        LanguageManager mgr = instance;
        return mgr != null ? mgr.get(key, fallback, placeholders) : color(applyPlaceholders(fallback, placeholders));
    }

    // Same as translate but without the prefix, for fragments embedded into another message
    // and for multi-line blocks such as the help banner.
    public static String translateRaw(String key, String fallback) {
        LanguageManager mgr = instance;
        return mgr != null ? mgr.getRaw(key, fallback) : color(fallback);
    }

    // Console/log text: localized, placeholder-substituted, and stripped of colour codes so
    // raw '&' sequences never leak into the server log.
    public static String log(String key, String fallback) {
        return log(key, fallback, null);
    }

    public static String log(String key, String fallback, Map<String, String> placeholders) {
        LanguageManager mgr = instance;
        String raw = mgr != null ? mgr.rawWithFallback(key, fallback) : fallback;
        return ChatColor.stripColor(color(applyPlaceholders(raw, placeholders)));
    }

    public void reload() {
        saveBundledLanguages();

        this.language = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        File file = new File(plugin.getDataFolder(), "languages/" + language + ".yml");
        if (!file.exists()) {
            // Cannot localize this one: it reports that the language file itself is missing.
            plugin.getLogger().warning("Language file not found: " + language
                + ".yml, falling back to " + DEFAULT_LANGUAGE);
            this.language = DEFAULT_LANGUAGE;
            file = new File(plugin.getDataFolder(), "languages/" + DEFAULT_LANGUAGE + ".yml");
        }

        this.messages = YamlConfiguration.loadConfiguration(file);
        this.bundledDefaults = loadBundled(language);
        this.prefix = color(rawWithFallback("prefix", ""));
    }

    private void saveBundledLanguages() {
        for (String lang : BUNDLED_LANGUAGES) {
            File f = new File(plugin.getDataFolder(), "languages/" + lang + ".yml");
            if (!f.exists()) {
                plugin.saveResource("languages/" + lang + ".yml", false);
            }
        }
    }

    private FileConfiguration loadBundled(String lang) {
        try (InputStream is = plugin.getResource("languages/" + lang + ".yml")) {
            if (is == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    private String rawWithFallback(String key, String fallback) {
        String value = messages.getString(key);
        if (value == null && bundledDefaults != null) {
            value = bundledDefaults.getString(key);
        }
        return value != null ? value : fallback;
    }

    public String get(String key, String fallback) {
        return withPrefix(color(rawWithFallback(key, fallback)));
    }

    public String get(String key) {
        return get(key, key);
    }

    // Prefix-free variant; adding a prefix per line would break banner layouts.
    public String getRaw(String key, String fallback) {
        return color(rawWithFallback(key, fallback));
    }

    public String get(String key, String fallback, Map<String, String> placeholders) {
        return withPrefix(color(applyPlaceholders(rawWithFallback(key, fallback), placeholders)));
    }

    private static String applyPlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        String result = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String withPrefix(String message) {
        if (message == null || message.isEmpty() || prefix == null || prefix.isEmpty()) {
            return message == null ? "" : message;
        }
        return message.startsWith(prefix) ? message : prefix + message;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getLanguage() {
        return language;
    }

    private static String color(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
