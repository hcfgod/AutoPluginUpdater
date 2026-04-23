package me.bestdad.autoPluginUpdater.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.bestdad.autoPluginUpdater.config.ManagedPluginConfig;
import me.bestdad.autoPluginUpdater.config.ManagedPluginRegistry;
import me.bestdad.autoPluginUpdater.domain.InstalledPlugin;
import me.bestdad.autoPluginUpdater.domain.PluginUpdateStatus;
import me.bestdad.autoPluginUpdater.domain.PluginView;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginUpdateEngineTest {
    private HttpServer server;
    private String baseUrl;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void refreshesAndStagesApprovedUpdateFromHttpSource() throws Exception {
        byte[] liveJarBytes = "old-jar".getBytes(StandardCharsets.UTF_8);
        byte[] newJarBytes = "new-jar".getBytes(StandardCharsets.UTF_8);

        Path pluginsDir = tempDir.resolve("plugins");
        Path dataDir = tempDir.resolve("data");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("ExamplePlugin-1.0.0.jar");
        Files.write(liveJar, liveJarBytes);

        String checksum = sha256(newJarBytes);
        server.createContext("/manifest.json", exchange -> respondJson(exchange, """
            {
              "name": "ExamplePlugin",
              "version": "2.0.0",
              "downloadUrl": "%s/download.jar",
              "sha256": "%s",
              "changelogUrl": "%s/changelog"
            }
            """.formatted(baseUrl, checksum, baseUrl)));
        server.createContext("/download.jar", exchange -> respondBytes(exchange, newJarBytes, "application/java-archive"));

        PluginUpdateEngine engine = createEngine(dataDir, pluginsDir, updateDir, true, new AtomicBoolean(false));
        InstalledPlugin installedPlugin = new InstalledPlugin("ExamplePlugin", "1.0.0", true, liveJar);

        assertTrue(engine.refreshAll(List.of(installedPlugin)).success());

        PluginView refreshedView = engine.getPluginView("ExamplePlugin", List.of(installedPlugin)).orElseThrow();
        assertEquals(PluginUpdateStatus.UPDATE_AVAILABLE, refreshedView.status());
        assertEquals("2.0.0", refreshedView.latestVersion());

        assertTrue(engine.approveUpdate(installedPlugin).success());

        Path stagedJar = updateDir.resolve("ExamplePlugin-2.0.0.jar");
        assertTrue(Files.exists(stagedJar));
        assertEquals("new-jar", Files.readString(stagedJar));
        assertFalse(Files.exists(updateDir.resolve("ExamplePlugin-1.0.0.jar")));

        Path backupDir = pluginsDir.resolve("backup").resolve("ExamplePlugin");
        assertTrue(Files.exists(backupDir));
        assertEquals(1L, Files.list(backupDir).count());

        PluginView stagedView = engine.getPluginView("ExamplePlugin", List.of(installedPlugin)).orElseThrow();
        assertEquals(PluginUpdateStatus.STAGED_PENDING_RESTART, stagedView.status());
        assertNotNull(stagedView.backupPath());
        assertNotNull(stagedView.stagedPath());
    }

    @Test
    void denyUpdateSuppressesSameVersionUntilNewerVersionAppears() throws Exception {
        byte[] downloadBytes = "new-jar".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> latestVersion = new AtomicReference<>("2.0.0");

        Path pluginsDir = tempDir.resolve("plugins");
        Path dataDir = tempDir.resolve("data");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("ExamplePlugin.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/manifest.json", exchange -> respondJson(exchange, """
            {
              "name": "ExamplePlugin",
              "version": "%s",
              "downloadUrl": "%s/download.jar"
            }
            """.formatted(latestVersion.get(), baseUrl)));
        server.createContext("/download.jar", exchange -> respondBytes(exchange, downloadBytes, "application/java-archive"));

        PluginUpdateEngine engine = createEngine(dataDir, pluginsDir, updateDir, false, new AtomicBoolean(false));
        InstalledPlugin installedPlugin = new InstalledPlugin("ExamplePlugin", "1.0.0", true, liveJar);

        engine.refreshAll(List.of(installedPlugin));
        assertTrue(engine.denyUpdate(installedPlugin).success());

        engine.refreshAll(List.of(installedPlugin));
        assertEquals(PluginUpdateStatus.DENIED_VERSION, engine.getPluginView("ExamplePlugin", List.of(installedPlugin)).orElseThrow().status());

        latestVersion.set("2.1.0");
        engine.refreshAll(List.of(installedPlugin));
        assertEquals(PluginUpdateStatus.UPDATE_AVAILABLE, engine.getPluginView("ExamplePlugin", List.of(installedPlugin)).orElseThrow().status());
    }

    @Test
    void refreshesAndStagesApprovedUpdateFromSpigotResourcePage() throws Exception {
        byte[] newJarBytes = "spigot-new-jar".getBytes(StandardCharsets.UTF_8);

        Path pluginsDir = tempDir.resolve("plugins-spigot");
        Path dataDir = tempDir.resolve("data-spigot");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("VaultUnlocked-2.18.0.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/resources/vaultunlocked.117277/", exchange -> respondHtml(exchange, """
            <html>
              <body>
                <h1>VaultUnlocked <span class="muted">2.19.0</span></h1>
                <a href="download?version=625066">Download Now</a>
              </body>
            </html>
            """));
        server.createContext("/resources/vaultunlocked.117277/download", exchange -> respondBytes(exchange, newJarBytes, "application/java-archive"));

        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("managed-plugins.VaultUnlocked.source-type", "spigot-resource");
        configuration.set("managed-plugins.VaultUnlocked.resource-url", baseUrl + "/resources/vaultunlocked.117277/");
        configuration.set("managed-plugins.VaultUnlocked.display-source", "SpigotMC");
        configuration.set("managed-plugins.VaultUnlocked.headers", Map.of());

        PluginUpdateEngine engine = new PluginUpdateEngine(
            ManagedPluginRegistry.fromConfig(configuration),
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5)),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> {}
        );

        InstalledPlugin installedPlugin = new InstalledPlugin("Vault", "2.18.0", true, liveJar);
        assertTrue(engine.refreshAll(List.of(installedPlugin)).success());
        assertEquals(PluginUpdateStatus.UPDATE_AVAILABLE, engine.getPluginView("VaultUnlocked", List.of(installedPlugin)).orElseThrow().status());
        assertTrue(engine.approveUpdate(installedPlugin).success());
        assertEquals("spigot-new-jar", Files.readString(updateDir.resolve("VaultUnlocked-2.19.0.jar")));
        assertFalse(Files.exists(updateDir.resolve("VaultUnlocked-2.18.0.jar")));
    }

    @Test
    void acceptsSpigotResourceTitlesThatStartWithPluginName() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins-spigot-title");
        Path dataDir = tempDir.resolve("data-spigot-title");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("OldCombatMechanics.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/resources/oldcombatmechanics.19510/", exchange -> respondHtml(exchange, """
            <html>
              <body>
                <h1>OldCombatMechanics - Disable 1.9 hit cooldown <span class="muted">1.12.8</span></h1>
                <a href="download?version=12345">Download Now</a>
              </body>
            </html>
            """));

        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("managed-plugins.OldCombatMechanics.source-type", "spigot-resource");
        configuration.set("managed-plugins.OldCombatMechanics.resource-url", baseUrl + "/resources/oldcombatmechanics.19510/");
        configuration.set("managed-plugins.OldCombatMechanics.display-source", "SpigotMC");
        configuration.set("managed-plugins.OldCombatMechanics.headers", Map.of());

        PluginUpdateEngine engine = new PluginUpdateEngine(
            ManagedPluginRegistry.fromConfig(configuration),
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5)),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> {}
        );

        InstalledPlugin installedPlugin = new InstalledPlugin("OldCombatMechanics", "1.12.7", true, liveJar);
        assertTrue(engine.refreshAll(List.of(installedPlugin)).success());
        assertEquals(
            PluginUpdateStatus.UPDATE_AVAILABLE,
            engine.getPluginView("OldCombatMechanics", List.of(installedPlugin)).orElseThrow().status()
        );
    }

    @Test
    void fallsBackToSpigetWhenSpigotDirectDownloadIsBlocked() throws Exception {
        byte[] newJarBytes = "spiget-new-jar".getBytes(StandardCharsets.UTF_8);

        Path pluginsDir = tempDir.resolve("plugins-spiget-fallback");
        Path dataDir = tempDir.resolve("data-spiget-fallback");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("VaultUnlocked-2.18.0.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/resources/vaultunlocked.117277/", exchange -> respondHtml(exchange, """
            <html>
              <body>
                <h1>VaultUnlocked <span class="muted">2.19.0</span></h1>
                <a href="download?version=625066">Download Now</a>
              </body>
            </html>
            """));
        server.createContext("/resources/vaultunlocked.117277/download", exchange -> respondStatus(exchange, 403, "challenge"));
        server.createContext("/api/spiget/resources/117277/versions/latest", exchange -> respondJson(exchange, """
            {
              "name": "2.19.0",
              "id": 625066
            }
            """));
        server.createContext("/api/spiget/resources/117277/download", exchange -> respondBytes(exchange, newJarBytes, "application/java-archive"));

        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("managed-plugins.VaultUnlocked.source-type", "spigot-resource");
        configuration.set("managed-plugins.VaultUnlocked.resource-url", baseUrl + "/resources/vaultunlocked.117277/");
        configuration.set("managed-plugins.VaultUnlocked.display-source", "SpigotMC");
        configuration.set("managed-plugins.VaultUnlocked.headers", Map.of());

        PluginUpdateEngine engine = new PluginUpdateEngine(
            ManagedPluginRegistry.fromConfig(configuration),
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5), baseUrl + "/api/spiget/resources/"),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> {}
        );

        InstalledPlugin installedPlugin = new InstalledPlugin("Vault", "2.18.0", true, liveJar);
        assertTrue(engine.refreshAll(List.of(installedPlugin)).success());
        assertEquals(PluginUpdateStatus.UPDATE_AVAILABLE, engine.getPluginView("VaultUnlocked", List.of(installedPlugin)).orElseThrow().status());
        assertTrue(engine.approveUpdate(installedPlugin).success());
        assertEquals("spiget-new-jar", Files.readString(updateDir.resolve("VaultUnlocked-2.19.0.jar")));
        assertFalse(Files.exists(updateDir.resolve("VaultUnlocked-2.18.0.jar")));
    }

    @Test
    void stagesModrinthUpdateUsingDownloadedArtifactFilename() throws Exception {
        byte[] newJarBytes = "modrinth-new-jar".getBytes(StandardCharsets.UTF_8);

        Path pluginsDir = tempDir.resolve("plugins-modrinth");
        Path dataDir = tempDir.resolve("data-modrinth");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("VaultUnlocked-2.18.0.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/modrinth/project/vaultunlocked/version", exchange -> respondJson(exchange, """
            [
              {
                "name": "VaultUnlocked 2.19.0",
                "version_number": "2.19.0",
                "status": "listed",
                "version_type": "release",
                "date_published": "2026-04-18T00:00:00Z",
                "files": [
                  {
                    "url": "%s/modrinth/files/vaultunlocked-2.19.0.jar",
                    "primary": true,
                    "filename": "VaultUnlocked-2.19.0.jar"
                  }
                ]
              }
            ]
            """.formatted(baseUrl)));
        server.createContext("/modrinth/files/vaultunlocked-2.19.0.jar", exchange -> respondBytes(exchange, newJarBytes, "application/java-archive"));

        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("managed-plugins.VaultUnlocked.source-type", "modrinth-project");
        configuration.set("managed-plugins.VaultUnlocked.manifest-url", baseUrl + "/modrinth/project/vaultunlocked/version");
        configuration.set("managed-plugins.VaultUnlocked.resource-url", baseUrl + "/plugin/vaultunlocked");
        configuration.set("managed-plugins.VaultUnlocked.display-source", "Modrinth");
        configuration.set("managed-plugins.VaultUnlocked.headers", Map.of());

        PluginUpdateEngine engine = new PluginUpdateEngine(
            ManagedPluginRegistry.fromConfig(configuration),
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5)),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> {}
        );

        InstalledPlugin installedPlugin = new InstalledPlugin("Vault", "2.18.0", true, liveJar);
        assertTrue(engine.refreshAll(List.of(installedPlugin)).success());
        assertEquals(PluginUpdateStatus.UPDATE_AVAILABLE, engine.getPluginView("VaultUnlocked", List.of(installedPlugin)).orElseThrow().status());

        assertTrue(engine.approveUpdate(installedPlugin).success());
        assertEquals("modrinth-new-jar", Files.readString(updateDir.resolve("VaultUnlocked-2.19.0.jar")));
        assertFalse(Files.exists(updateDir.resolve("VaultUnlocked-2.18.0.jar")));

        PluginView stagedView = engine.getPluginView("VaultUnlocked", List.of(installedPlugin)).orElseThrow();
        assertTrue(stagedView.stagedPath().endsWith("VaultUnlocked-2.19.0.jar"));
    }

    @Test
    void checksumMismatchBlocksApproval() throws Exception {
        byte[] newJarBytes = "new-jar".getBytes(StandardCharsets.UTF_8);

        Path pluginsDir = tempDir.resolve("plugins");
        Path dataDir = tempDir.resolve("data");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("ExamplePlugin.jar");
        Files.writeString(liveJar, "old-jar");

        server.createContext("/manifest.json", exchange -> respondJson(exchange, """
            {
              "name": "ExamplePlugin",
              "version": "2.0.0",
              "downloadUrl": "%s/download.jar",
              "sha256": "deadbeef"
            }
            """.formatted(baseUrl)));
        server.createContext("/download.jar", exchange -> respondBytes(exchange, newJarBytes, "application/java-archive"));

        PluginUpdateEngine engine = createEngine(dataDir, pluginsDir, updateDir, true, new AtomicBoolean(false));
        InstalledPlugin installedPlugin = new InstalledPlugin("ExamplePlugin", "1.0.0", true, liveJar);

        assertFalse(engine.approveUpdate(installedPlugin).success());
        assertFalse(Files.exists(updateDir.resolve(liveJar.getFileName())));
    }

    @Test
    void reconcilesStagedUpdateOnNextStartup() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path dataDir = tempDir.resolve("data");
        Path updateDir = pluginsDir.resolve("update");
        Files.createDirectories(pluginsDir);
        Path liveJar = pluginsDir.resolve("ExamplePlugin.jar");
        Files.writeString(liveJar, "new-jar");

        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        var state = new me.bestdad.autoPluginUpdater.domain.UpdateState("ExamplePlugin");
        state.setInstalledVersion("1.0.0");
        state.setLatestVersion("2.0.0");
        state.setStatus(PluginUpdateStatus.STAGED_PENDING_RESTART);
        state.setStagedPath(updateDir.resolve("ExamplePlugin.jar").toString());
        metadataStore.saveState(state);

        ManagedPluginRegistry registry = registryFor(baseUrl + "/manifest.json", false);
        PluginUpdateEngine engine = new PluginUpdateEngine(
            registry,
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5)),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> {}
        );

        InstalledPlugin installedPlugin = new InstalledPlugin("ExamplePlugin", "2.0.0", true, liveJar);
        engine.reconcileStartupState(List.of(installedPlugin));

        assertEquals(PluginUpdateStatus.UP_TO_DATE, metadataStore.getState("ExamplePlugin").orElseThrow().getStatus());
        assertEquals(null, metadataStore.getState("ExamplePlugin").orElseThrow().getStagedPath());
    }

    private PluginUpdateEngine createEngine(
        Path dataDir,
        Path pluginsDir,
        Path updateDir,
        boolean checksumRequired,
        AtomicBoolean restartDispatched
    ) throws Exception {
        UpdateMetadataStore metadataStore = new UpdateMetadataStore(dataDir.resolve("updates.yml"));
        metadataStore.load();
        return new PluginUpdateEngine(
            registryFor(baseUrl + "/manifest.json", checksumRequired),
            metadataStore,
            new HttpManifestSourceClient(java.time.Duration.ofSeconds(5)),
            new VersionService(),
            dataDir,
            pluginsDir,
            updateDir,
            () -> restartDispatched.set(true)
        );
    }

    private ManagedPluginRegistry registryFor(String manifestUrl, boolean checksumRequired) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("managed-plugins.ExamplePlugin.manifest-url", manifestUrl);
        configuration.set("managed-plugins.ExamplePlugin.checksum-required", checksumRequired);
        configuration.set("managed-plugins.ExamplePlugin.display-source", "Test Source");
        configuration.set("managed-plugins.ExamplePlugin.headers", Map.of());
        return ManagedPluginRegistry.fromConfig(configuration);
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }

    private void respondJson(HttpExchange exchange, String response) throws IOException {
        respondBytes(exchange, response.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void respondHtml(HttpExchange exchange, String response) throws IOException {
        respondBytes(exchange, response.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    private void respondBytes(HttpExchange exchange, byte[] response, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private void respondStatus(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
