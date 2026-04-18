package me.bestdad.autoPluginUpdater.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionServiceTest {
    private final VersionService versionService = new VersionService();

    @Test
    void comparesSemanticVersions() {
        assertTrue(versionService.isNewer("1.0.0", "1.1.0"));
        assertFalse(versionService.isNewer("1.2.0", "1.1.9"));
        assertTrue(versionService.isSameVersion("2.0.0", "2.0.0"));
    }
}
