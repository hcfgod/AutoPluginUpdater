package me.bestdad.autoPluginUpdater.config;

import java.util.Locale;

public enum ManagedPluginSourceType {
    HTTP_MANIFEST("http-manifest"),
    SPIGOT_RESOURCE("spigot-resource"),
    MODRINTH_PROJECT("modrinth-project");

    private final String configValue;

    ManagedPluginSourceType(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static ManagedPluginSourceType fromConfig(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return HTTP_MANIFEST;
        }

        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        for (ManagedPluginSourceType value : values()) {
            if (value.configValue.equals(normalized)) {
                return value;
            }
        }

        return HTTP_MANIFEST;
    }
}
