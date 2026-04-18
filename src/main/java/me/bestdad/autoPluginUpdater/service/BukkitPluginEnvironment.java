package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.domain.InstalledPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BukkitPluginEnvironment {
    private final JavaPlugin owningPlugin;

    public BukkitPluginEnvironment(JavaPlugin owningPlugin) {
        this.owningPlugin = owningPlugin;
    }

    public List<InstalledPlugin> getInstalledPlugins() {
        List<InstalledPlugin> plugins = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            plugins.add(new InstalledPlugin(
                plugin.getDescription().getName(),
                plugin.getDescription().getVersion(),
                plugin.isEnabled(),
                resolveJarPath(plugin)
            ));
        }
        plugins.sort(Comparator.comparing(InstalledPlugin::name, String.CASE_INSENSITIVE_ORDER));
        return plugins;
    }

    public Optional<InstalledPlugin> findInstalledPlugin(String pluginName) {
        return getInstalledPlugins().stream()
            .filter(plugin -> plugin.matchesIdentifier(pluginName))
            .findFirst();
    }

    public Path getPluginsDirectory() {
        return owningPlugin.getDataFolder().getParentFile().toPath().toAbsolutePath().normalize();
    }

    public Path getUpdateDirectory() {
        return Bukkit.getUpdateFolderFile().toPath().toAbsolutePath().normalize();
    }

    public void dispatchRestartCommand(String restartCommand) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), restartCommand);
    }

    private static Path resolveJarPath(Plugin plugin) {
        try {
            if (plugin.getClass().getProtectionDomain().getCodeSource() == null
                || plugin.getClass().getProtectionDomain().getCodeSource().getLocation() == null) {
                return null;
            }

            return Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
