package me.bestdad.autoPluginUpdater.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.bestdad.autoPluginUpdater.config.ManagedPluginConfig;
import me.bestdad.autoPluginUpdater.config.ManagedPluginSourceType;
import me.bestdad.autoPluginUpdater.domain.RemotePluginManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpManifestSourceClientTest {
    private HttpServer server;
    private HttpManifestSourceClient client;
    private String baseUrl;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new HttpManifestSourceClient(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchesManifestAndDownloadsJar() throws Exception {
        byte[] jarBytes = "test-jar".getBytes(StandardCharsets.UTF_8);
        server.createContext("/manifest.json", exchange -> respondJson(exchange, """
            {
              "name": "ExamplePlugin",
              "version": "2.0.0",
              "downloadUrl": "%s/download.jar",
              "sha256": "ignored",
              "changelogUrl": "%s/changelog",
              "publishedAt": "2026-04-17T21:13:00Z"
            }
            """.formatted(baseUrl, baseUrl)));
        server.createContext("/download.jar", exchange -> respondBytes(exchange, jarBytes, "application/java-archive"));

        ManagedPluginConfig config = new ManagedPluginConfig(
            "ExamplePlugin",
            ManagedPluginSourceType.HTTP_MANIFEST,
            baseUrl + "/manifest.json",
            "",
            "",
            Map.of(),
            "Example",
            false
        );
        RemotePluginManifest manifest = client.fetchManifest(config);
        Path downloaded = client.downloadArtifact(config, manifest, tempDir);

        assertEquals("ExamplePlugin", manifest.name());
        assertEquals("2.0.0", manifest.version());
        assertEquals(null, manifest.fileName());
        assertEquals(Instant.parse("2026-04-17T21:13:00Z"), manifest.publishedAt());
        assertTrue(Files.exists(downloaded));
        assertEquals("test-jar", Files.readString(downloaded));
    }

    @Test
    void rejectsMalformedManifest() {
        server.createContext("/bad.json", exchange -> respondJson(exchange, "{\"version\":\"\",\"downloadUrl\":\"\"}"));
        ManagedPluginConfig config = new ManagedPluginConfig(
            "ExamplePlugin",
            ManagedPluginSourceType.HTTP_MANIFEST,
            baseUrl + "/bad.json",
            "",
            "",
            Map.of(),
            "",
            false
        );

        assertThrows(IOException.class, () -> client.fetchManifest(config));
    }

    @Test
    void parsesSpigotResourcePageAndResolvesRelativeDownloadUrl() throws Exception {
        byte[] jarBytes = "spigot-jar".getBytes(StandardCharsets.UTF_8);
        server.createContext("/resources/example.1234/", exchange -> respondHtml(exchange, """
            <html>
              <body>
                <h1>ExamplePlugin <span class="muted">2.5.0</span></h1>
                <a class="button" href="download?version=42">Download Now</a>
              </body>
            </html>
            """));
        server.createContext("/resources/example.1234/download", exchange -> respondBytes(exchange, jarBytes, "application/java-archive"));

        ManagedPluginConfig config = new ManagedPluginConfig(
            "ExamplePlugin",
            ManagedPluginSourceType.SPIGOT_RESOURCE,
            "",
            baseUrl + "/resources/example.1234/",
            "",
            Map.of(),
            "SpigotMC",
            false
        );

        RemotePluginManifest manifest = client.fetchManifest(config);
        Path downloaded = client.downloadArtifact(config, manifest, tempDir);

        assertEquals("ExamplePlugin", manifest.name());
        assertEquals("2.5.0", manifest.version());
        assertEquals(null, manifest.fileName());
        assertEquals(baseUrl + "/resources/example.1234/download?version=42", manifest.downloadUrl().toString());
        assertEquals("spigot-jar", Files.readString(downloaded));
    }

    @Test
    void parsesModrinthVersionsApiAndDownloadsPrimaryFile() throws Exception {
        byte[] jarBytes = "modrinth-jar".getBytes(StandardCharsets.UTF_8);
        server.createContext("/modrinth/project/example/version", exchange -> respondJson(exchange, """
            [
              {
                "name": "ExamplePlugin 2.7.0",
                "version_number": "2.7.0",
                "status": "listed",
                "version_type": "release",
                "date_published": "2026-04-18T00:00:00Z",
                "files": [
                  {
                    "url": "%s/modrinth/files/example-2.7.0.jar",
                    "primary": true,
                    "filename": "ExamplePlugin-2.7.0.jar"
                  }
                ]
              }
            ]
            """.formatted(baseUrl)));
        server.createContext("/modrinth/files/example-2.7.0.jar", exchange -> respondBytes(exchange, jarBytes, "application/java-archive"));

        ManagedPluginConfig config = new ManagedPluginConfig(
            "ExamplePlugin",
            ManagedPluginSourceType.MODRINTH_PROJECT,
            baseUrl + "/modrinth/project/example/version",
            baseUrl + "/plugin/example",
            "",
            Map.of(),
            "Modrinth",
            false
        );

        RemotePluginManifest manifest = client.fetchManifest(config);
        Path downloaded = client.downloadArtifact(config, manifest, tempDir);

        assertEquals("ExamplePlugin", manifest.name());
        assertEquals("2.7.0", manifest.version());
        assertEquals("ExamplePlugin-2.7.0.jar", manifest.fileName());
        assertEquals(Instant.parse("2026-04-18T00:00:00Z"), manifest.publishedAt());
        assertEquals(baseUrl + "/plugin/example", manifest.changelogUrl().toString());
        assertEquals("modrinth-jar", Files.readString(downloaded));
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
}
