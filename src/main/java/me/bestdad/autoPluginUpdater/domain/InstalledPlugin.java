package me.bestdad.autoPluginUpdater.domain;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record InstalledPlugin(String name, String version, boolean enabled, Path jarPath) {

    public boolean matchesIdentifier(String rawValue) {
        String normalized = normalize(rawValue);
        if (normalized.isEmpty()) {
            return false;
        }
        return identifiers().contains(normalized);
    }

    public Set<String> identifiers() {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        addIfPresent(identifiers, name);

        if (jarPath != null && jarPath.getFileName() != null) {
            String fileName = jarPath.getFileName().toString();
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                String stem = fileName.substring(0, fileName.length() - 4);
                addIfPresent(identifiers, stem);

                int separatorIndex = Math.max(stem.lastIndexOf('-'), Math.max(stem.lastIndexOf('_'), stem.lastIndexOf(' ')));
                if (separatorIndex > 0 && separatorIndex < stem.length() - 1 && Character.isDigit(stem.charAt(separatorIndex + 1))) {
                    addIfPresent(identifiers, stem.substring(0, separatorIndex));
                }
            }
        }

        return identifiers;
    }

    private static void addIfPresent(Set<String> values, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            values.add(normalized);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
