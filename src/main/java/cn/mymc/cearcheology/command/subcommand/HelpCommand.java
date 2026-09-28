package cn.mymc.cearcheology.command.subcommand;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.SubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class HelpCommand extends SubCommand {

    private final CEArcheology plugin;

    public HelpCommand(CEArcheology plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return message("command-description-help", "Show the help message");
    }

    @Override
    public String getPermission() {
        return "cearcheology.command.help";
    }

    @Override
    public String getUsage() {
        return "/cearch help";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage(message("help-header", "&6========== CEArcheology Help =========="));
        sender.sendMessage(message("help-help", "&e/cearch help &7- Show the help message"));
        sender.sendMessage(message("help-reload", "&e/cearch reload &7- Reload config, tools, loot and runtime state"));
        sender.sendMessage(message("help-give-block", "&e/cearch give block <blockId> [player] [amount] &7- Give an archeology block"));
        sender.sendMessage(message("help-give-block-craftengine", "&e/cearch give block <blockId> craftengine <ceItemId> [player] [amount] &7- Preset a CE reward"));
        sender.sendMessage(message("help-give-block-table", "&e/cearch give block <blockId> table <lootTableId> [player] [amount] &7- Preset a loot table"));
        sender.sendMessage(message("help-give-block-item", "&e/cearch give block <blockId> item <itemId> [player] [amount] &7- Preset a vanilla item"));
        sender.sendMessage(message("help-give-tool", "&e/cearch give tool <toolId> [player] [amount] &7- Give a brushing tool"));
        sender.sendMessage(message("help-place", "&e/cearch place <blockId> [x y z|world x y z] [itemId/lootTableId] &7- Place an archeology block directly"));
        sender.sendMessage(message("help-place-note", "&7Note: players may omit world, the console must state it; place auto-detects vanilla item IDs and existing loot table IDs."));
        sender.sendMessage(message("help-footer", "&6======================================"));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    private String message(String path, String fallback) {
        // Help is a multi-line block; a per-line prefix would break the separators and item layout
        return cn.mymc.cearcheology.locale.LanguageManager.translateRaw(path, fallback);
    }
}
