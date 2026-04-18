package me.bestdad.autoPluginUpdater.gui;

import me.bestdad.autoPluginUpdater.domain.PluginUpdateStatus;
import me.bestdad.autoPluginUpdater.domain.PluginView;
import me.bestdad.autoPluginUpdater.service.PluginUpdateService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class PluginDetailMenu implements MenuHolder {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PluginUpdateService updateService;
    private final GuiService guiService;
    private final String pluginName;
    private final int parentPage;
    private final Inventory inventory;

    public PluginDetailMenu(PluginUpdateService updateService, GuiService guiService, String pluginName, int parentPage) {
        this.updateService = updateService;
        this.guiService = guiService;
        this.pluginName = pluginName;
        this.parentPage = Math.max(parentPage, 0);
        this.inventory = Bukkit.createInventory(this, 27, pluginName + " Update");
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        if (rawSlot == 10) {
            player.sendMessage(ChatColor.YELLOW + "Staging the selected update...");
            guiService.handleFuture(player, updateService.approveUpdateAsync(pluginName), () -> guiService.openDetailMenu(player, pluginName, parentPage));
            return;
        }
        if (rawSlot == 11) {
            player.sendMessage(ChatColor.YELLOW + "Saving the denied version...");
            guiService.handleFuture(player, updateService.denyUpdateAsync(pluginName), () -> guiService.openDetailMenu(player, pluginName, parentPage));
            return;
        }
        if (rawSlot == 12) {
            player.sendMessage(ChatColor.YELLOW + "Refreshing update information...");
            guiService.handleFuture(player, updateService.refreshPluginAsync(pluginName), () -> guiService.openDetailMenu(player, pluginName, parentPage));
            return;
        }
        if (rawSlot == 14) {
            guiService.sendResult(player, updateService.restartNow());
            return;
        }
        if (rawSlot == 15) {
            guiService.sendResult(player, updateService.restartLater());
            return;
        }
        if (rawSlot == 18) {
            guiService.openMainMenu(player, parentPage);
        }
    }

    private void render() {
        inventory.clear();
        PluginView pluginView = updateService.getPluginView(pluginName).orElse(null);
        if (pluginView == null) {
            inventory.setItem(13, createButton(
                Material.BARRIER,
                ChatColor.RED + "Plugin not found",
                List.of(ChatColor.GRAY + "This plugin is no longer loaded.")
            ));
            inventory.setItem(18, createButton(Material.ARROW, ChatColor.GOLD + "Back", List.of(ChatColor.GRAY + "Return to the plugin list.")));
            return;
        }

        inventory.setItem(13, createSummaryItem(pluginView));
        inventory.setItem(10, createButton(
            pluginView.canApprove() ? Material.EMERALD_BLOCK : Material.GRAY_STAINED_GLASS_PANE,
            ChatColor.GREEN + "Approve update",
            List.of(ChatColor.GRAY + "Stage the latest approved jar into the update folder.")
        ));
        inventory.setItem(11, createButton(
            pluginView.canDeny() ? Material.REDSTONE_BLOCK : Material.GRAY_STAINED_GLASS_PANE,
            ChatColor.RED + "Deny this version",
            List.of(ChatColor.GRAY + "Hide this exact version until a newer one appears.")
        ));
        inventory.setItem(12, createButton(
            Material.COMPASS,
            ChatColor.AQUA + "Refresh",
            List.of(ChatColor.GRAY + "Fetch the latest manifest for this plugin.")
        ));
        inventory.setItem(14, createButton(
            updateService.hasPendingRestart() ? Material.TNT : Material.GRAY_STAINED_GLASS_PANE,
            ChatColor.GOLD + "Restart now",
            List.of(ChatColor.GRAY + "Dispatch the configured restart command now.")
        ));
        inventory.setItem(15, createButton(
            updateService.hasPendingRestart() ? Material.CLOCK : Material.GRAY_STAINED_GLASS_PANE,
            ChatColor.YELLOW + "Restart later",
            List.of(ChatColor.GRAY + "Keep staged updates queued until a later restart.")
        ));
        inventory.setItem(18, createButton(
            Material.ARROW,
            ChatColor.GOLD + "Back",
            List.of(ChatColor.GRAY + "Return to the plugin list.")
        ));
    }

    private ItemStack createSummaryItem(PluginView pluginView) {
        Material material = switch (pluginView.status()) {
            case UPDATE_AVAILABLE -> Material.EMERALD;
            case DENIED_VERSION -> Material.REDSTONE;
            case STAGED_PENDING_RESTART -> Material.CLOCK;
            case CHECK_FAILED -> Material.BARRIER;
            case UP_TO_DATE -> Material.LIME_DYE;
            case NOT_CONFIGURED -> Material.PAPER;
        };

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Installed version: " + ChatColor.WHITE + pluginView.installedVersion());
        lore.add(ChatColor.GRAY + "Latest version: " + ChatColor.WHITE + orUnknown(pluginView.latestVersion()));
        lore.add(ChatColor.GRAY + "Status: " + statusColor(pluginView.status()) + pluginView.status().getLabel());
        lore.add(ChatColor.GRAY + "Managed: " + ChatColor.WHITE + (pluginView.managed() ? "Yes" : "No"));
        if (pluginView.sourceLabel() != null) {
            lore.add(ChatColor.GRAY + "Source: " + ChatColor.WHITE + pluginView.sourceLabel());
        }
        if (pluginView.lastChecked() != null) {
            lore.add(ChatColor.GRAY + "Last checked: " + ChatColor.WHITE + TIME_FORMAT.format(pluginView.lastChecked()));
        }
        if (pluginView.approvalTimestamp() != null) {
            lore.add(ChatColor.GRAY + "Approved: " + ChatColor.WHITE + TIME_FORMAT.format(pluginView.approvalTimestamp()));
        }
        if (pluginView.deniedVersion() != null) {
            lore.add(ChatColor.GRAY + "Denied version: " + ChatColor.WHITE + pluginView.deniedVersion());
        }
        if (pluginView.stagedPath() != null) {
            lore.add(ChatColor.GRAY + "Staged jar: " + ChatColor.WHITE + pluginView.stagedPath());
        }
        if (pluginView.backupPath() != null) {
            lore.add(ChatColor.GRAY + "Backup jar: " + ChatColor.WHITE + pluginView.backupPath());
        }
        if (pluginView.changelogUrl() != null) {
            lore.add(ChatColor.GRAY + "Changelog: " + ChatColor.WHITE + pluginView.changelogUrl());
        }
        if (pluginView.lastError() != null) {
            lore.add(ChatColor.RED + "Last error: " + pluginView.lastError());
        }
        if (!pluginView.managed()) {
            lore.add(ChatColor.YELLOW + "Add this plugin to config.yml to track updates.");
        }

        return createButton(material, ChatColor.WHITE + pluginView.pluginName(), lore);
    }

    private ItemStack createButton(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private ChatColor statusColor(PluginUpdateStatus status) {
        return switch (status) {
            case UPDATE_AVAILABLE -> ChatColor.GREEN;
            case DENIED_VERSION -> ChatColor.RED;
            case STAGED_PENDING_RESTART -> ChatColor.GOLD;
            case CHECK_FAILED -> ChatColor.DARK_RED;
            case UP_TO_DATE -> ChatColor.GREEN;
            case NOT_CONFIGURED -> ChatColor.GRAY;
        };
    }
}
