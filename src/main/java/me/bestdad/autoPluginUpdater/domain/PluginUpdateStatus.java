package me.bestdad.autoPluginUpdater.domain;

public enum PluginUpdateStatus {
    NOT_CONFIGURED("Not configured"),
    UP_TO_DATE("Up to date"),
    UPDATE_AVAILABLE("Update available"),
    DENIED_VERSION("Denied version"),
    STAGED_PENDING_RESTART("Staged, restart needed"),
    CHECK_FAILED("Check failed");

    private final String label;

    PluginUpdateStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
