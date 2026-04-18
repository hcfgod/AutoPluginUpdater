package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.domain.PluginUpdateStatus;
import me.bestdad.autoPluginUpdater.domain.UpdateHistoryEntry;
import me.bestdad.autoPluginUpdater.domain.UpdateState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class UpdateMetadataStore {
    private final Path file;
    private final Map<String, UpdateState> statesByKey = new LinkedHashMap<>();
    private final List<UpdateHistoryEntry> historyEntries = new ArrayList<>();

    public UpdateMetadataStore(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        statesByKey.clear();
        historyEntries.clear();

        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
        if (plugins != null) {
            for (String pluginName : plugins.getKeys(false)) {
                ConfigurationSection section = plugins.getConfigurationSection(pluginName);
                if (section == null) {
                    continue;
                }

                UpdateState state = new UpdateState(section.getString("plugin-name", pluginName));
                state.setInstalledVersion(blankToNull(section.getString("installed-version")));
                state.setLatestVersion(blankToNull(section.getString("latest-version")));
                state.setStatus(readStatus(section.getString("status")));
                state.setSourceLabel(blankToNull(section.getString("source-label")));
                state.setManifestUrl(blankToNull(section.getString("manifest-url")));
                state.setLastChecked(parseInstant(section.getString("last-checked")));
                state.setDeniedVersion(blankToNull(section.getString("denied-version")));
                state.setApprovalTimestamp(parseInstant(section.getString("approval-timestamp")));
                state.setBackupPath(blankToNull(section.getString("backup-path")));
                state.setStagedPath(blankToNull(section.getString("staged-path")));
                state.setLastError(blankToNull(section.getString("last-error")));
                state.setChangelogUrl(blankToNull(section.getString("changelog-url")));
                statesByKey.put(normalize(pluginName), state);
            }
        }

        List<Map<?, ?>> storedHistory = yaml.getMapList("history");
        for (Map<?, ?> rawEntry : storedHistory) {
            historyEntries.add(new UpdateHistoryEntry(
                stringValue(rawEntry.get("plugin-name")),
                stringValue(rawEntry.get("old-version")),
                stringValue(rawEntry.get("new-version")),
                stringValue(rawEntry.get("download-source")),
                parseInstant(stringValue(rawEntry.get("approval-timestamp"))),
                stringValue(rawEntry.get("backup-path")),
                stringValue(rawEntry.get("staged-path"))
            ));
        }
    }

    public synchronized Optional<UpdateState> getState(String pluginName) {
        UpdateState state = statesByKey.get(normalize(pluginName));
        return state == null ? Optional.empty() : Optional.of(state.copy());
    }

    public synchronized Map<String, UpdateState> getStatesSnapshot() {
        Map<String, UpdateState> copy = new LinkedHashMap<>();
        for (Map.Entry<String, UpdateState> entry : statesByKey.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    public synchronized List<UpdateHistoryEntry> getHistorySnapshot() {
        return new ArrayList<>(historyEntries);
    }

    public synchronized void saveState(UpdateState state) throws IOException {
        statesByKey.put(normalize(state.getPluginName()), state.copy());
        persist();
    }

    public synchronized void saveStates(List<UpdateState> states) throws IOException {
        for (UpdateState state : states) {
            statesByKey.put(normalize(state.getPluginName()), state.copy());
        }
        persist();
    }

    public synchronized void appendHistory(UpdateHistoryEntry historyEntry) throws IOException {
        historyEntries.add(historyEntry);
        persist();
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();

        for (UpdateState state : statesByKey.values()) {
            String base = "plugins." + state.getPluginName();
            yaml.set(base + ".plugin-name", state.getPluginName());
            yaml.set(base + ".installed-version", state.getInstalledVersion());
            yaml.set(base + ".latest-version", state.getLatestVersion());
            yaml.set(base + ".status", state.getStatus().name());
            yaml.set(base + ".source-label", state.getSourceLabel());
            yaml.set(base + ".manifest-url", state.getManifestUrl());
            yaml.set(base + ".last-checked", formatInstant(state.getLastChecked()));
            yaml.set(base + ".denied-version", state.getDeniedVersion());
            yaml.set(base + ".approval-timestamp", formatInstant(state.getApprovalTimestamp()));
            yaml.set(base + ".backup-path", state.getBackupPath());
            yaml.set(base + ".staged-path", state.getStagedPath());
            yaml.set(base + ".last-error", state.getLastError());
            yaml.set(base + ".changelog-url", state.getChangelogUrl());
        }

        List<Map<String, Object>> serializedHistory = new ArrayList<>();
        for (UpdateHistoryEntry entry : historyEntries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("plugin-name", entry.pluginName());
            map.put("old-version", entry.oldVersion());
            map.put("new-version", entry.newVersion());
            map.put("download-source", entry.downloadSource());
            map.put("approval-timestamp", formatInstant(entry.approvalTimestamp()));
            map.put("backup-path", entry.backupPath());
            map.put("staged-path", entry.stagedPath());
            serializedHistory.add(map);
        }
        yaml.set("history", serializedHistory);

        yaml.save(file.toFile());
    }

    private static PluginUpdateStatus readStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return PluginUpdateStatus.NOT_CONFIGURED;
        }

        try {
            return PluginUpdateStatus.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PluginUpdateStatus.NOT_CONFIGURED;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
