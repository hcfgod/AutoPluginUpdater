package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.domain.PluginUpdateStatus;
import me.bestdad.autoPluginUpdater.domain.UpdateHistoryEntry;
import me.bestdad.autoPluginUpdater.domain.UpdateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UpdateMetadataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsPluginStateAndHistory() throws Exception {
        Path metadataFile = tempDir.resolve("updates.yml");
        UpdateMetadataStore store = new UpdateMetadataStore(metadataFile);
        store.load();

        UpdateState state = new UpdateState("ExamplePlugin");
        state.setInstalledVersion("1.0.0");
        state.setLatestVersion("2.0.0");
        state.setStatus(PluginUpdateStatus.STAGED_PENDING_RESTART);
        state.setSourceLabel("Example CDN");
        state.setManifestUrl("https://example.com/manifest.json");
        state.setLastChecked(Instant.parse("2026-04-17T21:13:00Z"));
        state.setDeniedVersion("1.9.0");
        state.setApprovalTimestamp(Instant.parse("2026-04-17T21:15:00Z"));
        state.setBackupPath("plugins/backup/ExamplePlugin.jar");
        state.setStagedPath("plugins/update/ExamplePlugin.jar");
        state.setLastError("none");
        state.setChangelogUrl("https://example.com/changelog");
        store.saveState(state);

        store.appendHistory(new UpdateHistoryEntry(
            "ExamplePlugin",
            "1.0.0",
            "2.0.0",
            "Example CDN",
            Instant.parse("2026-04-17T21:15:00Z"),
            "plugins/backup/ExamplePlugin.jar",
            "plugins/update/ExamplePlugin.jar"
        ));

        UpdateMetadataStore reloadedStore = new UpdateMetadataStore(metadataFile);
        reloadedStore.load();

        UpdateState reloaded = reloadedStore.getState("ExamplePlugin").orElseThrow();
        assertEquals("1.0.0", reloaded.getInstalledVersion());
        assertEquals("2.0.0", reloaded.getLatestVersion());
        assertEquals(PluginUpdateStatus.STAGED_PENDING_RESTART, reloaded.getStatus());
        assertEquals("Example CDN", reloaded.getSourceLabel());
        assertEquals("https://example.com/changelog", reloaded.getChangelogUrl());
        assertEquals(1, reloadedStore.getHistorySnapshot().size());
        assertNotNull(reloadedStore.getHistorySnapshot().getFirst().approvalTimestamp());
    }
}
