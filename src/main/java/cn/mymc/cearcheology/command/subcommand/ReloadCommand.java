package cn.mymc.cearcheology.command.subcommand;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.SubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public class ReloadCommand extends SubCommand {

    private final CEArcheology plugin;

    public ReloadCommand(CEArcheology plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return rawMessage("command-description-reload", "Reload config and data");
    }

    @Override
    public String getPermission() {
        return "cearcheology.command.reload";
    }

    @Override
    public String getUsage() {
        return "/cearch reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        CEArcheology.ReloadSummary summary = plugin.reload();
        sender.sendMessage(message("reloaded", "&aCEArcheology reloaded."));
        sender.sendMessage(message("reload-summary-tools", "&7Tool configs: &f{count}",
            Map.of("count", String.valueOf(summary.toolCount()))));
        sender.sendMessage(message("reload-summary-loot-tables", "&7Loot tables: &f{count}",
            Map.of("count", String.valueOf(summary.lootTableCount()))));
        sender.sendMessage(message("reload-summary-blocks", "&7Detected archeology blocks: &f{count}",
            Map.of("count", String.valueOf(summary.loadedBlockCount()))));
        // Embedded mid-message, so it must be the prefix-free variant or the line shows two prefixes
        String statusText = summary.craftEngineIntegrated()
            ? rawMessage("reload-summary-integration-on", "&aconnected")
            : rawMessage("reload-summary-integration-off", "&cnot connected");
        sender.sendMessage(message("reload-summary-integration", "&7CraftEngine status: {status}",
            Map.of("status", statusText)));
        sender.sendMessage(message(
            "reload-summary-note",
            "&8Note: this only refreshes plugin-side config, tools, loot and runtime state; existing CraftEngine resource files are not overwritten."));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    private String message(String path, String fallback) {
        return cn.mymc.cearcheology.locale.LanguageManager.translate(path, fallback);
    }

    private String message(String path, String fallback, Map<String, String> placeholders) {
        return cn.mymc.cearcheology.locale.LanguageManager.translate(path, fallback, placeholders);
    }

    private String rawMessage(String path, String fallback) {
        return cn.mymc.cearcheology.locale.LanguageManager.translateRaw(path, fallback);
    }
}
