# Linux

> Copy of the wiki page "Linux" – paste into the GitHub wiki (`Linux.md`).

TRS Launcher runs on any x86_64 Linux with glibc 2.35 or newer (Ubuntu 22.04+, Debian 12+, Fedora 36+, Arch,
openSUSE Tumbleweed, …), on Wayland and X11. Java is downloaded automatically.

## Install

| Distribution | Command |
|---|---|
| Arch Linux / Manjaro / EndeavourOS | `yay -S trs-launcher-bin` or `paru -S trs-launcher-bin` |
| Debian / Ubuntu / Mint / Pop!_OS | download the `.deb`, then `sudo apt install ./TRS-Launcher_<version>_amd64.deb` |
| Fedora | download the `.rpm`, then `sudo dnf install ./TRS-Launcher-<version>-1.x86_64.rpm` |
| openSUSE | `sudo zypper install ./TRS-Launcher-<version>-1.x86_64.rpm` |
| Any distribution | download the `.AppImage`, `chmod +x` it and run it |
| Flatpak | `flatpak install flathub dev.theredstonee.trslauncher` (when available) |

Only the AppImage updates itself. For all other packages, update with your package manager – the launcher shows
"Update available" and links to the release.

## Where things are

| What | Path |
|---|---|
| Launcher data (instances, Java, libraries) | `~/.local/share/TRS-Launcher` |
| Flatpak | `~/.var/app/dev.theredstonee.trslauncher/data/TRS-Launcher` |
| Launcher log | `~/.local/share/dev.theredstonee.trslauncher/logs` |
| Sign-in key | system keyring (Secret Service), otherwise `~/.local/share/TRS-Launcher/.token-key` (mode 0600) |

## Troubleshooting

- **Blank or white window:** start with `WEBKIT_DISABLE_DMABUF_RENDERER=1 trs-launcher` (set automatically with the
  NVIDIA driver). On very old GPUs also try `WEBKIT_DISABLE_COMPOSITING_MODE=1`.
- **AppImage doesn't start ("FUSE"):** install `libfuse2` (Debian/Ubuntu) or `fuse2` (Arch), or run it with
  `--appimage-extract-and-run`.
- **Minecraft 1.12.2 or older crashes at start:** install `xrandr` (`xorg-xrandr` on Arch, `x11-xserver-utils` on
  Debian/Ubuntu, `xrandr` on Fedora).
- **"Only protected by file permissions" in Settings → Storage:** no keyring was found. Install and unlock GNOME
  Keyring or KWallet (or KeePassXC with Secret Service enabled) and restart the launcher; the key moves over by itself.
- **Wrong graphics card on a laptop:** Settings → Defaults → "High-performance graphics card" sets `DRI_PRIME=1`
  (AMD/Intel) or the NVIDIA PRIME variables. Your own values under "Environment variables" win.
- **Launch wrapper:** Settings → Launch hooks → Wrapper, e.g. `gamemoderun` or `prime-run`.
- **Importing from Prism Launcher, MultiMC, Modrinth App or `~/.minecraft`:** found automatically, also Flatpak
  installs. In the Flatpak version only these folders are readable; for anything else use "Choose folder".

## Not available on Linux

- Clip recording and the Windows firewall helper.
- ARM64 builds (Mojang has no Java runtime for ARM Linux and Minecraft ≤ 1.12.2 has no ARM natives).
