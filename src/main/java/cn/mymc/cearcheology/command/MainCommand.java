package cn.mymc.cearcheology.command;

import cn.mymc.cearcheology.CEArcheology;
import cn.mymc.cearcheology.command.subcommand.GiveCommand;
import cn.mymc.cearcheology.command.subcommand.HelpCommand;
import cn.mymc.cearcheology.command.subcommand.PlaceCommand;
import cn.mymc.cearcheology.command.subcommand.ReloadCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainCommand implements CommandExecutor, TabCompleter {

    private final CEArcheology plugin;
    private final Map<String, SubCommand> subCommands = new HashMap<>();

    public MainCommand(CEArcheology plugin) {
        this.plugin = plugin;
        registerSubCommand(new HelpCommand(plugin));
        registerSubCommand(new ReloadCommand(plugin));
        registerSubCommand(new GiveCommand(plugin));
        registerSubCommand(new PlaceCommand(plugin));
    }

    public void registerSubCommand(SubCommand command) {
        subCommands.put(command.getName().toLowerCase(), command);
        for (String alias : command.getAliases()) {
            subCommands.put(alias.toLowerCase(), command);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Rewrite to an explicit help subcommand so the permission check below also covers the bare call
        if (args.length == 0) {
            args = new String[]{"help"};
        }

        SubCommand subCommand = subCommands.get(args[0].toLowerCase());
        if (subCommand == null) {
            sender.sendMessage(cn.mymc.cearcheology.locale.LanguageManager
                .translate("unknown-command", "&cUnknown subcommand, use /cearch help for the command list."));
            return true;
        }

        if (!sender.hasPermission(subCommand.getPermission())) {
            sender.sendMessage(cn.mymc.cearcheology.locale.LanguageManager
                .translate("no-permission", "&cYou do not have permission to run this command."));
            return true;
        }

        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);

        subCommand.execute(sender, subArgs);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (SubCommand subCommand : subCommands.values()) {
                if (sender.hasPermission(subCommand.getPermission())
                    && subCommand.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(subCommand.getName());
                }
            }
        } else if (args.length > 1) {
            SubCommand subCommand = subCommands.get(args[0].toLowerCase());
            if (subCommand != null && sender.hasPermission(subCommand.getPermission())) {
                String[] subArgs = new String[args.length - 1];
                System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                completions.addAll(subCommand.tabComplete(sender, subArgs));
            }
        }

        return completions;
    }

    public Map<String, SubCommand> getSubCommands() {
        return subCommands;
    }
}
