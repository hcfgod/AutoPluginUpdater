package me.bestdad.autoPluginUpdater.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ManagedPluginConfig(
    String pluginName,
    ManagedPluginSourceType sourceType,
    String manifestUrl,
    String resourceUrl,
    String downloadUrl,
    Map<String, String> headers,
    String displaySource,
    boolean checksumRequired
) {

    public ManagedPluginConfig {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(sourceType, "sourceType");
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        manifestUrl = sanitize(manifestUrl);
        resourceUrl = sanitize(resourceUrl);
        downloadUrl = sanitize(downloadUrl);
        displaySource = displaySource == null ? "" : displaySource.trim();
    }

    public String referenceUrl() {
        return switch (sourceType) {
            case SPIGOT_RESOURCE, MODRINTH_PROJECT -> resourceUrl.isBlank() ? manifestUrl : resourceUrl;
            case HTTP_MANIFEST -> manifestUrl;
        };
    }

    public boolean isSpigotResource() {
        return sourceType == ManagedPluginSourceType.SPIGOT_RESOURCE;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
