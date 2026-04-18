package me.bestdad.autoPluginUpdater.service;

import me.bestdad.autoPluginUpdater.config.ManagedPluginConfig;
import me.bestdad.autoPluginUpdater.domain.RemotePluginManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface ManifestSourceClient {
    RemotePluginManifest fetchManifest(ManagedPluginConfig config) throws IOException, InterruptedException;

    Path downloadArtifact(ManagedPluginConfig config, RemotePluginManifest manifest, Path targetDirectory)
        throws IOException, InterruptedException;
}
