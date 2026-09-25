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
| CurseForge (`api.curseforge.com`; archivos e imágenes de `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Solo si eliges CurseForge como fuente, instalas un modpack de CurseForge, tienes instalado contenido de CurseForge o importas una instancia de CurseForge a la que le faltan archivos | Búsquedas y filtros, los ID de proyecto y de archivo del contenido instalado desde CurseForge (para los detalles y la búsqueda de actualizaciones) y solicitudes de descarga. Como en cualquier solicitud web, se incluye tu dirección IP. No necesitas una cuenta de CurseForge: el launcher se identifica con su propia clave de API, no con datos sobre ti. |
| Servidores de Minecraft de tu lista | Para mostrar su estado en directo | Un ping estándar de lista de servidores |
| mclo.gs | Solo cuando pulsas «Log teilen» (compartir registro) y lo confirmas | El registro del juego, sin tokens de acceso ni tu nombre de usuario de Windows |
| GitHub (`github.com`) | Al buscar actualizaciones del launcher | Una solicitud del manifiesto de actualización |
| App de Discord en tu ordenador (solo local, sin internet) | Mientras el launcher está abierto y «Mostrar estado en Discord» está activado (por defecto), ver [más abajo](#discord) | Tu estado de Discord: «In the TRS Launcher» (en inglés), o la versión de Minecraft, el cargador de mods y el tiempo de juego de la partida en curso |

Los tokens de las cuentas se guardan solo en tu ordenador, cifrados con DPAPI de Windows. Al desinstalar el launcher se
elimina el programa; puedes borrar tus datos de `%APPDATA%\TRS-Launcher` cuando quieras.

## Importar desde otros launchers

Al abrir «Importar desde otro launcher», el launcher busca otros launchers **en tu propio ordenador** (Minecraft
Launcher oficial, app de CurseForge, Modrinth App, Prism/MultiMC, Lunar Client, Badlion, Feather, OneClient, ATLauncher,
GDLauncher, TLauncher) y lee sus listas de instancias. Nada de esto sale de tu ordenador, y los archivos de los otros
launchers solo se leen, nunca se modifican: sus bases de datos se leen desde una copia temporal que se borra justo
después. Los datos de inicio de sesión de otros launchers (archivos de cuentas y tokens) nunca se leen ni se copian. Al
importar una instancia se copian sus mundos, mods, paquetes, ajustes y lista de servidores a la nueva instancia de TRS;
se conservan los ID de proyecto y de archivo que guardó el otro launcher para poder actualizar el contenido. Solo si
faltan archivos de una instancia de CurseForge, el launcher los descarga de CurseForge (ver la tabla de arriba).

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

## Vestuario en el juego (TRS Client)

El vestuario del TRS Client (skins, atuendos, capas, emotes y el editor de skins) solo se conecta para lo que haces
en él:

- **Aplicar una skin o elegir una capa de Minecraft** envía la imagen de la skin y su modelo (clásico/fino), o la capa
  elegida, junto con tu token de acceso de Minecraft directamente a Mojang (`api.minecraftservices.com`), igual que el
  launcher oficial. Para mostrar tus capas, el juego lee allí tu perfil de Minecraft y carga las imágenes desde
  `textures.minecraft.net`.
- **Añadir una skin por nombre de jugador** busca el nombre en Mojang (`api.mojang.com`, `sessionserver.mojang.com`) o,
  con los servicios TRS activados, a través de los servicios TRS, y descarga esa skin desde `textures.minecraft.net`.
- **Añadir una skin por enlace** descarga solo esa imagen desde la dirección que escribes: solo HTTPS, sin cookies y
  nunca desde direcciones de tu red local.
- **Añadir una skin desde un archivo** abre el diálogo de archivos de tu sistema; el archivo solo se lee en tu PC.
- **Con los servicios TRS activados**, tus skins del vestuario son las mismas «Mis skins» que en el launcher
  (sincronizadas con tu cuenta TRS, ver abajo), y tus favoritos, atuendos (nombre, skin, capa) y las casillas de la
  rueda de emotes se guardan en una pequeña entrada «wardrobe» de tu cuenta TRS para que sean iguales en cada PC. Elegir
  una capa TRS guarda esa elección en tu cuenta TRS; tras aplicar una skin, el juego avisa a los servicios TRS para que
  otros jugadores TRS vean antes la skin nueva.
- **Sin los servicios TRS**, todo se queda en tu PC en `config/trsclient/wardrobe/` de la carpeta del juego.

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

### Amigos en el juego (TRS Client)

Con los servicios TRS activados, el mod TRS Client muestra tu lista de amigos en el juego. Solo consulta al servidor
TRS tus amigos, solicitudes y jugadores bloqueados mientras la pantalla de amigos (o la lista de servidores / el menú de
pausa con información de amigos) está abierta: más o menos cada 30 segundos, si no cada 90 segundos o nunca. Las
solicitudes, eliminaciones y bloqueos solo se envían cuando haces clic. Las caras de los amigos se cargan del servicio
público de perfiles de Mojang (`sessionserver.mojang.com`, `textures.minecraft.net`) y solo se guardan en memoria. El
mod no guarda nada de esto en tu ordenador, salvo los servidores que fijas en la lista de servidores
(`config/trsclient/server-pins.json`, solo direcciones, nunca se envían).

### Insignia TRS en el juego (TRS Client)

El TRS Client muestra una pequeña insignia TRS junto al nombre de los jugadores que **están jugando con TRS en este
momento**: mientras están en un mundo o en un servidor con el TRS Client, o mientras se ejecuta un juego iniciado por
el TRS Launcher. Quien solo tiene el launcher abierto o juega con otro cliente no recibe insignia. Para ello, el TRS
Client informa «jugando» (versión, cargador de mods y, solo con «Server teilen», la dirección del servidor) más o menos
una vez por minuto mientras estás en un mundo o en un servidor, y el launcher hace lo mismo mientras se ejecuta un
juego que él ha iniciado; ambas cosas son el estado en línea descrito más abajo y terminan cuando sales del mundo o
se cierra el juego.

- **Quién la ve:** si alguien está jugando en este momento solo se muestra a jugadores que **están jugando ellos
  mismos**; en la práctica, otros jugadores TRS en el mismo servidor, junto a nombres que ya ven. Todos los demás
  reciben «sin insignia». Tu propia insignia la ves siempre. Los jugadores que has bloqueado nunca la ven.
- **Desactivarla:** «Mostrar insignia de TRS» (*Einstellungen → Datenschutz*) oculta tu insignia a todos. Poner tu
  estado en línea en «nadie» solo te oculta en las listas de amigos; la insignia **no**.

### Compartir capas con amigos

Puedes compartir con un amigo una capa que hayas subido, una vez que el equipo la haya aprobado. Tu amigo recibe una
oferta en el launcher y en el juego y decide si la acepta. Para ello, el servidor TRS guarda qué capa se ofreció a
quién, quién la ofreció y cuándo, y si se aceptó. Tras aceptarla, tu amigo puede llevar la capa como si fuera suya y
otros jugadores TRS la ven en él. Un amigo puede pasar la capa a sus propios amigos (como máximo 20 jugadores por capa).

- **Quién ve qué:** el creador ve a todos los que tienen la capa o una oferta abierta de ella (con su nombre de
  Minecraft), también a los jugadores a los que un amigo se la pasó. Quien tiene la capa ve quién se la dio, el nombre
  del creador y los jugadores a los que él mismo se la pasó.
- **Terminar:** el creador puede retirar la capa a cualquiera en cualquier momento; entonces también la pierden todos a
  los que ese jugador se la pasó. Cualquiera puede devolver una capa compartida. Si eliminas a un amigo o bloqueas a un
  jugador, se cancelan las ofertas abiertas entre vosotros; las capas ya aceptadas se quedan hasta que alguien las
  retire. Si se borra la capa, el equipo la rechaza o se borra una cuenta TRS, las capas compartidas afectadas
  desaparecen al instante.

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
| Capas compartidas: qué capas ofreciste a quién (y quién las pasó a otros), las ofertas abiertas para ti y las capas que tus amigos compartieron contigo, cada una con la fecha y si se aceptó | Compartir capas con amigos (ver arriba) |
| Amigos, solicitudes de amistad y bloqueos | La lista de amigos |
| Estado en línea: «en línea en el launcher» o «jugando» (del launcher o del TRS Client) con versión y cargador de mods y, solo si has activado «Server teilen» (compartir servidor), la dirección del servidor | Mostrar a tus amigos a qué juegas y permitirles unirse; mostrar la insignia TRS mientras juegas (ver arriba) |
| Solo con «Sincronizar con la cuenta de TRS» activado: tus skins propias de «Mis skins» (la imagen, recodificada sin metadatos, su nombre y modelo), tus presets de mods propios (nombres e ID de proyectos de Modrinth, sin archivos ni rutas de carpetas) y tu tema, color de acento e idioma, cada uno con la fecha del último cambio; las skins y presets eliminados se anotan durante un tiempo | Mantenerlos iguales en todos los PC donde uses esta cuenta de Minecraft |
| Solo con los servicios TRS activados y «Sincronizar con la cuenta de TRS» activado en el TRS Client (en el juego): los ajustes del TRS Client – qué módulos están activados y sus ajustes, los diseños y perfiles de HUD, las teclas TRS de los módulos, el modo de configuración de los mods de rendimiento, si terminaste la introducción (y el paquete de módulos elegido) y qué entradas «NUEVO» has abierto –, cada parte con la fecha de su último cambio; sin puntos de ruta, direcciones de servidor, archivos, rutas ni tokens | Mantener el TRS Client igual en todos los PC y carpetas de juego donde uses esta cuenta de Minecraft y mostrar la introducción solo una vez |
| Solo con los servicios TRS activados: la entrada del vestuario del TRS Client – tus skins favoritas, atuendos (nombre, skin, capa) y las casillas de la rueda de emotes, con la hora del último cambio | El mismo vestuario en cada PC |

**Sincronización:** «Sincronizar con la cuenta de TRS» (*Einstellungen → Datenschutz*, activado de fábrica mientras
los servicios TRS estén activados) mantiene iguales en todos tus PC tus skins propias, tus presets propios y el aspecto
del launcher (tema, color de acento, idioma). Java, la memoria y los demás ajustes **no** se sincronizan y nunca salen
de tu PC. Si desactivas el interruptor, se deja de sincronizar; lo ya sincronizado permanece en el servidor hasta que lo
borres con «Alle TRS-Daten löschen». Solo tú puedes leer tus datos sincronizados: no hay ninguna vista de
administración para ellos.

**Sincronización del TRS Client:** el mod TRS Client inicia sesión por sí mismo (ver arriba) y guarda sus propios
ajustes en el mismo lugar, como un documento de 64 KB como máximo por cuenta. Solo lo hace mientras los servicios TRS
estén activados en el launcher y su interruptor «Sincronizar con la cuenta de TRS» (menú TRS → *Funciones en línea de
TRS*, activado de fábrica) esté activado; el propio interruptor, los puntos de ruta, la lista de servidores de la vista
libre y las opciones de Minecraft (options.txt) se quedan en tu PC. El juego también lee tu tema, color de acento e
idioma sincronizados y los vuelve a escribir cuando los cambias en la introducción, para que el launcher los siga.
«Alle TRS-Daten löschen» borra también este documento.

El estado en línea se guarda **solo en la memoria del servidor**, nunca se escribe en disco, no tiene historial y
caduca **3 minutos** después de la última actualización. Solo lo ven tus amigos, y nadie si lo configuras en «nadie».
Solo el hecho de que estés jugando en este momento puede aparecer además como tu insignia TRS (ver arriba).

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
  launcher y sales del mundo.
- Las skins, presets y ajustes sincronizados se conservan hasta que los borres en el launcher (una skin borrada en un PC
  también se borra en el servidor). Las notas sobre skins borradas se guardan 30 días para que tus otros PC también
  puedan borrarlas.
- **«Alle TRS-Daten löschen»** (borrar todos los datos TRS, en *Einstellungen → Datenschutz*) lo elimina todo al
  instante (art. 17 del RGPD): tu cuenta, sesiones, amistades, solicitudes y bloqueos, las capas subidas y sus archivos,
  las capas compartidas (tus capas con amigos y las que tus amigos compartieron contigo),
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
