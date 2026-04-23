package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.config.ManagedPluginConfig;
import me.bestdad.autoPluginUpdater.config.ManagedPluginRegistry;
import me.bestdad.autoPluginUpdater.domain.ActionResult;
import me.bestdad.autoPluginUpdater.domain.InstalledPlugin;
import me.bestdad.autoPluginUpdater.domain.PluginUpdateStatus;
import me.bestdad.autoPluginUpdater.domain.PluginView;
import me.bestdad.autoPluginUpdater.domain.RemotePluginManifest;
import me.bestdad.autoPluginUpdater.domain.UpdateHistoryEntry;
import me.bestdad.autoPluginUpdater.domain.UpdateState;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class PluginUpdateEngine {
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ManagedPluginRegistry registry;
    private final UpdateMetadataStore metadataStore;
    private final ManifestSourceClient manifestSourceClient;
    private final VersionService versionService;
    private final Path dataDirectory;
    private final Path pluginsDirectory;
    private final Path updateDirectory;
    private final Runnable restartDispatcher;

    public PluginUpdateEngine(
        ManagedPluginRegistry registry,
        UpdateMetadataStore metadataStore,
        ManifestSourceClient manifestSourceClient,
        VersionService versionService,
        Path dataDirectory,
        Path pluginsDirectory,
        Path updateDirectory,
        Runnable restartDispatcher
    ) {
        this.registry = registry;
        this.metadataStore = metadataStore;
        this.manifestSourceClient = manifestSourceClient;
        this.versionService = versionService;
        this.dataDirectory = dataDirectory;
        this.pluginsDirectory = pluginsDirectory.toAbsolutePath().normalize();
        this.updateDirectory = updateDirectory.toAbsolutePath().normalize();
        this.restartDispatcher = restartDispatcher;
    }

    public synchronized void reconcileStartupState(List<InstalledPlugin> installedPlugins) throws IOException {
        List<UpdateState> statesToSave = new ArrayList<>();
        for (InstalledPlugin installedPlugin : installedPlugins) {
            UpdateState state = metadataStore.getState(installedPlugin.name()).orElse(null);
            if (state == null) {
                continue;
            }

            state.setInstalledVersion(installedPlugin.version());
            if (state.getStatus() == PluginUpdateStatus.STAGED_PENDING_RESTART
                && versionService.isSameVersion(installedPlugin.version(), state.getLatestVersion())) {
                state.setStatus(PluginUpdateStatus.UP_TO_DATE);
                state.setStagedPath(null);
                state.setLastError(null);
            }
            statesToSave.add(state);
        }

        if (!statesToSave.isEmpty()) {
            metadataStore.saveStates(statesToSave);
        }
    }

    public synchronized ActionResult refreshAll(List<InstalledPlugin> installedPlugins) {
        List<UpdateState> statesToSave = new ArrayList<>();
        int refreshedCount = 0;

        for (InstalledPlugin installedPlugin : installedPlugins.stream()
            .sorted(Comparator.comparing(InstalledPlugin::name, String.CASE_INSENSITIVE_ORDER))
            .toList()) {
            UpdateState state = metadataStore.getState(installedPlugin.name())
                .orElseGet(() -> new UpdateState(installedPlugin.name()));
            state.setPluginName(installedPlugin.name());
            state.setInstalledVersion(installedPlugin.version());

            try {
                refreshInstalledPlugin(installedPlugin, state);
            } catch (Exception exception) {
                ManagedPluginConfig config = registry.find(installedPlugin).orElse(null);
                applyCheckFailedState(state, installedPlugin, config, exception.getMessage());
            }

            statesToSave.add(state);
            refreshedCount++;
        }

        try {
            metadataStore.saveStates(statesToSave);
            return ActionResult.success("Refreshed " + refreshedCount + " installed plugins.");
        } catch (IOException exception) {
            return ActionResult.failure("Refresh completed but metadata could not be saved: " + exception.getMessage());
        }
    }

    public synchronized ActionResult refreshPlugin(InstalledPlugin installedPlugin) {
        UpdateState state = metadataStore.getState(installedPlugin.name())
            .orElseGet(() -> new UpdateState(installedPlugin.name()));
        state.setPluginName(installedPlugin.name());
        state.setInstalledVersion(installedPlugin.version());

        try {
            refreshInstalledPlugin(installedPlugin, state);
            metadataStore.saveState(state);
            return ActionResult.success(
                "Refreshed update information for " + installedPlugin.name() + ".",
                toPluginView(installedPlugin, state),
                false
            );
        } catch (Exception exception) {
            ManagedPluginConfig config = registry.find(installedPlugin).orElse(null);
            applyCheckFailedState(state, installedPlugin, config, exception.getMessage());
            try {
                metadataStore.saveState(state);
            } catch (IOException ignored) {
            }
            return ActionResult.failure("Could not refresh " + installedPlugin.name() + ": " + exception.getMessage());
        }
    }

    public synchronized ActionResult approveUpdate(InstalledPlugin installedPlugin) {
        ManagedPluginConfig config = registry.find(installedPlugin).orElse(null);
        if (config == null) {
            return ActionResult.failure(installedPlugin.name() + " is not configured for update checks.");
        }

        UpdateState state = metadataStore.getState(installedPlugin.name())
            .orElseGet(() -> new UpdateState(installedPlugin.name()));
        state.setPluginName(installedPlugin.name());
        state.setInstalledVersion(installedPlugin.version());

        if (state.getStatus() == PluginUpdateStatus.STAGED_PENDING_RESTART) {
            return ActionResult.failure(installedPlugin.name() + " already has a staged update waiting for restart.");
        }

        try {
            RemotePluginManifest manifest = manifestSourceClient.fetchManifest(config);
            validateManifest(installedPlugin, manifest);
            if (!versionService.isNewer(installedPlugin.version(), manifest.version())) {
                state.setLatestVersion(manifest.version());
                state.setSourceLabel(resolveSourceLabel(config));
                state.setManifestUrl(config.referenceUrl());
                state.setChangelogUrl(manifest.changelogUrl() == null ? null : manifest.changelogUrl().toString());
                state.setLastChecked(Instant.now());
                state.setStatus(PluginUpdateStatus.UP_TO_DATE);
                state.setLastError(null);
                metadataStore.saveState(state);
                return ActionResult.failure("No newer version is currently available for " + installedPlugin.name() + ".");
            }

            Path liveJar = requireLiveJar(installedPlugin);
            Path tempDownloadDirectory = dataDirectory.resolve("downloads");
            Path downloadedFile = manifestSourceClient.downloadArtifact(config, manifest, tempDownloadDirectory);

            try {
                verifyChecksumIfNeeded(config, manifest, downloadedFile);
                Path backupPath = backupLiveJar(installedPlugin.name(), liveJar);
                Path stagedPath = stageUpdate(liveJar, installedPlugin.version(), downloadedFile, manifest);
                Instant approvalTimestamp = Instant.now();

                state.setLatestVersion(manifest.version());
                state.setSourceLabel(resolveSourceLabel(config));
                state.setManifestUrl(config.referenceUrl());
                state.setLastChecked(approvalTimestamp);
                state.setDeniedVersion(null);
                state.setApprovalTimestamp(approvalTimestamp);
                state.setBackupPath(backupPath.toString());
                state.setStagedPath(stagedPath.toString());
                state.setLastError(null);
                state.setChangelogUrl(manifest.changelogUrl() == null ? null : manifest.changelogUrl().toString());
                state.setStatus(PluginUpdateStatus.STAGED_PENDING_RESTART);

                metadataStore.saveState(state);
                metadataStore.appendHistory(new UpdateHistoryEntry(
                    installedPlugin.name(),
                    installedPlugin.version(),
                    manifest.version(),
                    resolveSourceLabel(config),
                    approvalTimestamp,
                    backupPath.toString(),
                    stagedPath.toString()
                ));

                return ActionResult.success(
                    "Staged " + installedPlugin.name() + " " + manifest.version() + " for the next restart.",
                    toPluginView(installedPlugin, state),
                    true
                );
            } finally {
                Files.deleteIfExists(downloadedFile);
            }
        } catch (Exception exception) {
            applyCheckFailedState(state, installedPlugin, config, exception.getMessage());
            try {
                metadataStore.saveState(state);
            } catch (IOException ignored) {
            }
            return ActionResult.failure("Could not stage " + installedPlugin.name() + ": " + exception.getMessage());
        }
    }

    public synchronized ActionResult denyUpdate(InstalledPlugin installedPlugin) {
        ManagedPluginConfig config = registry.find(installedPlugin).orElse(null);
        if (config == null) {
            return ActionResult.failure(installedPlugin.name() + " is not configured for update checks.");
        }

        UpdateState state = metadataStore.getState(installedPlugin.name())
            .orElseGet(() -> new UpdateState(installedPlugin.name()));
        state.setPluginName(installedPlugin.name());
        state.setInstalledVersion(installedPlugin.version());

        if (state.getStatus() == PluginUpdateStatus.STAGED_PENDING_RESTART) {
            return ActionResult.failure("A staged update is already waiting for restart.");
        }
        if (state.getLatestVersion() == null || !versionService.isNewer(installedPlugin.version(), state.getLatestVersion())) {
            return ActionResult.failure("No newer version is currently available to deny for " + installedPlugin.name() + ".");
        }

        state.setDeniedVersion(state.getLatestVersion());
        state.setSourceLabel(resolveSourceLabel(config));
        state.setManifestUrl(config.referenceUrl());
        state.setStatus(PluginUpdateStatus.DENIED_VERSION);
        state.setLastError(null);

        try {
            metadataStore.saveState(state);
            return ActionResult.success(
                "Denied " + installedPlugin.name() + " " + state.getLatestVersion() + " until a newer version appears.",
                toPluginView(installedPlugin, state),
                false
            );
        } catch (IOException exception) {
            return ActionResult.failure("Could not save the denied version: " + exception.getMessage());
        }
    }

    public synchronized ActionResult restartNow() {
        if (!hasPendingRestart()) {
            return ActionResult.failure("No staged updates are waiting for a restart.");
        }

        restartDispatcher.run();
        return ActionResult.success("Restart command dispatched.");
    }

    public synchronized ActionResult restartLater() {
        if (!hasPendingRestart()) {
            return ActionResult.failure("No staged updates are waiting for a restart.");
        }

        return ActionResult.success("Staged updates remain queued until you restart the server.");
    }

    public synchronized boolean hasPendingRestart() {
        return metadataStore.getStatesSnapshot().values().stream()
            .anyMatch(state -> state.getStatus() == PluginUpdateStatus.STAGED_PENDING_RESTART);
    }

    public synchronized List<PluginView> getPluginViews(List<InstalledPlugin> installedPlugins) {
        List<PluginView> views = new ArrayList<>();
        for (InstalledPlugin installedPlugin : installedPlugins.stream()
            .sorted(Comparator.comparing(InstalledPlugin::name, String.CASE_INSENSITIVE_ORDER))
            .toList()) {
            UpdateState state = metadataStore.getState(installedPlugin.name())
                .orElseGet(() -> defaultState(installedPlugin));
            if (!registry.isManaged(installedPlugin)
                && state.getStatus() != PluginUpdateStatus.STAGED_PENDING_RESTART) {
                state.setStatus(PluginUpdateStatus.NOT_CONFIGURED);
            }
            views.add(toPluginView(installedPlugin, state));
        }
        return views;
    }

    public synchronized Optional<PluginView> getPluginView(String pluginName, List<InstalledPlugin> installedPlugins) {
        return installedPlugins.stream()
            .filter(installedPlugin -> installedPlugin.matchesIdentifier(pluginName))
            .findFirst()
            .map(installedPlugin -> {
                UpdateState state = metadataStore.getState(installedPlugin.name())
                    .orElseGet(() -> defaultState(installedPlugin));
                if (!registry.isManaged(installedPlugin)
                    && state.getStatus() != PluginUpdateStatus.STAGED_PENDING_RESTART) {
                    state.setStatus(PluginUpdateStatus.NOT_CONFIGURED);
                }
                return toPluginView(installedPlugin, state);
            });
    }

    public synchronized List<String> buildConsoleSummary(List<InstalledPlugin> installedPlugins) {
        List<PluginView> views = getPluginViews(installedPlugins);
        long updatesAvailable = views.stream().filter(view -> view.status() == PluginUpdateStatus.UPDATE_AVAILABLE).count();
        long deniedUpdates = views.stream().filter(view -> view.status() == PluginUpdateStatus.DENIED_VERSION).count();
        long stagedUpdates = views.stream().filter(view -> view.status() == PluginUpdateStatus.STAGED_PENDING_RESTART).count();
        long failedChecks = views.stream().filter(view -> view.status() == PluginUpdateStatus.CHECK_FAILED).count();

        List<String> lines = new ArrayList<>();
        lines.add("AutoPluginUpdater summary:");
        lines.add("Installed plugins: " + views.size());
        lines.add("Updates available: " + updatesAvailable);
        lines.add("Denied versions: " + deniedUpdates);
        lines.add("Staged for restart: " + stagedUpdates);
        lines.add("Check failures: " + failedChecks);

        views.stream()
            .filter(view -> view.status() == PluginUpdateStatus.UPDATE_AVAILABLE
                || view.status() == PluginUpdateStatus.DENIED_VERSION
                || view.status() == PluginUpdateStatus.STAGED_PENDING_RESTART
                || view.status() == PluginUpdateStatus.CHECK_FAILED)
            .forEach(view -> lines.add("- " + view.pluginName() + ": " + view.status().getLabel()
                + " (installed " + valueOrPlaceholder(view.installedVersion())
                + ", latest " + valueOrPlaceholder(view.latestVersion()) + ")"));
        return lines;
    }

    private void refreshInstalledPlugin(InstalledPlugin installedPlugin, UpdateState state) throws IOException, InterruptedException {
        ManagedPluginConfig config = registry.find(installedPlugin).orElse(null);
        state.setInstalledVersion(installedPlugin.version());

        if (config == null) {
            state.setSourceLabel(null);
            state.setManifestUrl(null);
            state.setLatestVersion(null);
            state.setLastChecked(Instant.now());
            state.setLastError(null);
            state.setStatus(PluginUpdateStatus.NOT_CONFIGURED);
            return;
        }

        if (state.getStatus() == PluginUpdateStatus.STAGED_PENDING_RESTART
            && !versionService.isSameVersion(installedPlugin.version(), state.getLatestVersion())) {
            state.setSourceLabel(resolveSourceLabel(config));
            state.setManifestUrl(config.referenceUrl());
            return;
        }

        RemotePluginManifest manifest = manifestSourceClient.fetchManifest(config);
        validateManifest(installedPlugin, manifest);
        Instant now = Instant.now();

        state.setLatestVersion(manifest.version());
        state.setSourceLabel(resolveSourceLabel(config));
            state.setManifestUrl(config.referenceUrl());
        state.setLastChecked(now);
        state.setLastError(null);
        state.setChangelogUrl(manifest.changelogUrl() == null ? null : manifest.changelogUrl().toString());

        if (versionService.isNewer(installedPlugin.version(), manifest.version())) {
            if (Objects.equals(state.getDeniedVersion(), manifest.version())) {
                state.setStatus(PluginUpdateStatus.DENIED_VERSION);
            } else {
                state.setStatus(PluginUpdateStatus.UPDATE_AVAILABLE);
            }
            return;
        }

        state.setStatus(PluginUpdateStatus.UP_TO_DATE);
        if (versionService.isSameVersion(installedPlugin.version(), manifest.version())) {
            state.setStagedPath(null);
        }
    }

    private void applyCheckFailedState(UpdateState state, InstalledPlugin installedPlugin, ManagedPluginConfig config, String error) {
        state.setInstalledVersion(installedPlugin.version());
        state.setSourceLabel(config == null ? null : resolveSourceLabel(config));
        state.setManifestUrl(config == null ? null : config.referenceUrl());
        state.setLastChecked(Instant.now());
        state.setLastError(error);
        if (state.getStatus() != PluginUpdateStatus.STAGED_PENDING_RESTART) {
            state.setStatus(config == null ? PluginUpdateStatus.NOT_CONFIGURED : PluginUpdateStatus.CHECK_FAILED);
        }
    }

    private UpdateState defaultState(InstalledPlugin installedPlugin) {
        UpdateState state = new UpdateState(installedPlugin.name());
        state.setInstalledVersion(installedPlugin.version());
        state.setStatus(registry.isManaged(installedPlugin)
            ? PluginUpdateStatus.CHECK_FAILED
            : PluginUpdateStatus.NOT_CONFIGURED);
        return state;
    }

    private PluginView toPluginView(InstalledPlugin installedPlugin, UpdateState state) {
        return new PluginView(
            installedPlugin.name(),
            valueOrPlaceholder(state.getInstalledVersion() == null ? installedPlugin.version() : state.getInstalledVersion()),
            valueOrNull(state.getLatestVersion()),
            state.getStatus(),
            registry.isManaged(installedPlugin),
            installedPlugin.enabled(),
            valueOrNull(state.getSourceLabel()),
            valueOrNull(state.getManifestUrl()),
            state.getLastChecked(),
            valueOrNull(state.getDeniedVersion()),
            state.getApprovalTimestamp(),
            valueOrNull(state.getBackupPath()),
            valueOrNull(state.getStagedPath()),
            valueOrNull(state.getLastError()),
            valueOrNull(state.getChangelogUrl())
        );
    }

    private Path requireLiveJar(InstalledPlugin installedPlugin) throws IOException {
        if (installedPlugin.jarPath() == null) {
            throw new IOException("The current plugin JAR could not be resolved.");
        }

        Path liveJar = installedPlugin.jarPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(liveJar)) {
            throw new IOException("The current plugin JAR does not exist.");
        }
        if (!liveJar.startsWith(pluginsDirectory)) {
            throw new IOException("The current plugin JAR is not inside the plugins directory.");
        }

        return liveJar;
    }

    private Path backupLiveJar(String pluginName, Path liveJar) throws IOException {
        Path backupDirectory = pluginsDirectory.resolve("backup").resolve(sanitize(pluginName));
        Files.createDirectories(backupDirectory);
        String backupName = BACKUP_TIMESTAMP.format(Instant.now()) + "_" + liveJar.getFileName();
        Path backupPath = backupDirectory.resolve(backupName);
        Files.copy(liveJar, backupPath, StandardCopyOption.REPLACE_EXISTING);
        return backupPath.toAbsolutePath().normalize();
    }

    private Path stageUpdate(Path liveJar, String installedVersion, Path downloadedFile, RemotePluginManifest manifest)
        throws IOException {
        Files.createDirectories(updateDirectory);
        Path stagedPath = updateDirectory.resolve(resolveStagedFileName(liveJar, installedVersion, manifest));
        Files.copy(downloadedFile, stagedPath, StandardCopyOption.REPLACE_EXISTING);
        return stagedPath.toAbsolutePath().normalize();
    }

    private String resolveStagedFileName(Path liveJar, String installedVersion, RemotePluginManifest manifest) {
        String fallbackName = liveJar.getFileName().toString();
        String artifactName = manifest.fileName();
        if (artifactName == null || artifactName.isBlank()) {
            return rewriteVersionInFileName(fallbackName, installedVersion, manifest.version());
        }

        String candidate = artifactName.trim().replace('\\', '/');
        int lastSlash = candidate.lastIndexOf('/');
        if (lastSlash >= 0) {
            candidate = candidate.substring(lastSlash + 1);
        }

        candidate = candidate.replaceAll("[:*?\"<>|]", "_");
        if (candidate.isBlank() || !candidate.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return rewriteVersionInFileName(fallbackName, installedVersion, manifest.version());
        }

        return candidate;
    }

    private String rewriteVersionInFileName(String fileName, String installedVersion, String latestVersion) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }
        if (installedVersion == null || installedVersion.isBlank()
            || latestVersion == null || latestVersion.isBlank()
            || installedVersion.equals(latestVersion)) {
            return fileName;
        }

        int extensionIndex = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        if (extensionIndex < 0) {
            return fileName;
        }

        String baseName = fileName.substring(0, extensionIndex);
        String extension = fileName.substring(extensionIndex);
        if (!baseName.contains(installedVersion)) {
            return fileName;
        }

        return baseName.replace(installedVersion, latestVersion) + extension;
    }

    private void verifyChecksumIfNeeded(ManagedPluginConfig config, RemotePluginManifest manifest, Path downloadedFile)
        throws IOException {
        if ((manifest.sha256() == null || manifest.sha256().isBlank()) && !config.checksumRequired()) {
            return;
        }
        if (manifest.sha256() == null || manifest.sha256().isBlank()) {
            throw new IOException("The manifest does not include a SHA-256 checksum.");
        }

        String actualChecksum = sha256(downloadedFile);
        if (!actualChecksum.equalsIgnoreCase(manifest.sha256())) {
            throw new IOException("Downloaded JAR checksum did not match the manifest.");
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] content = Files.readAllBytes(file);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available on this JVM.", exception);
        }
    }

    private void validateManifest(InstalledPlugin installedPlugin, RemotePluginManifest manifest) throws IOException {
        if (manifest.name() != null && !manifestNameMatches(installedPlugin, manifest.name())) {
            throw new IOException("Manifest name " + manifest.name() + " does not match " + installedPlugin.name() + ".");
        }
    }

    private boolean manifestNameMatches(InstalledPlugin installedPlugin, String manifestName) {
        String normalizedManifestName = normalize(manifestName);
        String compactManifestName = compact(normalizedManifestName);
        for (String identifier : installedPlugin.identifiers()) {
            if (identifier.equals(normalizedManifestName)) {
                return true;
            }

            String compactIdentifier = compact(identifier);
            if (compactIdentifier.isBlank() || compactManifestName.isBlank()) {
                continue;
            }
            if (compactManifestName.equals(compactIdentifier)
                || compactManifestName.startsWith(compactIdentifier)
                || compactIdentifier.startsWith(compactManifestName)) {
                return true;
            }
        }
        return false;
    }

    private String resolveSourceLabel(ManagedPluginConfig config) {
        if (config.displaySource() != null && !config.displaySource().isBlank()) {
            return config.displaySource();
        }
        try {
            URI uri = URI.create(config.referenceUrl());
            return uri.getHost() == null ? config.referenceUrl() : uri.getHost();
        } catch (IllegalArgumentException ignored) {
            return config.referenceUrl();
        }
    }

    private static String valueOrPlaceholder(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String valueOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return normalize(value).replaceAll("[^a-z0-9]", "");
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
