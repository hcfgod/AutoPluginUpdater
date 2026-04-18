package me.bestdad.autoPluginUpdater.domain;

import java.time.Instant;

public record UpdateHistoryEntry(
    String pluginName,
    String oldVersion,
    String newVersion,
    String downloadSource,
    Instant approvalTimestamp,
    String backupPath,
    String stagedPath
) {
}
