<div align="center">

[English](README.md) · [Deutsch](README.de.md) · [**Español**](README.es.md)

<img src="docs/logo.png" alt="TRS Launcher" width="96" height="96" />

# TRS Launcher

**Un launcher rápido y moderno para Minecraft: Java Edition en Windows y Linux, con un cliente integrado que añade FPS, HUD y funciones de PvP.**

[![Latest release](https://img.shields.io/github/v/release/theredstonee/TRS-Launcher?include_prereleases&sort=semver&label=release&color=e0281e)](https://github.com/theredstonee/TRS-Launcher/releases)
[![Release build](https://img.shields.io/github/actions/workflow/status/theredstonee/TRS-Launcher/release.yml?label=build)](https://github.com/theredstonee/TRS-Launcher/actions/workflows/release.yml)
[![Downloads](https://img.shields.io/github/downloads/theredstonee/TRS-Launcher/total?color=ffb84d)](https://github.com/theredstonee/TRS-Launcher/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
<br />
[![Platform: Windows](https://img.shields.io/badge/platform-Windows%2010%2F11-0078D6?logo=windows&logoColor=white)](#windows)
[![Platform: Linux](https://img.shields.io/badge/platform-Linux-FCC624?logo=linux&logoColor=black)](#linux)
[![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white)](https://tauri.app)
[![Rust](https://img.shields.io/badge/Rust-2024-000000?logo=rust&logoColor=white)](https://www.rust-lang.org)
[![Nuxt 4](https://img.shields.io/badge/Nuxt-4-00DC82?logo=nuxt&logoColor=white)](https://nuxt.com)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.5.2%E2%80%9326.x-62B47A)](#funciones)

[Descarga](https://github.com/theredstonee/TRS-Launcher/releases) ·
[Wiki](https://github.com/theredstonee/TRS-Launcher/wiki/es-Home) ·
[Funciones](#funciones) ·
[Compilar](#compilar-desde-el-código-fuente) ·
[Arquitectura](#arquitectura)

<img src="docs/screenshot-home.png" alt="Página de inicio de TRS Launcher" width="860" />

</div>

> [!NOTE]
> TRS Launcher está en una fase temprana de desarrollo. Las versiones se publican como pre-releases y se actualizan solas.

## Funciones

**Jugar**
- Todas las versiones de Minecraft desde la 1.5.2 hasta la última 26.x, snapshots incluidas
- **Fabric, Quilt, Forge y NeoForge**: Forge y NeoForge se instalan con nuestro propio instalador y ejecutor de processors
- Java automático: para cada versión se descarga el runtime de Mojang adecuado
- **TRS Boost**: las instancias vanilla arrancan con Fabric, el TRS Client y mods de rendimiento de confianza (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix…). Las versiones sin Fabric usan Forge con el TRS Client. Puedes desactivarlo por instancia para jugar en vanilla puro
- Ajustes de JVM optimizados (G1/ZGC según la versión de Java) y la GPU dedicada en portátiles
- Las partidas siguen abiertas aunque cierres el launcher, y este las recupera la próxima vez que lo abras

**TRS Client (mod dentro del juego)**
- Incluido para **Fabric/Quilt 1.14.4–26.3, Forge 1.7.10–26.3 y NeoForge 1.20.2–26.3**, y se instala solo en cada instancia compatible
- Menú en el juego (Mayús derecha) con mosaicos, búsqueda, categorías y ajustes por módulo, incluido un selector de color con transparencia y chroma
- HUD con FPS, CPS, teclas pulsadas, ping, armadura, efectos, coordenadas y más; el editor del HUD ajusta los módulos a los bordes de la pantalla y entre sí, y puedes guardar varias disposiciones como perfiles y cambiar entre ellas con una tecla
- Indicadores de PvP (alcance, combo, velocidad), mira personalizada, color de golpe, animaciones de la 1.7, fuego bajo, contorno de bloque, hitboxes y sin sacudida al recibir daño
- **Minimapa** y **puntos de ruta** por mundo o servidor, con distancia, haz de luz y punto de muerte
- Mejoras del chat (marcas de tiempo, agrupación de mensajes repetidos, Ctrl+clic para copiar), zoom (V), fullbright y cámara libre
- Pantalla de título y menú con estilo redstone que adoptan el tema y el color de acento del launcher
- Juego limpio: no cambia el alcance ni las hitboxes, no hace clics automáticos; el minimapa solo muestra chunks cargados, sin vista de cuevas
- Se actualiza por su propio canal firmado, sin necesidad de una nueva versión del launcher

**Contenido**
- Busca e instala mods, modpacks, paquetes de recursos, paquetes de datos y shaders de **Modrinth**, con sus dependencias, y consulta la página de cada proyecto con descripción, galería, versiones y dependencias
- Elige cualquier versión, busca actualizaciones, lee los changelogs y activa o desactiva mods por instancia
- Importa instancias del **launcher oficial, Modrinth App, CurseForge, Prism Launcher, MultiMC** o de cualquier carpeta
- Exporta cualquier instancia como **`.mrpack`** (los mods que existen en Modrinth se enlazan y el resto va como overrides) y vuelve a importar esos paquetes

**Servicios TRS (opcionales)**
- **Capas TRS**: elige entre la colección de capas TRS (algunas animadas), desbloquea capas con códigos o sube la tuya; el equipo la revisa antes de que otros la vean
- **Amigos**: solicitudes de amistad, bloqueos, quién está conectado y a qué juega, y unirte a su servidor con un clic
- Todo está **desactivado hasta que lo aceptes**; consulta [PRIVACY.es.md](PRIVACY.es.md#servicios-trs)

**Y además**
- **Estética redstone**: un circuito de redstone vivo en la página de inicio y detrás de cada página, una lámpara como botón de jugar, tema oscuro, OLED, claro o del sistema y cinco colores de acento
- Varias cuentas de Microsoft, que cambias desde la barra de título (tokens cifrados con DPAPI de Windows; en Linux, con una clave del llavero del sistema)
- **Tareas en segundo plano**: las instalaciones y descargas siguen mientras usas el launcher, con un panel de tareas para pausarlas, reanudarlas o cancelarlas y un historial de las terminadas
- Lista de servidores con jugadores conectados y ping en directo, y unirse con un clic
- **Skins y capas** con vista previa en 3D: guarda tu propia colección de skins, cambia de modelo (clásico/delgado), elige cualquier capa de Mojang que tengas y aplica todos los cambios de una vez
- **Galería de capturas** de todas las instancias con visor a pantalla completa, copiar al portapapeles y papelera de reciclaje
- **Noticias** en la página de inicio: notas de parche de Minecraft, noticias de Mojang, proyectos populares de Modrinth y versiones del launcher
- Registro del juego en directo con filtros, diagnóstico de cierres, reparación de archivos y registros compartibles en mclo.gs (con los tokens ocultos)
- Mundos, tiempo de juego, banners de instancia, duplicar instancias y una paleta de comandos (Ctrl+K)
- **Actualizaciones silenciosas**: las nuevas versiones vienen firmadas, se descargan en segundo plano y se instalan cuando reinicias desde la barra de título; las partidas abiertas siguen funcionando

## Instalación

### Windows

1. Descarga `TRS-Launcher_<version>_x64-setup.exe` desde la [página de versiones](https://github.com/theredstonee/TRS-Launcher/releases).
2. Ejecútalo. Se instala solo para tu usuario de Windows y no necesita permisos de administrador.
3. Inicia sesión con la cuenta de Microsoft que tiene Minecraft.

> [!TIP]
> El instalador todavía no está firmado, así que Windows SmartScreen puede mostrar «Windows protegió su PC». Elige **Más información → Ejecutar de todas formas**.

Tus datos se guardan en `%APPDATA%\TRS-Launcher`. Encontrarás guías paso a paso en la [wiki](https://github.com/theredstonee/TRS-Launcher/wiki/es-Home).

### Linux

Cada versión incluye un **AppImage**, un **.deb** y un **.rpm** para x86_64 (compilados en Ubuntu 22.04, así que funciona cualquier distribución con glibc 2.35 o superior). No necesitas Java: el launcher descarga él mismo el runtime de Mojang adecuado.

| Distribución | Instalación |
|---|---|
| **Arch Linux**, Manjaro, EndeavourOS | `yay -S trs-launcher-bin` (o `paru -S trs-launcher-bin`) desde el AUR |
| **Debian, Ubuntu**, Linux Mint, Pop!_OS | `sudo apt install ./TRS-Launcher_<version>_amd64.deb` |
| **Fedora**, openSUSE | `sudo dnf install ./TRS-Launcher-<version>-1.x86_64.rpm` (openSUSE: `sudo zypper install …`) |
| **Cualquier distribución** | `chmod +x TRS-Launcher_<version>_amd64.AppImage && ./TRS-Launcher_<version>_amd64.AppImage` |
| **Flatpak** | `flatpak install flathub dev.theredstonee.trslauncher` (cuando esté publicado en Flathub) |

- **Actualizaciones:** el AppImage se actualiza solo, igual que la versión de Windows. Los paquetes .deb, .rpm, AUR y Flatpak los actualiza tu gestor de paquetes; el launcher solo te avisa cuando hay una versión nueva.
- Tus datos se guardan en `~/.local/share/TRS-Launcher` (Flatpak: `~/.var/app/dev.theredstonee.trslauncher/data/TRS-Launcher`).
- La clave de inicio de sesión se guarda en el llavero del sistema (GNOME Keyring, KWallet, KeePassXC…). Sin llavero se guarda en un archivo que solo tu usuario puede leer, y los ajustes lo indican.
- Minecraft 1.12.2 y anteriores (LWJGL 2) necesitan la herramienta `xrandr`: `xorg-xrandr` (Arch), `x11-xserver-utils` (Debian/Ubuntu), `xrandr` (Fedora).
- Funciona en Wayland y X11 (el juego se ejecuta mediante XWayland). Con el driver propietario de NVIDIA, el launcher desactiva el renderizador DMA-BUF de WebKit para evitar una ventana en blanco.
- El AppImage necesita FUSE 2 (`libfuse2`/`fuse2`); sin él, ejecútalo con `--appimage-extract-and-run`.
- Todavía no disponible en Linux: el asistente del firewall de Windows (no hace falta) y la grabación de clips. Para ARM64 no hay runtime de Java de Mojang: indica tu propio Java en los ajustes.

## Juego limpio

- El inicio de sesión funciona **solo con una cuenta de Microsoft que tenga el juego de forma legítima**, mediante el flujo oficial OAuth 2.0 de Microsoft (authorization code + PKCE, con device code como alternativa).
- **No hay modo offline ni «no premium»**, y nunca se saltan las comprobaciones de propiedad.
- Los archivos del juego **no se redistribuyen**: se descargan directamente a tu PC desde los servidores oficiales de Mojang.
- Tu contraseña nunca pasa por el launcher. Los tokens se quedan cifrados en tu PC y nunca se envían a servidores de terceros.

## Compilar desde el código fuente

**Requisitos:** Node.js 22+ con pnpm, Rust (stable, toolchain MSVC), Visual Studio Build Tools («Desarrollo para el escritorio con C++») y WebView2 (preinstalado en Windows 10/11).

**En Linux** necesitas Node.js 22+ con pnpm, Rust (stable) y los paquetes de desarrollo de WebKitGTK/GTK:

```sh
# Debian/Ubuntu
sudo apt install build-essential curl file pkg-config libssl-dev libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev
# Arch
sudo pacman -S --needed base-devel webkit2gtk-4.1 gtk3 libayatana-appindicator librsvg openssl xdotool
# Fedora
sudo dnf install gcc-c++ openssl-devel webkit2gtk4.1-devel gtk3-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel
```

`pnpm tauri build --bundles appimage,deb,rpm` compila los paquetes de Linux. Las recetas para AUR y Flatpak están en [`packaging/`](packaging/).

```sh
pnpm install
pnpm app:dev      # servidor de desarrollo de Nuxt + ventana de Tauri con recarga en caliente
```

| Comando | Para qué sirve |
|---|---|
| `pnpm app:build` | Compilación de release e instalador NSIS (`src-tauri/target/release/bundle`) |
| `pnpm typecheck` | Comprobar tipos de TypeScript/Vue |
| `pnpm test` | Pruebas del frontend (Vitest) |
| `cargo test --workspace` (en `src-tauri`) | Pruebas de Rust |
| `cargo clippy --workspace --all-targets` (en `src-tauri`) | Lints de Rust |
| `./gradlew collectLauncherJars` (en `client-mod/…`) | Compilar los jars del TRS Client para el launcher |

Con `TRS_LAUNCHER_HOME` puedes usar otra carpeta de datos, algo muy útil para hacer pruebas. El proceso de arranque también se puede probar sin interfaz:

```sh
cargo run -p trs-core --example launch -- <carpeta-de-datos> 1.21.1 [vanilla|fabric|quilt|forge|neoforge] [segundos]
```

Las compilaciones de desarrollo sin una cuenta iniciada abren el juego en su **modo demo** oficial. Las compilaciones de release siempre exigen una cuenta que tenga el juego.

### Versiones

Al subir una etiqueta como `v0.2.0` se ejecuta [`release.yml`](.github/workflows/release.yml), que compila y firma el instalador de Windows y el AppImage, .deb y .rpm de Linux, publica una versión (con un `PKGBUILD` listo para el AUR) y, cuando ambas plataformas terminan, actualiza el canal que consulta el actualizador integrado.

### Actualizaciones del TRS Client

El TRS Client tiene su propio canal de actualizaciones, así que se puede actualizar sin sacar una nueva versión del launcher. La versión de GitHub `client-mod` contiene `client-mod.json` (la versión del mod y cada build con su SHA-256 y tamaño), su firma minisign `client-mod.json.sig` y todos los jars. El launcher consulta el canal al arrancar y, como mucho, cada 30 minutos antes de iniciar una partida (con un tiempo de espera corto; sin conexión usa lo que ya tiene). Solo acepta manifiestos firmados con la clave del actualizador y más nuevos que la versión incluida, y descarga únicamente el jar que necesita la instancia que se va a iniciar en `<datos>/client-mod/<version>/`, comprobado contra el manifiesto. Ante cualquier error vuelve al jar incluido.

```sh
node scripts/publish-client-mod.mjs --bump patch   # sube mod_version; después vuelve a compilar los jars (collectLauncherJars)
node scripts/publish-client-mod.mjs --dry-run      # combina client-mod/dist, firma, verifica y actualiza src-tauri/resources/client-mod
node scripts/publish-client-mod.mjs                # lo mismo y además lo sube a la versión client-mod (necesita gh)
```

El script firma con `%USERPROFILE%\.tauri\trs-launcher.key` (contraseña en `trs-launcher.key.password`) y comprueba la firma contra `plugins.updater.pubkey` antes de subir nada. Para verificar un manifiesto generado con el propio verificador del launcher: `TRS_CLIENT_MOD_CHANNEL_DIR=client-mod/dist/channel TRS_CLIENT_MOD_DIST=client-mod/dist cargo test -p trs-core published_manifest -- --ignored`.

## Arquitectura

```
app/                    Frontend en Nuxt 4 (SPA, sin SSR)
src-tauri/
  src/                  App de Tauri: comandos, registro de tareas, mapeo de errores, plugins
  crates/core/          trs-core: el núcleo del launcher, independiente de la interfaz
    meta/ prepare.rs    Metadatos de versiones, librerías, natives, assets
    forge.rs loaders.rs Instalador de Forge/NeoForge, perfiles de Fabric/Quilt
    launch.rs process.rs  Argumentos, procesos del juego independientes, registros, diagnóstico de cierres
    auth/               Microsoft → Xbox Live → Minecraft, almacén de cuentas cifrado
    modrinth.rs modpack.rs content.rs  Modrinth, modpacks, contenido por instancia
    modpack_export.rs   Exportación .mrpack (búsqueda en Modrinth por hash, overrides)
    skins.rs skin_sync.rs news.rs screenshots.rs  Perfil y skins de Minecraft, caché de noticias, galería de capturas
    task.rs task_history.rs  Tareas en segundo plano que se pueden cancelar y pausar, y su historial
    trs_api/            Cliente de los servicios TRS opcionales (capas, amigos, estado en línea)
    client_mod.rs client_mod_update.rs  Builds incluidos del TRS Client y el canal de actualización firmado
    import.rs servers.rs boost.rs hooks.rs sync.rs
  resources/client-mod/ Builds incluidos del TRS Client + builds.json (manifiesto con versión y sumas de comprobación)
client-mod/             TRS Client: núcleo común + Fabric 1.14.4–26.3, Forge 1.7.10–26.3, NeoForge 1.20.2–26.3
```

Principios:

- **La lógica vive en `trs-core`** y la capa de Tauri se mantiene fina. El núcleo valida cada entrada por sí mismo.
- **El webview no tiene permisos de sistema de archivos, red ni shell.** Todo pasa por comandos dedicados, bajo una CSP estricta.
- **A la interfaz solo llegan un tipo de error estable y un mensaje legible.** Los detalles van al registro.

## Code signing policy

Las versiones para Windows se firman para que Windows pueda comprobar quién las ha publicado.

- Free code signing provided by [SignPath.io](https://about.signpath.io/), certificate by [SignPath Foundation](https://signpath.org/).
- Las compilaciones se hacen a partir de este repositorio con [GitHub Actions](.github/workflows/release.yml). Cada versión necesita una aprobación manual antes de firmarse.

**Roles del equipo**

| Rol | Miembros |
|---|---|
| Committers y revisores | [theredstonee](https://github.com/theredstonee) |
| Aprobadores | [theredstonee](https://github.com/theredstonee) |

**Privacidad:** este programa no transmitirá ninguna información a otros sistemas en red a menos que lo solicite expresamente el usuario o la persona que lo instala o lo utiliza. Los servicios TRS opcionales (capas, amigos, estado en línea) solo se conectan después de que lo aceptes en el launcher. En [PRIVACY.es.md](PRIVACY.es.md) ([English](PRIVACY.md) · [Deutsch](PRIVACY.de.md)) tienes los servicios con los que se conecta el launcher y cuándo.

## Agradecimientos

Parte de la gestión de procesos está adaptada de [Polyfrost OneLauncher](https://github.com/Polyfrost/OneLauncher) (GPL-3.0-only). Los metadatos del juego provienen de Mojang y los datos de mods, de la [API de Modrinth](https://docs.modrinth.com/).

## Licencia

TRS Launcher se distribuye bajo la [GNU General Public License v3.0](LICENSE).

<sub>TRS Launcher no es un producto oficial de Minecraft. No está aprobado por Mojang ni por Microsoft, ni está asociado con ellos.</sub>
