package cn.mymc.cearcheology.config;

import cn.mymc.cearcheology.locale.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ConfigManager {

    private static volatile ConfigManager instance;
    private static final Object LOCK = new Object();

    private final JavaPlugin plugin;
    private float defaultDisplayStartOffset;
    private float defaultDisplayEndOffset;
    private float defaultDisplayScale;
    private long brushTickInterval;
    private long recoveryDelay;
    private long recoveryInterval;
    private int sessionTimeout;
    private boolean debugMode;
    private boolean commandWhitelistEnabled;
    private List<String> commandWhitelist;
    private List<CommandWhitelistRule> commandWhitelistRules;

    private ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public static void init(JavaPlugin plugin) {
        synchronized (LOCK) {
            if (instance != null) {
                instance.load();
                return;
            }
            instance = new ConfigManager(plugin);
        }
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration config = plugin.getConfig();

        // These defaults must match the bundled config.yml: saveDefaultConfig never adds missing keys
        // to an existing file, so upgraded or hand-trimmed servers really do land on them.
        // (The old session-timeout=10 tore sessions down after 500ms while basic brushing takes 4.8s.)
        defaultDisplayStartOffset = (float) config.getDouble("display.start-offset", 0.03);
        defaultDisplayEndOffset = (float) config.getDouble("display.end-offset", 0.20);
        defaultDisplayScale = (float) config.getDouble("display.scale", 0.5);

        brushTickInterval = config.getLong("brush.tick-interval", 20);
        recoveryDelay = config.getLong("recovery.delay", 20);
        recoveryInterval = config.getLong("recovery.interval", 5);
        sessionTimeout = config.getInt("brush.session-timeout", 300);
        debugMode = config.getBoolean("debug", false);

        commandWhitelistEnabled = config.getBoolean("command-loot.enable-whitelist", true);
        commandWhitelist = config.getStringList("command-loot.whitelist");
        if (commandWhitelist == null) {
            commandWhitelist = new ArrayList<>();
        }

        commandWhitelistRules = new ArrayList<>();
        for (String rule : commandWhitelist) {
            CommandWhitelistRule parsedRule = CommandWhitelistRule.parse(rule);
            if (parsedRule != null) {
                commandWhitelistRules.add(parsedRule);
            }
        }
    }

    public float getDefaultDisplayStartOffset() {
        return defaultDisplayStartOffset;
    }

    public float getDefaultDisplayEndOffset() {
        return defaultDisplayEndOffset;
    }

    public float getDefaultDisplayScale() {
        return defaultDisplayScale;
    }

    public long getBrushTickInterval() {
        return brushTickInterval;
    }

    public long getRecoveryDelay() {
        return recoveryDelay;
    }

    public long getRecoveryInterval() {
        return recoveryInterval;
    }

    public int getSessionTimeout() {
        return sessionTimeout;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public boolean isCommandWhitelistEnabled() {
        return commandWhitelistEnabled;
    }

    public List<String> getCommandWhitelist() {
        return commandWhitelist;
    }

    public boolean isCommandAllowed(String command) {
        return isCommandAllowed(command, command);
    }

    // template is the raw configured command, resolvedCommand the one about to run after substitution.
    public boolean isCommandAllowed(String template, String resolvedCommand) {
        if (!commandWhitelistEnabled) {
            return true;
        }

        String normalizedTemplate = normalizeCommand(template);
        String normalizedCommand = normalizeCommand(resolvedCommand);
        if (normalizedTemplate == null || normalizedCommand == null) {
            return false;
        }

        // Dangerous substrings are checked on the template only. Checking after substitution would match
        // player and world names too: Flagship / Stopper / Reloader would hit "lag" / "stop" / "reload"
        // and those players could never receive a command reward, purely because of their name.
        // Substituted values are guarded by PLAYER_PATTERN / WORLD_PATTERN / NUMBER_PATTERN instead.
        if (containsDangerousPatterns(normalizedTemplate)) {
            Bukkit.getLogger().warning(LanguageManager.log("log-command-blocked-dangerous",
                "[CEArcheology] Command blocked (dangerous pattern): {command}",
                Map.of("command", String.valueOf(normalizedTemplate))));
            return false;
        }

        for (CommandWhitelistRule rule : commandWhitelistRules) {
            if (rule.matches(normalizedCommand)) {
                return true;
            }
        }

        return false;
    }

    private String normalizeCommand(String command) {
        if (command == null) {
            return null;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean containsDangerousPatterns(String command) {
        String lower = command.toLowerCase();

        String[] dangerousPatterns = {
            "&&", "||", ";", "|",
            "$(", "`", "${",
            ">", ">>", "<",
            "op ", "deop ",
            "stop", "reload",
            "save-all", "save-off",
            "whitelist off",
            "ban-ip", "pardon-ip",
            "execute if", "execute run",
            "function ",
            "\n", "\r",
            "\\u0000",
            "\\x00",
            "%0a", "%0d",
            "crash", "lag",
            "fill ~", "setblock ~",
            "clone ~"
        };

        for (String pattern : dangerousPatterns) {
            if (lower.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    private static final class CommandWhitelistRule {

        private static final Pattern PLAYER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");
        private static final Pattern WORLD_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");
        private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

        private final List<String> tokens;

        private CommandWhitelistRule(List<String> tokens) {
            this.tokens = tokens;
        }

        static CommandWhitelistRule parse(String rawRule) {
            if (rawRule == null) {
                return null;
            }

            String normalized = rawRule.trim();
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1).trim();
            }
            if (normalized.isEmpty()) {
                return null;
            }

            String[] parts = normalized.split("\\s+");
            List<String> tokens = new ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    tokens.add(part);
                }
            }
            return tokens.isEmpty() ? null : new CommandWhitelistRule(tokens);
        }

        boolean matches(String command) {
            String[] commandTokens = command.split("\\s+");
            if (commandTokens.length < tokens.size()) {
                return false;
            }

            for (int i = 0; i < tokens.size(); i++) {
                if (!matchesToken(tokens.get(i), commandTokens[i])) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesToken(String ruleToken, String commandToken) {
            return switch (ruleToken.toLowerCase()) {
                case "{player}" -> PLAYER_PATTERN.matcher(commandToken).matches();
                case "{world}" -> WORLD_PATTERN.matcher(commandToken).matches();
                case "{x}", "{y}", "{z}" -> NUMBER_PATTERN.matcher(commandToken).matches();
                default -> ruleToken.equalsIgnoreCase(commandToken);
            };
        }
    }
}
