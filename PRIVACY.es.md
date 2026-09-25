[English](PRIVACY.md) · [Deutsch](PRIVACY.de.md) · [**Español**](PRIVACY.es.md)

# Privacidad

TRS Launcher se ejecuta en tu ordenador. **No tiene telemetría, analíticas, informes de errores ni publicidad**. Solo
envía información a un servidor del proyecto TRS Launcher si activas los [servicios TRS](#servicios-trs) opcionales
(capas, amigos, estado en línea). Sin tu consentimiento, el launcher no envía nada allí.

El launcher solo se conecta a otros servicios cuando hace falta para algo que tú le has pedido:

| Servicio | Cuándo | Qué se envía |
|---|---|---|
| Microsoft / Xbox Live / servicios de Minecraft | Al iniciar sesión y al iniciar el juego | El inicio de sesión OAuth estándar; tu token de acceso de Minecraft al iniciar el juego |
| Mojang (`piston-meta`, `libraries`, `resources`) | Al instalar o iniciar una versión | Solicitudes de descarga de los archivos del juego |
| Servidor de sesiones de Mojang (`sessionserver.mojang.com`) | Al iniciar sesión en los servicios TRS (solo si lo has aceptado) | La misma solicitud «join» que usa el inicio de sesión en un servidor de Minecraft: tu token de acceso, tu UUID y un desafío de un solo uso |
| Servicios de perfil de Mojang (`api.mojang.com`, `sessionserver.mojang.com`, `textures.minecraft.net`) | Al importar un skin por nombre de jugador y al mostrar caras de jugadores (amigos, búsqueda de administración) | El nombre de jugador o UUID buscado; la descarga de esa imagen de skin |
| El sitio web de un enlace que introduces | Solo al importar un skin «por enlace» | Una solicitud de descarga normal de esa imagen (solo HTTPS, sin cookies ni cuentas) |
| Servicios TRS (`trs-launcher.theredstonee.de`, antes también `api.theredstonee.de`) | Solo si lo has aceptado, ver [más abajo](#servicios-trs) | Tu UUID, tu nombre, la capa que eliges, tus amigos y tu estado en línea |
| Servidores maven/meta de Fabric, Quilt, Forge y NeoForge | Al instalar un cargador de mods | Solicitudes de descarga |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Al explorar, instalar o actualizar contenido | Búsquedas y los hashes de los mods instalados (para buscar actualizaciones) |
| CurseForge (`api.curseforge.com`; archivos e imágenes de `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Solo si eliges CurseForge como fuente, instalas un modpack de CurseForge o tienes instalado contenido de CurseForge | Búsquedas y filtros, los ID de proyecto y de archivo del contenido instalado desde CurseForge (para los detalles y la búsqueda de actualizaciones) y solicitudes de descarga. Como en cualquier solicitud web, se incluye tu dirección IP. No necesitas una cuenta de CurseForge: el launcher se identifica con su propia clave de API, no con datos sobre ti. |
| Servidores de Minecraft de tu lista | Para mostrar su estado en directo | Un ping estándar de lista de servidores |
| mclo.gs | Solo cuando pulsas «Log teilen» (compartir registro) y lo confirmas | El registro del juego, sin tokens de acceso ni tu nombre de usuario de Windows |
| GitHub (`github.com`) | Al buscar actualizaciones del launcher | Una solicitud del manifiesto de actualización |
| App de Discord en tu ordenador (solo local, sin internet) | Mientras el launcher está abierto y «Mostrar estado en Discord» está activado (por defecto), ver [más abajo](#discord) | Tu estado de Discord: «In the TRS Launcher» (en inglés), o la versión de Minecraft, el cargador de mods y el tiempo de juego de la partida en curso |

Los tokens de las cuentas se guardan solo en tu ordenador, cifrados con DPAPI de Windows. Al desinstalar el launcher se
elimina el programa; puedes borrar tus datos de `%APPDATA%\TRS-Launcher` cuando quieras.

## Cambiar de cuenta en el juego (TRS Client)

- **Juego iniciado con el TRS Launcher:** el TRS Client puede mostrar las cuentas del launcher y cambiar entre ellas
  sin reiniciar. Al elegir una cuenta, el launcher entrega al juego un token de acceso de Minecraft nuevo solo por una
  conexión local de tu ordenador (`127.0.0.1`), cifrada y solo al proceso del juego que él mismo inició. La clave de esa
  conexión se entrega al juego en memoria al iniciarlo y nunca se guarda en el disco. Para esto no sale nada de tu PC.
  «Añadir cuenta» en el juego abre el inicio de sesión normal de Microsoft del launcher en tu navegador.
- **Juego iniciado sin el TRS Launcher:** las cuentas que añadas en el juego inician sesión directamente con Microsoft,
  Xbox Live y los servicios de Minecraft (con la propia app de inicio de sesión del TRS Launcher). Solo se guarda el
  token de actualización, cifrado (DPAPI de Windows; en otros sistemas AES con un archivo de clave en tu carpeta de
  usuario), en `config/trsclient/accounts.json` de esa carpeta del juego. Los tokens de acceso se quedan en memoria.
  Si eliminas una cuenta en el juego, se borra.
- Para las caras pequeñas de la lista, el juego carga el skin desde `textures.minecraft.net` y, si hace falta, el
  perfil público desde `sessionserver.mojang.com`.

## Discord

Si la app de Discord está abierta en tu ordenador, el launcher muestra un estado en tu perfil de Discord («Jugando a TRS
Launcher»): «In the TRS Launcher» mientras solo está abierto el launcher y, mientras juegas, **la versión de Minecraft,
el cargador de mods (p. ej. Fabric) y cuánto tiempo llevas jugando**. Nunca muestra direcciones de servidor ni nombres
de instancias o jugadores.

- El launcher solo habla con la app de Discord **de tu propio ordenador** (la interfaz local de Discord, una tubería con
  nombre o un socket local). Él mismo no envía nada por internet y no necesita tu inicio de sesión de Discord.
- La app de Discord muestra después este estado en tu perfil, **visible públicamente para quien pueda ver tu perfil de
  Discord** (amigos, miembros de servidores en común). Lo que Discord hace con él se rige por la
  [política de privacidad de Discord](https://discord.com/privacy).
- El estado desaparece al cerrar el launcher. Si Discord no está abierto, no pasa nada.
- Está **activado por defecto** y puedes desactivarlo cuando quieras en *Ajustes → Privacidad → Mostrar estado en Discord*
  o, en Discord, en *Ajustes de usuario → Privacidad de la actividad*.

## Servicios TRS

Los servicios TRS añaden capas TRS, una lista de amigos y un estado en línea al launcher y al mod TRS Client. Están
**desactivados hasta que los aceptes** en el launcher (antes del primer inicio de sesión, un breve aviso explica qué se
guarda). Puedes volver a desactivarlos cuando quieras en *Einstellungen → Datenschutz* (Ajustes → Privacidad).

### Cómo funciona el inicio de sesión

El launcher inicia sesión con tu cuenta de Minecraft igual que un servidor de Minecraft comprueba a un jugador: pide al
servidor TRS un desafío de un solo uso, lo confirma con el servidor de sesiones de Mojang usando tu token de acceso de
Minecraft, y el servidor TRS pregunta a Mojang si eso ha ocurrido. **El servidor TRS nunca ve tu contraseña ni tu token
de acceso de Minecraft.** Después emite su propio token, que el launcher guarda en tu ordenador cifrado con DPAPI de
Windows y que nunca entrega a contenido web ni al juego. El mod TRS Client inicia sesión por su cuenta a través de la
sesión del juego.

### Qué se guarda

| Datos | Para qué |
|---|---|
| UUID de Minecraft y nombre de jugador | Para identificar tu cuenta TRS y mostrar tu nombre a tus amigos |
| Fecha de creación de la cuenta y del último inicio de sesión | Gestión de cuentas y prevención de abusos |
| Tokens de sesión (solo como hashes SHA-256, válidos 30 días, como máximo 10 por cuenta) | Mantener tu sesión iniciada |
| Tus ajustes de privacidad (insignia TRS, capa visible para otros, estado en línea visible para amigos/nadie, compartir servidor) | Para que los servicios respeten tus decisiones |
| La capa que has elegido y las capas desbloqueadas con códigos o concedidas por el equipo | Mostrar tu capa a otros jugadores TRS |
| Las capas que subes (la imagen, recodificada sin metadatos), su estado de revisión y un nombre opcional | Subida de capas; el equipo revisa cada subida antes de que otros la vean |
| Las denuncias que haces sobre capas de otros jugadores (motivo, nota opcional) | Moderación |
| Amigos, solicitudes de amistad y bloqueos | La lista de amigos |
| Estado en línea: «en línea en el launcher» o «jugando» con versión y cargador de mods y, solo si has activado «Server teilen» (compartir servidor), la dirección del servidor | Mostrar a tus amigos a qué juegas y permitirles unirse |
| Solo con «Sincronizar con la cuenta de TRS» activado: tus skins propias de «Mis skins» (la imagen, recodificada sin metadatos, su nombre y modelo), tus presets de mods propios (nombres e ID de proyectos de Modrinth, sin archivos ni rutas de carpetas) y tu tema, color de acento e idioma, cada uno con la fecha del último cambio; las skins y presets eliminados se anotan durante un tiempo | Mantenerlos iguales en todos los PC donde uses esta cuenta de Minecraft |

**Sincronización:** «Sincronizar con la cuenta de TRS» (*Einstellungen → Datenschutz*, activado de fábrica mientras
los servicios TRS estén activados) mantiene iguales en todos tus PC tus skins propias, tus presets propios y el aspecto
del launcher (tema, color de acento, idioma). Java, la memoria y los demás ajustes **no** se sincronizan y nunca salen
de tu PC. Si desactivas el interruptor, se deja de sincronizar; lo ya sincronizado permanece en el servidor hasta que lo
borres con «Alle TRS-Daten löschen». Solo tú puedes leer tus datos sincronizados: no hay ninguna vista de
administración para ellos.

El estado en línea se guarda **solo en la memoria del servidor**, nunca se escribe en disco, no tiene historial y
caduca **3 minutos** después de la última actualización. Solo lo ven tus amigos, y nadie si lo configuras en «nadie».

Las acciones de administración (como aprobar una capa o un bloqueo) se registran en un registro de auditoría junto con
la UUID afectada.

### Finalidad y base jurídica

Los datos se tratan únicamente para prestar los servicios TRS que has pedido: capas, lista de amigos, estado en línea y
la sincronización de tus skins, presets y del aspecto del launcher entre tus PC.
La base jurídica es la prestación del servicio que has solicitado (art. 6.1.b del RGPD). Mantener los servicios libres
de abusos (revisión de subidas, denuncias, bloqueos y límites de uso) se basa en nuestro interés legítimo en un servicio
seguro (art. 6.1.f del RGPD). No hay publicidad, ni elaboración de perfiles, ni venta de datos.

### Conservación y eliminación

- Tus datos se conservan mientras exista tu cuenta TRS.
- Los tokens de sesión caducan a los 30 días; cerrar sesión o quitar una cuenta del launcher revoca el token.
- El estado en línea desaparece 3 minutos después de la última actualización, o en el momento en que cierras el
  launcher.
- Las skins, presets y ajustes sincronizados se conservan hasta que los borres en el launcher (una skin borrada en un PC
  también se borra en el servidor). Las notas sobre skins borradas se guardan 30 días para que tus otros PC también
  puedan borrarlas.
- **«Alle TRS-Daten löschen»** (borrar todos los datos TRS, en *Einstellungen → Datenschutz*) lo elimina todo al
  instante (art. 17 del RGPD): tu cuenta, sesiones, amistades, solicitudes y bloqueos, las capas subidas y sus archivos,
  los códigos canjeados, las denuncias, tu estado en línea y todas las skins, presets y ajustes sincronizados. Después,
  los servicios TRS quedan desactivados en el launcher. Las skins y presets de tu PC se conservan.
- Tras la eliminación solo se conserva un registro de bloqueo existente (tu UUID, el motivo y la fecha), para que no se
  pueda eludir un bloqueo volviendo a iniciar sesión.
- Los registros del servidor contienen solo datos técnicos (método, ruta sin parámetros de consulta, estado, duración,
  id de la solicitud): **ni direcciones IP ni tokens**. Los límites de uso cuentan las solicitudes por dirección IP y
  por cuenta **solo en memoria**; esos contadores nunca se escriben en disco.

### Alojamiento y encargados del tratamiento

- El servidor TRS funciona en un servidor en **Alemania** (alojamiento gestionado con Pterodactyl). Los datos se guardan
  allí.
- **Cloudflare** (Cloudflare, Inc. / Cloudflare Germany GmbH) actúa como encargado del tratamiento: el servidor solo es
  accesible a través de un Cloudflare Tunnel y Cloudflare termina la conexión HTTPS. Por eso Cloudflare trata tu
  dirección IP y las solicitudes transmitidas en nuestro nombre, conforme a su anexo de tratamiento de datos (Data
  Processing Addendum). Las transferencias a EE. UU. están cubiertas por el Marco de Privacidad de Datos UE-EE. UU. y
  por cláusulas contractuales tipo.
- **Mojang/Microsoft** confirma el inicio de sesión (ver arriba): tu ordenador envía la solicitud «join» directamente a
  Mojang, y el servidor TRS consulta a Mojang con tu nombre de jugador y el desafío de un solo uso (`hasJoined`).

### Tus derechos

Tienes derecho de acceso, rectificación, supresión, limitación del tratamiento, portabilidad de los datos y oposición
(arts. 15 a 21 del RGPD), así como a presentar una reclamación ante una autoridad de control. Casi todo esto puedes
hacerlo tú mismo en el launcher (desactivar los servicios, cambiar los ajustes de privacidad, borrar todos los datos).
Para cualquier otra cosa, ponte en contacto con nosotros.

### Contacto

Theredstonee: abre una incidencia en <https://github.com/theredstonee/TRS-Launcher/issues> o usa los datos de contacto
de <https://theredstonee.de>. Por favor, no publiques datos personales en incidencias públicas; pide mejor un contacto
privado.
