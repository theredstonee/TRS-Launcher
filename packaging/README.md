# Linux-Pakete

| Ordner | Inhalt |
|---|---|
| `aur/trs-launcher-bin/` | AUR-Paket aus dem fertigen `.deb` des GitHub-Releases (empfohlen) |
| `aur/trs-launcher/` | AUR-Paket, das aus den Quellen baut (Tag `v<version>`) |
| `flatpak/` | Flatpak-Manifest, AppStream-Metadaten und Desktop-Datei für Flathub |
| `linux/` | Desktop-Datei für Builds ohne Tauri-Bundle (Quellpaket) |

AppImage, `.deb` und `.rpm` baut der Release-Workflow (`.github/workflows/release.yml`, Job „Linux“ auf
Ubuntu 22.04). Nur das AppImage aktualisiert sich selbst; alle anderen Pakete aktualisiert die Paketverwaltung.

## AUR (`trs-launcher-bin`) hochladen

Der Release-Workflow hängt `trs-launcher-bin.PKGBUILD` und `trs-launcher-bin.SRCINFO` mit Version und
SHA-256 des `.deb` an jedes Release. Lokal geht dasselbe mit

```sh
node scripts/aur.mjs --version 0.4.3 --deb TRS-Launcher_0.4.3_amd64.deb --out /tmp/aur
```

Einmalig: AUR-Konto auf <https://aur.archlinux.org> anlegen, unter „My Account“ den öffentlichen
SSH-Schlüssel eintragen und in `~/.ssh/config`:

```
Host aur.archlinux.org
  IdentityFile ~/.ssh/aur
  User aur
```

Erstes Hochladen (legt das Paket an):

```sh
git clone ssh://aur@aur.archlinux.org/trs-launcher-bin.git
cd trs-launcher-bin
cp /pfad/zu/trs-launcher-bin.PKGBUILD PKGBUILD
cp /pfad/zu/trs-launcher-bin.SRCINFO .SRCINFO
makepkg -si            # auf Arch einmal selbst bauen und installieren (Test)
namcap PKGBUILD        # optional: Lint
git add PKGBUILD .SRCINFO
git commit -m "Update to 0.4.3"
git push
```

Jedes weitere Release: dieselben zwei Dateien aus dem neuen Release kopieren, committen, pushen.
`.SRCINFO` muss immer zur `PKGBUILD` passen (`makepkg --printsrcinfo > .SRCINFO` erzeugt sie auf Arch
ebenfalls; `node scripts/aur.mjs --srcinfo PKGBUILD` liefert dieselbe Ausgabe).

`trs-launcher` (aus Quellen) funktioniert genauso mit `packaging/aur/trs-launcher/PKGBUILD`; dort nur
`pkgver` anpassen – die Quellen kommen per Git-Tag, eine Prüfsumme gibt es nicht (`SKIP`).

## Flathub einreichen

1. Das Manifest `flatpak/dev.theredstonee.trslauncher.yml` auf die Release-Version stellen: URL und
   `sha256` des `.deb` (steht in `SHA256SUMS-linux.txt` am Release) und in der metainfo einen
   `<release>`-Eintrag ergänzen.
2. Lokal prüfen:
   ```sh
   flatpak install flathub org.gnome.Platform//50 org.gnome.Sdk//50 org.flatpak.Builder
   flatpak run org.flatpak.Builder --user --install --force-clean build flatpak/dev.theredstonee.trslauncher.yml
   flatpak run dev.theredstonee.trslauncher
   flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest flatpak/dev.theredstonee.trslauncher.yml
   flatpak run --command=flatpak-builder-lint org.flatpak.Builder appstream flatpak/dev.theredstonee.trslauncher.metainfo.xml
   ```
3. Vor der Einreichung ergänzen: **Screenshots** in der metainfo (`<screenshots>` mit öffentlichen
   PNG-URLs, z. B. aus `docs/`), sonst lehnt Flathub ab. Die App-ID `dev.theredstonee.trslauncher`
   verlangt, dass die Domain `theredstonee.dev` dir gehört (Nachweis per Datei
   `https://theredstonee.dev/.well-known/org.flathub.VerifiedApps.txt`); alternativ die ID auf
   `io.github.theredstonee.TRSLauncher` ändern (dann reicht das GitHub-Konto). In dem Fall nur die
   Flatpak-Dateien und die ID darin umbenennen – `identifier` in `src-tauri/tauri.conf.json` bleibt.
4. Einreichen: Fork von <https://github.com/flathub/flathub>, Branch **`new-pr`** als Basis, Manifest,
   metainfo und Desktop-Datei in die Wurzel legen, Pull Request gegen `new-pr` öffnen. Die Review
   kommentiert Berechtigungen – die Begründungen stehen als Kommentare im Manifest.
5. Nach der Aufnahme bekommst du ein eigenes Repo `flathub/dev.theredstonee.trslauncher`; neue
   Versionen dort per PR (URL + sha256 + `<release>`), den Rest baut Flathub.

Flathub bevorzugt Builds aus Quellen. Weil der Launcher Rust + Node braucht, wäre das mit
`flatpak-builder-tools` (`cargo-sources.json`, `node-sources.json`) möglich, aber aufwendig – das
Umverpacken des signierten Release-`.deb` ist für den Anfang üblich und akzeptiert.

## Bekannte Grenzen unter Linux

- Nur x86_64-Pakete. ARM64 würde bauen, aber Mojang liefert dort keine Java-Runtime (eigenes Java nötig)
  und LWJGL 2 (≤ 1.12.2) hat keine ARM-Natives.
- Clips (Spielaufnahmen) und die Firewall-Freigabe gibt es nur unter Windows.
- Im Flatpak landet der Papierkorb für Screenshots im Sandbox-Ordner der App.
