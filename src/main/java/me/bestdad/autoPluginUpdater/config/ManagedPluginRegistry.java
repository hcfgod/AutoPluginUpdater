package me.bestdad.autoPluginUpdater.config;

import me.bestdad.autoPluginUpdater.domain.InstalledPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ManagedPluginRegistry {
    private final Map<String, ManagedPluginConfig> pluginsByKey;

    private ManagedPluginRegistry(Map<String, ManagedPluginConfig> pluginsByKey) {
        this.pluginsByKey = Collections.unmodifiableMap(new LinkedHashMap<>(pluginsByKey));
    }

    public static ManagedPluginRegistry fromConfig(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("managed-plugins");
        Map<String, ManagedPluginConfig> entries = new LinkedHashMap<>();
        if (root == null) {
            return new ManagedPluginRegistry(entries);
        }

        for (String pluginName : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(pluginName);
            if (section == null) {
                continue;
            }

            ManagedPluginSourceType sourceType = ManagedPluginSourceType.fromConfig(section.getString("source-type"));
            String manifestUrl = section.getString("manifest-url", "").trim();
            String resourceUrl = section.getString("resource-url", "").trim();
            String downloadUrl = section.getString("download-url", "").trim();
            if (sourceType == ManagedPluginSourceType.HTTP_MANIFEST && manifestUrl.isEmpty()) {
                continue;
            }
            if (sourceType == ManagedPluginSourceType.SPIGOT_RESOURCE && resourceUrl.isEmpty()) {
                continue;
            }
            if (sourceType == ManagedPluginSourceType.MODRINTH_PROJECT && manifestUrl.isEmpty()) {
                continue;
            }

            Map<String, String> headers = new LinkedHashMap<>();
            ConfigurationSection headerSection = section.getConfigurationSection("headers");
            if (headerSection != null) {
                for (String key : headerSection.getKeys(false)) {
                    headers.put(key, headerSection.getString(key, ""));
                }
            }

            ManagedPluginConfig managedPlugin = new ManagedPluginConfig(
                pluginName,
                sourceType,
                manifestUrl,
                resourceUrl,
                downloadUrl,
                headers,
                section.getString("display-source", ""),
                section.getBoolean("checksum-required", false)
            );
            entries.put(normalize(pluginName), managedPlugin);
        }

        return new ManagedPluginRegistry(entries);
    }

    public Optional<ManagedPluginConfig> find(String pluginName) {
        return Optional.ofNullable(pluginsByKey.get(normalize(pluginName)));
    }

    public Optional<ManagedPluginConfig> find(InstalledPlugin installedPlugin) {
        for (String identifier : installedPlugin.identifiers()) {
            ManagedPluginConfig config = pluginsByKey.get(identifier);
            if (config != null) {
                return Optional.of(config);
            }
        }
        return Optional.empty();
    }

    public boolean isManaged(String pluginName) {
        return pluginsByKey.containsKey(normalize(pluginName));
    }

    public boolean isManaged(InstalledPlugin installedPlugin) {
        return find(installedPlugin).isPresent();
    }

    public Set<String> names() {
        return pluginsByKey.keySet();
    }

    public Map<String, ManagedPluginConfig> asMap() {
        return pluginsByKey;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
