package me.bestdad.autoPluginUpdater.domain;

import java.time.Instant;

public final class UpdateState {
    private String pluginName;
    private String installedVersion;
    private String latestVersion;
    private PluginUpdateStatus status = PluginUpdateStatus.NOT_CONFIGURED;
    private String sourceLabel;
    private String manifestUrl;
    private Instant lastChecked;
    private String deniedVersion;
    private Instant approvalTimestamp;
    private String backupPath;
    private String stagedPath;
    private String lastError;
    private String changelogUrl;

    public UpdateState(String pluginName) {
        this.pluginName = pluginName;
    }

    public UpdateState copy() {
        UpdateState copy = new UpdateState(pluginName);
        copy.installedVersion = installedVersion;
        copy.latestVersion = latestVersion;
        copy.status = status;
        copy.sourceLabel = sourceLabel;
        copy.manifestUrl = manifestUrl;
        copy.lastChecked = lastChecked;
        copy.deniedVersion = deniedVersion;
        copy.approvalTimestamp = approvalTimestamp;
        copy.backupPath = backupPath;
        copy.stagedPath = stagedPath;
        copy.lastError = lastError;
        copy.changelogUrl = changelogUrl;
        return copy;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public PluginUpdateStatus getStatus() {
        return status;
    }

    public void setStatus(PluginUpdateStatus status) {
        this.status = status;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getManifestUrl() {
        return manifestUrl;
    }

    public void setManifestUrl(String manifestUrl) {
        this.manifestUrl = manifestUrl;
    }

    public Instant getLastChecked() {
        return lastChecked;
    }

    public void setLastChecked(Instant lastChecked) {
        this.lastChecked = lastChecked;
    }

    public String getDeniedVersion() {
        return deniedVersion;
    }

    public void setDeniedVersion(String deniedVersion) {
        this.deniedVersion = deniedVersion;
    }

    public Instant getApprovalTimestamp() {
        return approvalTimestamp;
    }

    public void setApprovalTimestamp(Instant approvalTimestamp) {
        this.approvalTimestamp = approvalTimestamp;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(String backupPath) {
        this.backupPath = backupPath;
    }

    public String getStagedPath() {
        return stagedPath;
    }

    public void setStagedPath(String stagedPath) {
        this.stagedPath = stagedPath;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getChangelogUrl() {
        return changelogUrl;
    }

    public void setChangelogUrl(String changelogUrl) {
        this.changelogUrl = changelogUrl;
    }
}
