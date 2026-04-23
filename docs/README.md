# AutoPluginUpdater Docs

AutoPluginUpdater is an admin-only Paper plugin that checks installed plugins for updates, lets staff review
them in-game, backs up the current jar, stages the approved jar into Paper's update folder, and prompts for a
restart when the update is ready to apply.

## What It Does

- Scans configured plugins for newer versions.
- Shows installed plugins in an in-game admin GUI.
- Lets admins approve or deny a specific update version.
- Backs up the current live jar into `plugins/backup`.
- Downloads the approved jar and stages it into `plugins/update`.
- Saves update metadata and history in `plugins/AutoPluginUpdater/updates.yml`.
- Prompts admins to restart now or later.

## Important Notes

- This plugin updates plugins that are already installed. It does not install brand-new plugins.
- The `managed-plugins` key should ideally use the plugin's real Bukkit/Paper name.
- AutoPluginUpdater also tries to match by jar base name, which helps with cases like
  `VaultUnlocked-2.18.0.jar` loading as plugin name `Vault`.
- A full server restart is still required for the staged update to apply.

## Choosing a Source

AutoPluginUpdater supports whichever source you want to use for a managed plugin.

Current built-in choices:

- `modrinth-project`
- `spigot-resource`
- `http-manifest`

Both `modrinth-project` and `spigot-resource` are fully supported and can work well. Use the source that makes the
most sense for the plugin you are managing and the site you prefer to pull updates from.

## Installation

1. Build the plugin and place the AutoPluginUpdater jar in your server's `plugins` folder.
2. Start the server once so the plugin generates its config folder.
3. Edit `plugins/AutoPluginUpdater/config.yml`.
4. Restart the server.
5. Use `/apu` in-game or `/apu refresh` in console to verify your managed plugin list is working.

## Basic Config

Example:

```yaml
scan-interval-minutes: 60
restart-command: "restart"
http-timeout-seconds: 10

managed-plugins:
  VaultUnlocked:
    source-type: "modrinth-project"
    manifest-url: "https://api.modrinth.com/v2/project/vaultunlocked/version"
    resource-url: "https://modrinth.com/plugin/vaultunlocked"
    headers:
      User-Agent: "yourname/AutoPluginUpdater/0.1"
    display-source: "Modrinth"
    checksum-required: false
```

Global settings:

- `scan-interval-minutes`: how often AutoPluginUpdater checks for updates automatically.
- `restart-command`: the console command used when an admin clicks or runs restart now.
- `http-timeout-seconds`: timeout for manifest and download HTTP requests.

Per-plugin settings:

- `source-type`: one of `modrinth-project`, `http-manifest`, or `spigot-resource`.
- `manifest-url`: used by `modrinth-project` and `http-manifest`.
- `resource-url`: used as the public project page or reference page.
- `download-url`: optional manual download fallback, mainly useful with `spigot-resource`.
- `headers`: optional HTTP headers such as `User-Agent`, `Authorization`, or `Cookie`.
- `display-source`: friendly label shown in the GUI.
- `checksum-required`: if `true`, approval fails unless the manifest includes a SHA-256 checksum.

## Source Types

### `modrinth-project`

Use this when you want to read versions directly from Modrinth.

- `manifest-url` should point to the Modrinth versions API for the project.
- `resource-url` should point to the public Modrinth project page.

Example:

```yaml
managed-plugins:
  SomePlugin:
    source-type: "modrinth-project"
    manifest-url: "https://api.modrinth.com/v2/project/someplugin/version"
    resource-url: "https://modrinth.com/plugin/someplugin"
    display-source: "Modrinth"
    checksum-required: false
```

### `http-manifest`

Use this when you control your own update feed.

Example:

```yaml
managed-plugins:
  ExamplePlugin:
    source-type: "http-manifest"
    manifest-url: "https://example.com/autoupdater/exampleplugin.json"
    display-source: "Example CDN"
    checksum-required: true
```

Expected manifest shape:

```json
{
  "name": "ExamplePlugin",
  "version": "2.0.0",
  "downloadUrl": "https://example.com/downloads/ExamplePlugin-2.0.0.jar",
  "fileName": "ExamplePlugin-2.0.0.jar",
  "sha256": "optional",
  "changelogUrl": "optional",
  "publishedAt": "2026-04-17T21:13:00Z"
}
```

`fileName` is optional, but recommended. If present, AutoPluginUpdater uses it for the staged jar filename.

### `spigot-resource`

Use this when you want to manage updates from the plugin's Spigot resource page.

Example:

```yaml
managed-plugins:
  SomePlugin:
    source-type: "spigot-resource"
    resource-url: "https://www.spigotmc.org/resources/someplugin.12345/"
    download-url: "https://www.spigotmc.org/resources/someplugin.12345/download?version=67890"
    headers:
      User-Agent: "yourname/AutoPluginUpdater/0.1"
      Referer: "https://www.spigotmc.org/resources/someplugin.12345/"
    display-source: "SpigotMC"
    checksum-required: false
```

Note:

- Spigot resource pages can work for update detection.
- AutoPluginUpdater will first try the direct Spigot jar URL.
- If Spigot blocks that request, AutoPluginUpdater will try Spiget's cached download endpoint for the same resource.
- Spiget data is not instant; its public site says it updates from Spigot every 2 hours.
- If you want a different source instead, switch the plugin to Modrinth or another source you control.

## Commands

- `/apu`: opens the GUI for players, or prints a summary in console.
- `/apu refresh`: refreshes every installed plugin.
- `/apu refresh <plugin>`: refreshes one plugin.
- `/apu approve <plugin>`: downloads and stages the latest approved update.
- `/apu deny <plugin>`: denies the currently available version until a newer one appears.
- `/apu restart now`: dispatches the configured restart command.
- `/apu restart later`: keeps the staged update waiting until the next restart.

Permission:

- `autopluginupdater.admin`

## In-Game GUI Flow

1. Run `/apu`.
2. Click a plugin in the list.
3. Review its installed version, latest version, source, and status.
4. Click `Approve` to back up the current jar and stage the update.
5. Click `Deny this version` to ignore the current release until something newer appears.
6. Choose `Restart now` or `Restart later`.

## Status Meanings

- `Not configured`: the plugin is visible, but not in `managed-plugins`.
- `Up to date`: the installed version matches the latest known version.
- `Update available`: a newer version was detected.
- `Denied version`: the current available version was denied by an admin.
- `Staged, restart needed`: the update jar is already in the update folder and waiting for reboot.
- `Check failed`: the source could not be fetched or parsed.

## Files Written By AutoPluginUpdater

- `plugins/update/`
  Paper reads staged plugin jars from here on boot.
- `plugins/backup/<PluginName>/`
  AutoPluginUpdater stores timestamped backups of the old live jar here.
- `plugins/AutoPluginUpdater/updates.yml`
  Stores current update state and update history.

Tracked metadata includes:

- plugin name
- old version
- new version
- download source
- approval timestamp
- backup path
- staged path
- last error

## Troubleshooting

### Plugin shows `Not configured`

- Make sure the plugin exists under `managed-plugins` in `config.yml`.
- Restart the server after editing the config.
- Prefer the plugin's real Bukkit/Paper name as the config key.
- If the plugin's jar name differs from its loaded plugin name, AutoPluginUpdater will still try to match by jar
  base name.

### Approval fails with `HTTP 403`

- This is most common with `spigot-resource`.
- AutoPluginUpdater now retries through Spiget automatically for supported Spigot resources.
- If the fallback still fails, it may mean Spiget has not cached the newest file yet.
- Try again later or switch to another supported source if you prefer.
- If you must use a protected source, add the required headers in config.

### The update is staged but not live yet

- That is expected.
- Paper applies jars from the update folder on the next full startup.

## Recommended Workflow

1. Pick the source you actually want to manage updates from for each plugin.
2. Use `modrinth-project` for plugins you want to track through Modrinth.
3. Use `spigot-resource` for plugins you want to track through Spigot.
4. Use `http-manifest` for private plugins or plugins you distribute yourself.
5. Always test updates on a non-production server first when possible.
