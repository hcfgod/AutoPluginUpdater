package me.bestdad.autoPluginUpdater.domain;

import java.net.URI;
import java.time.Instant;

public record RemotePluginManifest(
    String name,
    String version,
    URI downloadUrl,
    String fileName,
    String sha256,
    URI changelogUrl,
    Instant publishedAt
) {
}
