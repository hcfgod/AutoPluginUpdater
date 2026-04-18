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

import java.util.ArrayList;
import java.util.List;

public final class PluginListMenu implements MenuHolder {
    private static final int PAGE_SIZE = 45;

    private final PluginUpdateService updateService;
    private final GuiService guiService;
    private final int page;
    private final List<PluginView> pluginViews;
    private final Inventory inventory;

    public PluginListMenu(PluginUpdateService updateService, GuiService guiService, int page) {
        this.updateService = updateService;
        this.guiService = guiService;
        this.pluginViews = updateService.getPluginViews();
        this.page = Math.max(0, Math.min(page, maxPage(pluginViews.size())));
        this.inventory = Bukkit.createInventory(this, 54, "Plugin Updates");
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        int startIndex = page * PAGE_SIZE;
        int index = startIndex + rawSlot;

        if (rawSlot >= 0 && rawSlot < PAGE_SIZE && index < pluginViews.size()) {
            guiService.openDetailMenu(player, pluginViews.get(index).pluginName(), page);
            return;
        }
        if (rawSlot == 45 && page > 0) {
            guiService.openMainMenu(player, page - 1);
            return;
        }
        if (rawSlot == 49) {
            player.sendMessage(ChatColor.YELLOW + "Refreshing plugin update information...");
            guiService.handleFuture(player, updateService.refreshAllAsync(), () -> guiService.openMainMenu(player, page));
            return;
        }
        if (rawSlot == 53 && page < maxPage(pluginViews.size())) {
            guiService.openMainMenu(player, page + 1);
        }
    }

    private void render() {
        inventory.clear();

        int startIndex = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int pluginIndex = startIndex + slot;
            if (pluginIndex >= pluginViews.size()) {
                break;
            }
            inventory.setItem(slot, createPluginItem(pluginViews.get(pluginIndex)));
        }

        inventory.setItem(45, createButton(
            Material.ARROW,
            ChatColor.GOLD + "Previous page",
            List.of(ChatColor.GRAY + "Go to the previous page.")
        ));
        inventory.setItem(49, createButton(
            Material.COMPASS,
            ChatColor.AQUA + "Refresh all",
            List.of(ChatColor.GRAY + "Re-scan all managed plugins now.")
        ));
        inventory.setItem(53, createButton(
            Material.ARROW,
            ChatColor.GOLD + "Next page",
            List.of(ChatColor.GRAY + "Go to the next page.")
        ));
    }

    private ItemStack createPluginItem(PluginView pluginView) {
        Material material = switch (pluginView.status()) {
            case UPDATE_AVAILABLE -> Material.EMERALD;
            case DENIED_VERSION -> Material.REDSTONE;
            case STAGED_PENDING_RESTART -> Material.CLOCK;
            case CHECK_FAILED -> Material.BARRIER;
            case UP_TO_DATE -> Material.LIME_DYE;
            case NOT_CONFIGURED -> Material.PAPER;
        };

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Installed: " + ChatColor.WHITE + pluginView.installedVersion());
        lore.add(ChatColor.GRAY + "Latest: " + ChatColor.WHITE + orUnknown(pluginView.latestVersion()));
        lore.add(ChatColor.GRAY + "Status: " + statusColor(pluginView.status()) + pluginView.status().getLabel());
        lore.add(ChatColor.GRAY + "Managed: " + ChatColor.WHITE + (pluginView.managed() ? "Yes" : "No"));
        if (pluginView.sourceLabel() != null) {
            lore.add(ChatColor.GRAY + "Source: " + ChatColor.WHITE + pluginView.sourceLabel());
        }
        if (pluginView.lastError() != null) {
            lore.add(ChatColor.RED + "Error: " + pluginView.lastError());
        }
        lore.add(ChatColor.YELLOW + "Click to view details.");

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

    private int maxPage(int pluginCount) {
        return pluginCount <= 0 ? 0 : (pluginCount - 1) / PAGE_SIZE;
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
