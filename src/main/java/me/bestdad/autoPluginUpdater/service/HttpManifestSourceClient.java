package me.bestdad.autoPluginUpdater.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.bestdad.autoPluginUpdater.config.ManagedPluginConfig;
import me.bestdad.autoPluginUpdater.config.ManagedPluginSourceType;
import me.bestdad.autoPluginUpdater.domain.RemotePluginManifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpManifestSourceClient implements ManifestSourceClient {
    private static final String DEFAULT_SPIGET_API_BASE = "https://api.spiget.org/v2/resources/";
    private static final Pattern SPIGOT_TITLE_PATTERN = Pattern.compile(
        "<h1>\\s*(?<name>[^<]+?)\\s*<span[^>]*>\\s*(?<version>[^<]+?)\\s*</span>\\s*</h1>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SPIGOT_VERSION_PATTERN = Pattern.compile(
        "<h3>\\s*Version\\s+(?<version>[^<]+?)\\s*</h3>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SPIGOT_DOWNLOAD_PATTERN = Pattern.compile(
        "href=\"(?<href>[^\"]*download\\?version=\\d+[^\"]*)\"",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPIGOT_RESOURCE_ID_PATTERN = Pattern.compile(
        "/resources/(?:[^./]+\\.)?(?<id>\\d+)(?:[/?#]|$)",
        Pattern.CASE_INSENSITIVE
    );
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 AutoPluginUpdater/0.1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final String spigetApiBase;

    public HttpManifestSourceClient(Duration timeout) {
        this(timeout, DEFAULT_SPIGET_API_BASE);
    }

    HttpManifestSourceClient(Duration timeout, String spigetApiBase) {
        this.timeout = timeout;
        this.spigetApiBase = spigetApiBase.endsWith("/") ? spigetApiBase : spigetApiBase + "/";
        this.httpClient = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public RemotePluginManifest fetchManifest(ManagedPluginConfig config) throws IOException, InterruptedException {
        return switch (config.sourceType()) {
            case HTTP_MANIFEST -> fetchHttpManifest(config);
            case SPIGOT_RESOURCE -> fetchSpigotResource(config);
            case MODRINTH_PROJECT -> fetchModrinthProject(config);
        };
    }

    @Override
    public Path downloadArtifact(ManagedPluginConfig config, RemotePluginManifest manifest, Path targetDirectory)
        throws IOException, InterruptedException {
        Files.createDirectories(targetDirectory);
        Path tempFile = Files.createTempFile(targetDirectory, sanitize(config.pluginName()) + "-", ".jar");

        URI downloadUri = manifest.downloadUrl();
        if (downloadUri == null && !config.downloadUrl().isBlank()) {
            downloadUri = URI.create(config.downloadUrl());
        }
        if (downloadUri == null) {
            Files.deleteIfExists(tempFile);
            throw new IOException("No download URL is available for " + config.pluginName() + ".");
        }

        try {
            DownloadResult directResult = downloadToFile(downloadUri, tempFile, config.headers(), refererFor(config, downloadUri));
            if (!directResult.success()) {
                DownloadResult fallbackResult = maybeDownloadViaSpiget(config, manifest, tempFile, directResult.statusCode());
                if (!fallbackResult.success()) {
                    throw buildDownloadFailure(config, manifest, directResult.statusCode(), fallbackResult);
                }
            }
        } catch (IOException exception) {
            Files.deleteIfExists(tempFile);
            throw exception;
        }

        return tempFile;
    }

    private DownloadResult downloadToFile(URI uri, Path targetFile, Map<String, String> headers, String referer)
        throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .GET();
        if (referer != null && !referer.isBlank()) {
            requestBuilder.header("Referer", referer);
        }

        HttpRequest request = applyHeaders(requestBuilder, headers).build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                return DownloadResult.failure(response.statusCode(), uri);
            }
        }

        try (InputStream inputStream = response.body()) {
            Files.copy(inputStream, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return DownloadResult.success(uri);
    }

    private DownloadResult maybeDownloadViaSpiget(
        ManagedPluginConfig config,
        RemotePluginManifest manifest,
        Path targetFile,
        int directStatusCode
    ) throws IOException, InterruptedException {
        if (config.sourceType() != ManagedPluginSourceType.SPIGOT_RESOURCE || directStatusCode < 400) {
            return DownloadResult.skippedResult();
        }

        Optional<String> resourceId = parseSpigotResourceId(config);
        if (resourceId.isEmpty()) {
            return DownloadResult.skippedResult();
        }

        SpigetVersionPayload latestVersion = fetchSpigetLatestVersion(resourceId.get());
        if (latestVersion == null || isBlank(latestVersion.name())) {
            return DownloadResult.failure(-1, URI.create(spigetApiBase + resourceId.get() + "/versions/latest"));
        }
        if (!isSameSpigotVersion(latestVersion.name(), manifest.version())) {
            return DownloadResult.versionMismatch(latestVersion.name(), URI.create(spigetApiBase + resourceId.get() + "/download"));
        }

        URI spigetDownloadUri = URI.create(spigetApiBase + resourceId.get() + "/download");
        return downloadToFile(spigetDownloadUri, targetFile, userAgentOnlyHeaders(config.headers()), null);
    }

    private IOException buildDownloadFailure(
        ManagedPluginConfig config,
        RemotePluginManifest manifest,
        int directStatusCode,
        DownloadResult fallbackResult
    ) {
        if (config.sourceType() != ManagedPluginSourceType.SPIGOT_RESOURCE || fallbackResult.skipped()) {
            return new IOException("Download request failed with HTTP " + directStatusCode);
        }
        if (fallbackResult.versionMismatchVersion() != null) {
            return new IOException(
                "Download request failed with HTTP " + directStatusCode
                    + ". Spiget fallback still reports version " + fallbackResult.versionMismatchVersion()
                    + " while Spigot shows " + manifest.version() + "."
            );
        }
        if (fallbackResult.statusCode() > 0) {
            return new IOException(
                "Download request failed with HTTP " + directStatusCode
                    + ". Spiget fallback returned HTTP " + fallbackResult.statusCode() + "."
            );
        }
        return new IOException(
            "Download request failed with HTTP " + directStatusCode
                + ". Spiget fallback was unavailable for this resource."
        );
    }

    private RemotePluginManifest fetchHttpManifest(ManagedPluginConfig config) throws IOException, InterruptedException {
        HttpRequest request = applyHeaders(HttpRequest.newBuilder(URI.create(config.manifestUrl()))
            .timeout(timeout)
            .GET(), config.headers())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Manifest request failed with HTTP " + response.statusCode());
        }

        ManifestPayload payload = objectMapper.readValue(response.body(), ManifestPayload.class);
        if (isBlank(payload.version()) || isBlank(payload.downloadUrl())) {
            throw new IOException("Manifest is missing required fields.");
        }

        URI changelog = isBlank(payload.changelogUrl()) ? null : URI.create(payload.changelogUrl());
        return new RemotePluginManifest(
            blankToNull(payload.name()),
            payload.version().trim(),
            URI.create(payload.downloadUrl().trim()),
            blankToNull(payload.fileName()),
            blankToNull(payload.sha256()),
            changelog,
            payload.publishedAt()
        );
    }

    private RemotePluginManifest fetchSpigotResource(ManagedPluginConfig config) throws IOException, InterruptedException {
        HttpRequest request = applyHeaders(HttpRequest.newBuilder(URI.create(config.resourceUrl()))
            .timeout(timeout)
            .GET(), config.headers())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Spigot resource request failed with HTTP " + response.statusCode());
        }

        String html = response.body();
        String version = parseSpigotVersion(html);
        String name = parseSpigotName(html, config.pluginName());
        URI downloadUrl = parseSpigotDownloadUrl(config, html);
        return new RemotePluginManifest(
            name,
            version,
            downloadUrl,
            null,
            null,
            URI.create(config.resourceUrl()),
            null
        );
    }

    private SpigetVersionPayload fetchSpigetLatestVersion(String resourceId) throws IOException, InterruptedException {
        URI uri = URI.create(spigetApiBase + resourceId + "/versions/latest");
        HttpRequest request = applyHeaders(HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .GET(), userAgentOnlyHeaders(Map.of()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return objectMapper.readValue(response.body(), SpigetVersionPayload.class);
    }

    private RemotePluginManifest fetchModrinthProject(ManagedPluginConfig config) throws IOException, InterruptedException {
        HttpRequest request = applyHeaders(HttpRequest.newBuilder(URI.create(config.manifestUrl()))
            .timeout(timeout)
            .GET(), config.headers())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth version request failed with HTTP " + response.statusCode());
        }

        ModrinthVersionPayload[] payloads = objectMapper.readValue(response.body(), ModrinthVersionPayload[].class);
        if (payloads.length == 0) {
            throw new IOException("Modrinth returned no versions for this project.");
        }

        ModrinthVersionPayload selectedVersion = null;
        for (ModrinthVersionPayload payload : payloads) {
            if (payload == null || payload.files() == null || payload.files().length == 0) {
                continue;
            }
            if ("listed".equalsIgnoreCase(blankToNull(payload.status()))
                && ("release".equalsIgnoreCase(blankToNull(payload.versionType()))
                    || blankToNull(payload.versionType()) == null)) {
                selectedVersion = payload;
                break;
            }
            if (selectedVersion == null) {
                selectedVersion = payload;
            }
        }

        if (selectedVersion == null) {
            throw new IOException("Modrinth did not return a downloadable version.");
        }

        ModrinthFilePayload selectedFile = null;
        for (ModrinthFilePayload file : selectedVersion.files()) {
            if (file != null && Boolean.TRUE.equals(file.primary()) && !isBlank(file.url())) {
                selectedFile = file;
                break;
            }
            if (selectedFile == null && file != null && !isBlank(file.url())) {
                selectedFile = file;
            }
        }

        if (selectedFile == null) {
            throw new IOException("Modrinth version has no downloadable file.");
        }

        URI changelogUri = null;
        if (!config.resourceUrl().isBlank()) {
            changelogUri = URI.create(config.resourceUrl());
        }

        return new RemotePluginManifest(
            inferModrinthProjectName(config, selectedVersion.name()),
            selectedVersion.versionNumber(),
            URI.create(selectedFile.url()),
            blankToNull(selectedFile.filename()),
            null,
            changelogUri,
            selectedVersion.datePublished()
        );
    }

    private HttpRequest.Builder applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (!containsHeader(headers, "User-Agent")) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    private String parseSpigotVersion(String html) throws IOException {
        Matcher titleMatcher = SPIGOT_TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            return titleMatcher.group("version").trim();
        }

        Matcher versionMatcher = SPIGOT_VERSION_PATTERN.matcher(html);
        if (versionMatcher.find()) {
            return versionMatcher.group("version").trim();
        }

        throw new IOException("Could not parse the current Spigot resource version.");
    }

    private String parseSpigotName(String html, String fallbackName) {
        Matcher titleMatcher = SPIGOT_TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            return titleMatcher.group("name").trim();
        }
        return fallbackName;
    }

    private URI parseSpigotDownloadUrl(ManagedPluginConfig config, String html) {
        Matcher downloadMatcher = SPIGOT_DOWNLOAD_PATTERN.matcher(html);
        if (downloadMatcher.find()) {
            return URI.create(config.resourceUrl()).resolve(downloadMatcher.group("href"));
        }
        if (!config.downloadUrl().isBlank()) {
            return URI.create(config.downloadUrl());
        }
        return null;
    }

    private Optional<String> parseSpigotResourceId(ManagedPluginConfig config) {
        String[] candidates = {config.resourceUrl(), config.downloadUrl()};
        for (String candidate : candidates) {
            if (isBlank(candidate)) {
                continue;
            }
            Matcher matcher = SPIGOT_RESOURCE_ID_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return Optional.of(matcher.group("id"));
            }
        }
        return Optional.empty();
    }

    private String refererFor(ManagedPluginConfig config, URI downloadUri) {
        if (config.sourceType() != ManagedPluginSourceType.SPIGOT_RESOURCE || config.resourceUrl().isBlank()) {
            return null;
        }

        String host = downloadUri.getHost();
        if (host != null && host.toLowerCase(Locale.ROOT).contains("spigotmc.org")) {
            return config.resourceUrl();
        }
        return null;
    }

    private boolean containsHeader(Map<String, String> headers, String headerName) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(headerName));
    }

    private Map<String, String> userAgentOnlyHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("User-Agent") && !isBlank(entry.getValue())) {
                return Map.of("User-Agent", entry.getValue());
            }
        }
        return Map.of();
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isSameSpigotVersion(String left, String right) {
        return blankToNull(left) != null
            && blankToNull(right) != null
            && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private record ManifestPayload(
        String name,
        String version,
        String downloadUrl,
        String fileName,
        String sha256,
        String changelogUrl,
        Instant publishedAt
    ) {
    }

    private record ModrinthVersionPayload(
        String name,
        @JsonProperty("version_number") String versionNumber,
        String status,
        @JsonProperty("version_type") String versionType,
        @JsonProperty("date_published") Instant datePublished,
        ModrinthFilePayload[] files
    ) {
    }

    private record ModrinthFilePayload(
        String url,
        Boolean primary,
        String filename
    ) {
    }

    private record SpigetVersionPayload(
        String name,
        Integer id
    ) {
    }

    private record DownloadResult(
        boolean success,
        boolean skipped,
        int statusCode,
        URI uri,
        String versionMismatchVersion
    ) {
        private static DownloadResult success(URI uri) {
            return new DownloadResult(true, false, 200, uri, null);
        }

        private static DownloadResult failure(int statusCode, URI uri) {
            return new DownloadResult(false, false, statusCode, uri, null);
        }

        private static DownloadResult skippedResult() {
            return new DownloadResult(false, true, -1, null, null);
        }

        private static DownloadResult versionMismatch(String version, URI uri) {
            return new DownloadResult(false, false, -1, uri, version);
        }
    }

    private String inferModrinthProjectName(ManagedPluginConfig config, String versionName) {
        if (isBlank(versionName)) {
            return config.pluginName();
        }

        String trimmed = versionName.trim();
        if (trimmed.equals(config.pluginName())) {
            return trimmed;
        }

        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            return trimmed.substring(0, lastSpace).trim();
        }
        return trimmed;
    }
}
