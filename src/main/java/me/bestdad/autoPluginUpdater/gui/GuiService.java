package me.bestdad.autoPluginUpdater.gui;

import me.bestdad.autoPluginUpdater.AutoPluginUpdater;
import me.bestdad.autoPluginUpdater.domain.ActionResult;
import me.bestdad.autoPluginUpdater.service.PluginUpdateService;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public final class GuiService {
    private final AutoPluginUpdater plugin;
    private final PluginUpdateService updateService;

    public GuiService(AutoPluginUpdater plugin, PluginUpdateService updateService) {
        this.plugin = plugin;
        this.updateService = updateService;
    }

    public void openMainMenu(Player player, int page) {
        player.openInventory(new PluginListMenu(updateService, this, page).getInventory());
    }

    public void openDetailMenu(Player player, String pluginName, int parentPage) {
        player.openInventory(new PluginDetailMenu(updateService, this, pluginName, parentPage).getInventory());
    }

    public void handleFuture(CommandSender sender, CompletableFuture<ActionResult> future, Runnable completionAction) {
        future.whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                sender.sendMessage(org.bukkit.ChatColor.RED + "Operation failed: " + throwable.getMessage());
            } else if (result != null) {
                sendResult(sender, result);
            }

            if (completionAction != null) {
                completionAction.run();
            }
        }));
    }

    public void sendResult(CommandSender sender, ActionResult result) {
        org.bukkit.ChatColor color = result.success() ? org.bukkit.ChatColor.GREEN : org.bukkit.ChatColor.RED;
        sender.sendMessage(color + result.message());
        if (result.success() && result.restartPromptSuggested()) {
            sendRestartPrompt(sender);
        }
    }

    public void sendRestartPrompt(CommandSender sender) {
        if (sender instanceof Player player) {
            TextComponent prefix = new TextComponent("Update staged. ");
            prefix.setColor(ChatColor.YELLOW);

            TextComponent restartNow = new TextComponent("[Restart now]");
            restartNow.setColor(ChatColor.GREEN);
            restartNow.setBold(true);
            restartNow.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/apu restart now"));

            TextComponent spacer = new TextComponent(" ");

            TextComponent restartLater = new TextComponent("[Restart later]");
            restartLater.setColor(ChatColor.GRAY);
            restartLater.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/apu restart later"));

            player.spigot().sendMessage(new ComponentBuilder()
                .append(prefix)
                .append(restartNow)
                .append(spacer)
                .append(restartLater)
                .create());
            return;
        }

        sender.sendMessage(org.bukkit.ChatColor.YELLOW + "Use /apu restart now when you're ready to apply staged updates.");
    }
}
