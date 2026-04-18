package me.bestdad.autoPluginUpdater;

import me.bestdad.autoPluginUpdater.command.AutoPluginUpdaterCommand;
import me.bestdad.autoPluginUpdater.config.ManagedPluginRegistry;
import me.bestdad.autoPluginUpdater.gui.GuiService;
import me.bestdad.autoPluginUpdater.gui.MenuListener;
import me.bestdad.autoPluginUpdater.service.BukkitPluginEnvironment;
import me.bestdad.autoPluginUpdater.service.HttpManifestSourceClient;
import me.bestdad.autoPluginUpdater.service.PluginUpdateEngine;
import me.bestdad.autoPluginUpdater.service.PluginUpdateService;
import me.bestdad.autoPluginUpdater.service.UpdateMetadataStore;
import me.bestdad.autoPluginUpdater.service.VersionService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AutoPluginUpdater extends JavaPlugin {
    private ExecutorService ioExecutor;
    private PluginUpdateService updateService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        try {
            UpdateMetadataStore metadataStore = new UpdateMetadataStore(getDataFolder().toPath().resolve("updates.yml"));
            metadataStore.load();

            ManagedPluginRegistry registry = ManagedPluginRegistry.fromConfig(getConfig());
            BukkitPluginEnvironment environment = new BukkitPluginEnvironment(this);
            VersionService versionService = new VersionService();
            HttpManifestSourceClient manifestSourceClient = new HttpManifestSourceClient(Duration.ofSeconds(
                Math.max(3L, getConfig().getLong("http-timeout-seconds", 10L))
            ));

            ioExecutor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                runnable -> {
                    Thread thread = new Thread(runnable, "AutoPluginUpdater-IO");
                    thread.setDaemon(true);
                    return thread;
                }
            );

            PluginUpdateEngine engine = new PluginUpdateEngine(
                registry,
                metadataStore,
                manifestSourceClient,
                versionService,
                getDataFolder().toPath(),
                environment.getPluginsDirectory(),
                environment.getUpdateDirectory(),
                () -> environment.dispatchRestartCommand(getConfig().getString("restart-command", "restart"))
            );
            updateService = new PluginUpdateService(this, ioExecutor, environment, engine);
            updateService.reconcileStartupState();

            GuiService guiService = new GuiService(this, updateService);
            AutoPluginUpdaterCommand commandExecutor = new AutoPluginUpdaterCommand(updateService, guiService);
            PluginCommand pluginCommand = Objects.requireNonNull(getCommand("apu"), "apu command missing from plugin.yml");
            pluginCommand.setExecutor(commandExecutor);
            pluginCommand.setTabCompleter(commandExecutor);

            getServer().getPluginManager().registerEvents(new MenuListener(), this);

            long scanIntervalMinutes = Math.max(1L, getConfig().getLong("scan-interval-minutes", 60L));
            updateService.scheduleRecurringScan(scanIntervalMinutes);
            updateService.refreshAllAsync();
            getLogger().info("AutoPluginUpdater is enabled.");
        } catch (IOException exception) {
            getLogger().severe("Failed to initialize AutoPluginUpdater: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (updateService != null) {
            updateService.cancelRecurringScan();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ioExecutor.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
