package me.bestdad.autoPluginUpdater.domain;

import java.time.Instant;

public record PluginView(
    String pluginName,
    String installedVersion,
    String latestVersion,
    PluginUpdateStatus status,
    boolean managed,
    boolean enabled,
    String sourceLabel,
    String manifestUrl,
    Instant lastChecked,
    String deniedVersion,
    Instant approvalTimestamp,
    String backupPath,
    String stagedPath,
    String lastError,
    String changelogUrl
) {

    public boolean hasUpdateCandidate() {
        return status == PluginUpdateStatus.UPDATE_AVAILABLE || status == PluginUpdateStatus.DENIED_VERSION;
    }

    public boolean canApprove() {
        return managed && (status == PluginUpdateStatus.UPDATE_AVAILABLE || status == PluginUpdateStatus.DENIED_VERSION);
    }

    public boolean canDeny() {
        return managed && status == PluginUpdateStatus.UPDATE_AVAILABLE;
    }

    public boolean hasPendingRestart() {
        return status == PluginUpdateStatus.STAGED_PENDING_RESTART;
    }
}
