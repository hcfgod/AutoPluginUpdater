package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.domain.ActionResult;
import me.bestdad.autoPluginUpdater.domain.InstalledPlugin;
import me.bestdad.autoPluginUpdater.domain.PluginView;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class PluginUpdateService {
    private final JavaPlugin plugin;
    private final Executor executor;
    private final BukkitPluginEnvironment environment;
    private final PluginUpdateEngine engine;
    private BukkitTask recurringScanTask;

    public PluginUpdateService(
        JavaPlugin plugin,
        Executor executor,
        BukkitPluginEnvironment environment,
        PluginUpdateEngine engine
    ) {
        this.plugin = plugin;
        this.executor = executor;
        this.environment = environment;
        this.engine = engine;
    }

    public void reconcileStartupState() throws IOException {
        engine.reconcileStartupState(environment.getInstalledPlugins());
    }

    public void scheduleRecurringScan(long intervalMinutes) {
        cancelRecurringScan();
        long ticks = Math.max(1L, intervalMinutes) * 60L * 20L;
        recurringScanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllAsync, 20L, ticks);
    }

    public void cancelRecurringScan() {
        if (recurringScanTask != null) {
            recurringScanTask.cancel();
            recurringScanTask = null;
        }
    }

    public CompletableFuture<ActionResult> refreshAllAsync() {
        List<InstalledPlugin> installedPlugins = environment.getInstalledPlugins();
        return CompletableFuture.supplyAsync(() -> engine.refreshAll(installedPlugins), executor);
    }

    public CompletableFuture<ActionResult> refreshPluginAsync(String pluginName) {
        Optional<InstalledPlugin> installedPlugin = environment.findInstalledPlugin(pluginName);
        if (installedPlugin.isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("Plugin " + pluginName + " is not currently loaded."));
        }
        return CompletableFuture.supplyAsync(() -> engine.refreshPlugin(installedPlugin.get()), executor);
    }

    public CompletableFuture<ActionResult> approveUpdateAsync(String pluginName) {
        Optional<InstalledPlugin> installedPlugin = environment.findInstalledPlugin(pluginName);
        if (installedPlugin.isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("Plugin " + pluginName + " is not currently loaded."));
        }
        return CompletableFuture.supplyAsync(() -> engine.approveUpdate(installedPlugin.get()), executor);
    }

    public CompletableFuture<ActionResult> denyUpdateAsync(String pluginName) {
        Optional<InstalledPlugin> installedPlugin = environment.findInstalledPlugin(pluginName);
        if (installedPlugin.isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("Plugin " + pluginName + " is not currently loaded."));
        }
        return CompletableFuture.supplyAsync(() -> engine.denyUpdate(installedPlugin.get()), executor);
    }

    public ActionResult restartNow() {
        return engine.restartNow();
    }

    public ActionResult restartLater() {
        return engine.restartLater();
    }

    public List<PluginView> getPluginViews() {
        return engine.getPluginViews(environment.getInstalledPlugins());
    }

    public Optional<PluginView> getPluginView(String pluginName) {
        return engine.getPluginView(pluginName, environment.getInstalledPlugins());
    }

    public List<String> getConsoleSummary() {
        return engine.buildConsoleSummary(environment.getInstalledPlugins());
    }

    public boolean hasPendingRestart() {
        return engine.hasPendingRestart();
    }
}
