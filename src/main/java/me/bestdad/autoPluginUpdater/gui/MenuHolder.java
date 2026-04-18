package me.bestdad.autoPluginUpdater.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryHolder;

public interface MenuHolder extends InventoryHolder {
    void handleClick(Player player, int rawSlot, ClickType clickType);
}
