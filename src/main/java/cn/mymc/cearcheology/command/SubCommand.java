package cn.mymc.cearcheology.command;

import org.bukkit.command.CommandSender;

import java.util.List;

public abstract class SubCommand {
    
    public abstract String getName();
    public abstract String getDescription();
    public abstract String getPermission();
    public abstract String getUsage();
    
    public String[] getAliases() {
        return new String[0];
    }
    
    public abstract void execute(CommandSender sender, String[] args);
    public abstract List<String> tabComplete(CommandSender sender, String[] args);
}
