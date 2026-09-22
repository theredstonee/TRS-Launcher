# Privacy

TRS Launcher runs on your computer. It has **no telemetry, analytics, crash reporting or advertising**, and it does not
send any information to servers run by the TRS Launcher project.

The launcher only connects to other services when that is needed for something you asked it to do:

| Service | When | What is sent |
|---|---|---|
| Microsoft / Xbox Live / Minecraft services | Signing in, starting the game | Standard OAuth sign-in; your Minecraft access token when the game starts |
| Mojang (`piston-meta`, `libraries`, `resources`) | Installing or starting a version | Download requests for game files |
| Fabric, Quilt, Forge, NeoForge maven/meta servers | Installing a mod loader | Download requests |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Browsing, installing or updating content | Search queries, file hashes of installed mods (for update checks) |
| Minecraft servers in your server list | Showing live status | A standard server-list ping |
| mclo.gs | Only when you click "Log teilen" and confirm | The game log, with access tokens and your Windows user name removed |
| GitHub (`github.com`) | Checking for launcher updates | A request for the update manifest |

Account tokens are stored only on your computer, encrypted with Windows DPAPI. Uninstalling the launcher removes the
program; your data in `%APPDATA%\TRS-Launcher` can be deleted at any time.
