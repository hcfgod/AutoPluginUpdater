package me.bestdad.autoPluginUpdater.command;

import me.bestdad.autoPluginUpdater.gui.GuiService;
import me.bestdad.autoPluginUpdater.service.PluginUpdateService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class AutoPluginUpdaterCommand implements CommandExecutor, TabCompleter {
    private final PluginUpdateService updateService;
    private final GuiService guiService;

    public AutoPluginUpdaterCommand(PluginUpdateService updateService, GuiService guiService) {
        this.updateService = updateService;
        this.guiService = guiService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("autopluginupdater.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage plugin updates.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                guiService.openMainMenu(player, 0);
            } else {
                updateService.getConsoleSummary().forEach(sender::sendMessage);
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "refresh" -> handleRefresh(sender, args);
            case "approve" -> handleApprove(sender, args);
            case "deny" -> handleDeny(sender, args);
            case "restart" -> handleRestart(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Usage: /apu [refresh|approve|deny|restart]");
        }
        return true;
    }

    private void handleRefresh(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(ChatColor.YELLOW + "Refreshing all managed plugins...");
            guiService.handleFuture(sender, updateService.refreshAllAsync(), null);
            return;
        }

        String pluginName = joinArgs(args, 1);
        sender.sendMessage(ChatColor.YELLOW + "Refreshing " + pluginName + "...");
        guiService.handleFuture(sender, updateService.refreshPluginAsync(pluginName), null);
    }

    private void handleApprove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /apu approve <plugin>");
            return;
        }

        String pluginName = joinArgs(args, 1);
        sender.sendMessage(ChatColor.YELLOW + "Staging update for " + pluginName + "...");
        guiService.handleFuture(sender, updateService.approveUpdateAsync(pluginName), null);
    }

    private void handleDeny(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /apu deny <plugin>");
            return;
        }

        String pluginName = joinArgs(args, 1);
        sender.sendMessage(ChatColor.YELLOW + "Saving denied version for " + pluginName + "...");
        guiService.handleFuture(sender, updateService.denyUpdateAsync(pluginName), null);
    }

    private void handleRestart(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /apu restart <now|later>");
            return;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "now" -> guiService.sendResult(sender, updateService.restartNow());
            case "later" -> guiService.sendResult(sender, updateService.restartLater());
            default -> sender.sendMessage(ChatColor.RED + "Usage: /apu restart <now|later>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("autopluginupdater.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterStartsWith(List.of("refresh", "approve", "deny", "restart"), args[0]);
        }

        if (args.length >= 2 && List.of("refresh", "approve", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            String partial = joinArgs(args, 1);
            List<String> pluginNames = updateService.getPluginViews().stream()
                .map(view -> view.pluginName())
                .collect(Collectors.toCollection(ArrayList::new));
            return filterStartsWith(pluginNames, partial);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("restart")) {
            return filterStartsWith(List.of("now", "later"), args[1]);
        }

        return List.of();
    }

    private List<String> filterStartsWith(List<String> values, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private String joinArgs(String[] args, int startIndex) {
        return String.join(" ", Arrays.copyOfRange(args, startIndex, args.length));
    }
}
