// Themen-Seiten auf Spanisch. Aufbau und Regeln: shared/landing.ts (gleiche Abschnitte wie Englisch).

import type { LandingTexts } from './landing'

export const landingEs: LandingTexts = {
  common: {
    topics: 'Temas',
    faqTitle: 'Preguntas y respuestas',
    related: 'Más sobre TRS Launcher',
    download: 'Descargar gratis',
    features: 'Todas las funciones',
    allQuestions: 'Todas las preguntas',
    note: 'Gratis · Código abierto (GPL-3.0) · Windows y Linux',
    learnMore: 'Más información',
    onThisPage: 'En esta página',
  },
  pages: {
    'minecraft-launcher': {
      seo: {
        title: 'Launcher de Minecraft gratis para Windows y Linux – TRS',
        description:
          'TRS Launcher es un launcher gratis y de código abierto para Minecraft: Java Edition en Windows y Linux: cada versión, login de Microsoft, mods, clips y amigos.',
      },
      name: 'Launcher de Minecraft',
      teaser: 'El launcher gratis y de código abierto para Minecraft: Java Edition: cada versión, login de Microsoft, clips y amigos.',
      kicker: 'Launcher para Minecraft: Java Edition',
      title: 'El launcher de Minecraft gratis para Windows y Linux',
      lead: 'TRS Launcher – el Redstone Launcher de TheRedstonee – instala y abre cada versión de Minecraft: Java Edition. Es gratis, de código abierto bajo GPL-3.0 y trae todo lo que necesitas para jugar: instancias, cargadores de mods, tus cuentas de Microsoft, clips, amigos y su propio client mod, el TRS Client.',
      sections: [
        {
          id: 'instances',
          title: 'Cada versión de Minecraft en su propia instancia',
          text: [
            'Una instancia es una instalación de Minecraft separada con su propia versión, mods, mundos y ajustes. Crea todas las que quieras – una para la versión más nueva, otra para PvP en 1.8.9, otra para un modpack grande – y nunca se estorban entre sí. La biblioteca las muestra como tarjetas con orden, filtros y grupos propios.',
            'Tienes cada versión desde 1.7.10 hasta la más nueva, snapshots incluidas, con Vanilla, Fabric, Quilt, Forge o NeoForge. El launcher descarga por sí mismo el Java adecuado para cada versión, así que nunca tienes que instalar ni elegir Java a mano.',
          ],
          points: [
            'Vanilla, Fabric, Quilt, Forge y NeoForge con un clic',
            'Java se descarga automáticamente para cada versión de Minecraft',
            'Cada instancia tiene pestañas para contenido, archivos, mundos, capturas, historial y registros',
          ],
          shot: { file: '0.2.1/library.png', alt: 'La biblioteca de instancias de TRS Launcher con instancias de Minecraft ordenadas en grupos', caption: 'La biblioteca con tus propios grupos' },
        },
        {
          id: 'accounts',
          title: 'Login de Microsoft y varias cuentas',
          text: [
            'Inicias sesión con tu cuenta de Microsoft en la propia página de Microsoft: el launcher nunca ve tu contraseña. Añade varias cuentas y cambia entre ellas desde la barra de título. Con el TRS Client puedes cambiar de cuenta incluso dentro del juego, sin reiniciarlo.',
            'Skins y capas tienen su propia página con vista previa en 3D: colecciona skins, pruébalas y ponlas en tu cuenta. Con los servicios TRS opcionales, tu colección de skins te sigue a cada PC.',
          ],
        },
        {
          id: 'import',
          title: 'Trae tus instancias de otros launchers',
          text: [
            '¿Ya juegas con otro launcher? «Importar desde otro launcher» encuentra los launchers instalados en tu PC y muestra, para cada instancia, lo que se viene contigo: mundos, mods, paquetes de recursos y de shaders, ajustes y tu lista de servidores. La versión de Minecraft y el cargador de mods se detectan solos, y los mods importados se pueden seguir actualizando.',
            'Los datos de inicio de sesión de otros launchers nunca se leen ni se copian, y sus archivos solo se leen: tu configuración anterior queda exactamente como estaba.',
          ],
        },
        {
          id: 'together',
          title: 'Clips, amigos, chat y alojar mundos',
          text: [
            'Pulsa F9 en el juego para guardar los últimos momentos como clip de vídeo, o F10 para grabar una sesión entera. Solo se graba la ventana del juego y los clips se quedan en tu PC; en la galería de clips los reproduces, recortas y compartes. Los clips están disponibles en Windows.',
            'Con las funciones en línea opcionales de TRS tienes lista de amigos, mensajes directos y chats de grupo, en el launcher y en el juego. Aloja tu mundo de un jugador para hasta 10 amigos sin abrir puertos: lo ven en Social → Mundos y entran con un clic o con un código.',
          ],
          shot: { file: '0.8.0/worlds.png', alt: 'Social → Mundos en TRS Launcher con el mundo de Minecraft abierto de un amigo', caption: 'Únete a los mundos de tus amigos desde el launcher' },
        },
        {
          id: 'crash-helper',
          title: 'Un asistente de cierres que explica qué falló',
          text: [
            'Cuando Minecraft se cierra por un error, el launcher lee el informe y el registro y explica con palabras claras qué pasó: qué mods chocan, qué dependencia falta o si el problema es la memoria, la versión de Java o el driver gráfico. Botones como «Desactivar mod», «Instalar dependencia» o «Aumentar RAM» lo arreglan con un clic, y cada cambio se confirma antes.',
            'El análisis se hace solo en tu PC. La pestaña de registros tiene búsqueda, filtros y trazas de pila plegables, y puedes compartir un registro como enlace cuando pidas ayuda.',
          ],
          shot: { file: '0.10.0/crash-helper.png', alt: 'El asistente de cierres de TRS Launcher explica un cierre de Minecraft con un botón para arreglarlo', caption: 'La causa en palabras claras y un botón para arreglarla' },
        },
        {
          id: 'extras',
          title: 'Logros, actualizaciones automáticas y ocho idiomas',
          text: [
            'Los logros premian el tiempo de juego, probar funciones del launcher y formar parte de la comunidad; algunos traen una capa o un emote. Las nuevas versiones del launcher se descargan en segundo plano mientras juegas y se instalan con un clic, y el TRS Client recibe correcciones por su propio canal de actualizaciones.',
            'El launcher habla inglés, alemán y español, además de francés, polaco, portugués (Brasil), turco y neerlandés en beta.',
          ],
          shot: { file: '0.14.0/achievements.png', alt: 'Logros en TRS Launcher con puntos, rareza y recompensas', caption: 'Logros con puntos, rareza y recompensas' },
        },
        {
          id: 'privacy',
          title: 'Privado y de código abierto',
          text: [
            'TRS Launcher no tiene telemetría, analíticas ni publicidad. Las funciones en línea como capas, amigos y chat solo se activan si aceptas, y puedes borrar todos los datos de TRS cuando quieras. Todo el código fuente está en GitHub bajo GPL-3.0, así que cualquiera puede comprobar cómo funciona.',
            'En Windows 10 y 11 se instala para tu usuario sin permisos de administrador. En Linux funciona como AppImage, .deb, .rpm o desde el AUR.',
          ],
          link: { to: '/download', label: 'Descargar para Windows o Linux' },
        },
      ],
      faq: [
        { q: '¿TRS Launcher es un launcher de Minecraft gratis?', a: 'Sí. TRS Launcher es gratis y de código abierto bajo GPL-3.0. Solo necesitas tu propia cuenta de Minecraft: Java Edition.' },
        { q: '¿Es para Java Edition o Bedrock Edition?', a: 'TRS Launcher está hecho para Minecraft: Java Edition. Bedrock Edition no es compatible.' },
        { q: '¿En qué sistemas operativos funciona?', a: 'En Windows 10 y 11 (64 bits) y en distribuciones Linux actuales de 64 bits como Arch, Ubuntu, Debian y Fedora.' },
        { q: '¿Puedo usar más de una cuenta de Microsoft?', a: 'Sí. Añade todas las cuentas que quieras y cambia entre ellas en la barra de título; con el TRS Client, incluso dentro del juego.' },
        { q: '¿Puedo traer mis instancias de otro launcher?', a: 'Sí. Mundos, mods, paquetes de recursos y de shaders, ajustes y listas de servidores se vienen contigo, y la versión y el cargador de mods se detectan solos. Los datos de inicio de sesión de otros launchers nunca se tocan.' },
      ],
      cta: { title: 'Consigue el launcher de Minecraft gratis', text: 'Descarga TRS Launcher para Windows o Linux: el TRS Client viene incluido.' },
    },

    'redstone-launcher': {
      seo: {
        title: 'Redstone Launcher para Minecraft – TRS Launcher y redstone',
        description:
          'TRS Launcher, el launcher redstone de Minecraft: diseño redstone y TRS Client con intensidad de señal, medidor de reloj y biblioteca de circuitos.',
      },
      name: 'Redstone Launcher',
      teaser: 'Diseño redstone desde la página de inicio hasta los menús del juego, más herramientas de redstone y una biblioteca de circuitos.',
      kicker: 'El Redstone Launcher',
      title: 'El launcher de Minecraft hecho para la redstone',
      lead: 'TRS Launcher de TheRedstonee es el Redstone Launcher por dos motivos: está construido con estilo redstone, desde la página de inicio hasta los menús del juego, y el TRS Client que incluye trae herramientas que te ayudan a construir, entender y depurar circuitos de redstone.',
      sections: [
        {
          id: 'design',
          title: 'Un launcher con estilo redstone',
          text: [
            'Abre el launcher y un circuito de redstone de verdad recorre la página de inicio: relojes, pistones, lámparas y antorchas que parpadean. La línea principal lleva al botón de jugar y se carga mientras tu juego arranca; cuando Minecraft está en marcha, la lámpara se enciende. El circuito funciona en silencio detrás de cada página, y en los ajustes puedes reducir las animaciones si lo prefieres.',
            'El TRS Client lleva el estilo al juego: una pantalla de título redstone con tu propia skin sobre una plataforma giratoria de redstone, botones de piedra que se iluminan, tarjetas de servidor con barras de ping de redstone y pantallas de carga con una fila de lámparas de redstone. Cada menú puede volver al aspecto clásico.',
          ],
          shot: { file: '0.4.0/running.png', alt: 'La página de inicio de TRS Launcher con un circuito de redstone y una lámpara encendida mientras Minecraft está en marcha', caption: 'Mientras juegas, la lámpara brilla' },
        },
        {
          id: 'signal',
          title: 'Ve la intensidad de señal, el retardo y la salida',
          text: [
            'Mira polvo de redstone, un repetidor, un comparador o un pistón y el TRS Client muestra la intensidad de señal de 0 a 15, el retardo del repetidor, el modo del comparador y su salida. Se acabó adivinar por qué una línea se apaga después de 15 bloques.',
            'La superposición de redstone (F6) va un paso más allá: escribe la intensidad de señal como número sobre cada polvo de redstone a tu alrededor, ideal para líneas largas, clasificadores de objetos y construcciones compactas.',
          ],
          shot: { file: '0.5.0/redstone-overlay.png', alt: 'Intensidad de señal de redstone como número sobre cada polvo de redstone en el TRS Client', caption: 'La superposición muestra la señal de cada polvo' },
        },
        {
          id: 'clock',
          title: 'Mide relojes de redstone en ticks',
          text: [
            'El medidor de reloj mide la frecuencia, el periodo y la duración del pulso de un reloj de redstone en ticks y dibuja la señal en un pequeño osciloscopio. Úsalo para comprobar un reloj, ajustar un bucle de repetidores o averiguar por qué una granja se activa demasiado a menudo.',
          ],
          points: [
            'Frecuencia, periodo y duración del pulso en ticks del juego',
            'Un pequeño osciloscopio muestra la señal a lo largo del tiempo',
            'El paquete de módulos «Redstone» prepara el cliente para construir con un clic',
          ],
        },
        {
          id: 'circuits',
          title: 'Una biblioteca de circuitos con bloques fantasma',
          text: [
            'La biblioteca de circuitos del TRS Client reúne circuitos de redstone listos: puertas lógicas, una cadena de repetidores, una torre de antorchas, relojes, memorias como un latch RS, flip-flops T y un latch D, circuitos de pulso, una puerta de pistones de 2×2, una escalera oculta, bases de granjas como un filtro de objetos, un horno automático y un ascensor de objetos, y pantallas. Cada uno trae explicación, dificultad, tamaño, una lista de materiales que revisa tu inventario, la versión de Minecraft que necesita y si funciona de forma fiable en servidores, además de una vista previa en 3D que puedes ver capa por capa.',
            'Elige un circuito y colócalo como plantilla en tu mundo. Los bloques fantasma muestran qué va dónde: verde cuando un bloque está bien, rojo cuando está mal y gris mientras falta, con barra de progreso y vista capa por capa. Solo es una visualización: no se construye nada por ti y no se envía nada al servidor. Las plantillas funcionan desde Minecraft 1.8.9 (no en 1.7.10 ni 1.13.2).',
          ],
          shot: { file: '0.10.0/circuit-ghost.png', alt: 'Un circuito de redstone mostrado como bloques fantasma en un mundo de Minecraft con el TRS Client', caption: 'Construye un circuito bloque a bloque con una plantilla' },
        },
        {
          id: 'share-circuits',
          title: 'Circuitos nuevos sin actualizar el mod',
          text: [
            'Los circuitos vienen del servidor de TRS, así que aparecen nuevos sin actualizar el mod, y una copia local sigue funcionando sin conexión. ¿Construiste algo ingenioso? Márcalo en tu mundo (hasta 16×16×16 bloques), ponle nombre, categoría y una descripción corta y envíalo: el equipo revisa cada envío.',
            'Todos los circuitos publicados también están en esta web, cada uno con su propia página y una descarga para el bloque estructural.',
          ],
          shot: { file: '0.10.0/circuits.png', alt: 'La biblioteca de circuitos del TRS Client con circuitos de redstone listos, explicación y materiales', caption: 'Circuitos de redstone listos con explicación y materiales' },
          link: { to: '/circuits', label: 'Ver la biblioteca de circuitos' },
        },
        {
          id: 'maker',
          title: 'Hecho por un fan de la redstone',
          text: [
            'TRS Launcher lo desarrolla TheRedstonee, un fan de la redstone que crea las herramientas que él mismo quiere usar. Por eso el TRS Client trata la redstone como una función principal junto al rendimiento y el PvP, por eso el launcher tiene este aspecto y por eso a menudo se le llama simplemente «el Redstone Launcher».',
          ],
        },
      ],
      faq: [
        { q: '¿Por qué TRS Launcher se llama Redstone Launcher?', a: 'Porque la redstone es su núcleo: el launcher y el TRS Client tienen estilo redstone, y el TRS Client incluye herramientas de redstone como intensidad de señal, superposición de redstone, medidor de reloj y una biblioteca de circuitos. Lo desarrolla TheRedstonee.' },
        { q: '¿Qué herramientas de redstone tiene el TRS Client?', a: 'Intensidad de señal para polvo, repetidores, comparadores y pistones, una superposición con la señal sobre cada polvo (F6), un medidor de reloj en ticks y una biblioteca de circuitos con plantillas de bloques fantasma.' },
        { q: '¿Los bloques fantasma construyen el circuito por mí?', a: 'No. Las plantillas solo son una visualización: muestran qué va dónde y tú colocas cada bloque. No se envía nada al servidor.' },
        { q: '¿Puedo compartir mis propios circuitos de redstone?', a: 'Sí. Marca un circuito en tu mundo (hasta 16×16×16 bloques), añade nombre, categoría y descripción y envíalo con tu sesión de TRS. Tras la revisión aparece en la biblioteca y en la web.' },
        { q: '¿El diseño redstone está solo en el launcher?', a: 'No. El TRS Client lleva la pantalla de título y los menús redstone al juego en cada versión de Minecraft compatible. Cada menú puede volver al aspecto clásico.' },
      ],
      cta: { title: 'Construye mejores circuitos', text: 'Descarga TRS Launcher gratis: el TRS Client con todas las herramientas de redstone viene incluido.' },
    },

    modpacks: {
      seo: {
        title: 'Modpacks de Minecraft: Fabric, Forge, NeoForge y Quilt | TRS',
        description:
          'Instala mods y modpacks de Modrinth y CurseForge con TRS Launcher: Fabric, Forge, NeoForge y Quilt, comparte packs con un código y conserva tus cambios.',
      },
      name: 'Mods y modpacks',
      teaser: 'Fabric, Forge, NeoForge y Quilt con Modrinth y CurseForge integrados: instala, actualiza y comparte modpacks.',
      kicker: 'Mods y modpacks',
      title: 'Modpacks de Minecraft para Fabric, Forge, NeoForge y Quilt',
      lead: 'TRS Launcher es un launcher de Minecraft para mods con Modrinth y CurseForge integrados. Elige un cargador de mods, instala mods sueltos o un modpack completo con un clic, comparte tu propio pack con un código y mantenlo al día, sin salir de la app.',
      sections: [
        {
          id: 'loaders',
          title: 'Cada cargador de mods, listo para usar',
          text: [
            'Crea una instancia con Vanilla, Fabric, Quilt, Forge o NeoForge para cualquier versión de Minecraft desde 1.7.10 hasta la más nueva. El launcher instala el cargador, sus bibliotecas y el Java correcto por ti: sin instaladores que ejecutar ni carpetas que copiar.',
            'Cada instancia tiene sus propios mods, paquetes de recursos, shaders, paquetes de datos y mundos, así que un modpack técnico grande y una configuración ligera de Fabric conviven sin estorbarse.',
          ],
        },
        {
          id: 'discover',
          title: 'Modrinth y CurseForge en un solo lugar',
          text: [
            'La página Descubrir busca mods, modpacks, paquetes de recursos, shaders y paquetes de datos en Modrinth y CurseForge: un interruptor cambia la plataforma y los filtros se mantienen. Cada proyecto tiene su propia página con descripción, galería, versiones y dependencias.',
            'Al instalar un mod se instalan también sus dependencias necesarias. Las actualizaciones se detectan para el contenido de ambas plataformas, y puedes cambiar un mod a otra versión mientras el historial de la instancia muestra qué cambió. Si un autor solo permite descargas en el propio CurseForge, el launcher muestra los archivos con un botón a su página y los recoge de tu carpeta de descargas.',
          ],
          shot: { file: '0.2.0/content.png', alt: 'Mods, paquetes de recursos y shaders de una instancia de Minecraft en una sola lista en TRS Launcher', caption: 'Todo lo que contiene una instancia en una lista' },
        },
        {
          id: 'install',
          title: 'Instala modpacks con un clic',
          text: [
            'Los modpacks de Modrinth y CurseForge – desde Descubrir o como archivo .mrpack o .zip descargado – se convierten en una instancia nueva. Los packs grandes descargan primero los archivos grandes y varios a la vez, y la instancia nueva muestra su progreso hasta que todo está listo.',
            'Al instalar un pack eliges una vez si se añade el TRS Client. Si el pack ya trae mods que se solapan con él, como su propio minimapa o HUD, el launcher te dice cuáles y preselecciona «Sin TRS Client». Tu elección se guarda y se puede cambiar en los ajustes de la instancia.',
          ],
          shot: { file: '0.10.0/modpack-choice.png', alt: 'Instalar un modpack de Minecraft con o sin el TRS Client en TRS Launcher', caption: 'Instala un modpack con o sin el TRS Client' },
        },
        {
          id: 'compat',
          title: 'Mods que funcionan juntos',
          text: [
            'Algunos mods solo funcionan con ciertas versiones de otros mods. El launcher lee esas reglas de los archivos de los mods: los presets eligen versiones que encajan, las actualizaciones que romperían otro mod se retienen y, antes de cada inicio, comprueba que a ningún mod le falte otro que necesita. Si aun así el juego se cierra, el asistente de cierres nombra los mods implicados y ofrece una solución.',
          ],
          points: [
            'Las dependencias necesarias se instalan automáticamente',
            'Las instancias con una pareja incompatible conocida reciben un botón «Arreglar»',
            'Tus propios presets: un conjunto de mods, paquetes de recursos y shaders para cualquier instancia',
          ],
        },
        {
          id: 'share',
          title: 'Comparte tu modpack con un código',
          text: [
            '¿Has creado el pack perfecto? Compártelo desde la instancia: el launcher sube tu lista de mods y los ajustes que elijas y te da un código (TRS-XXXX-XXXX) y un enlace, o envía el pack directamente a tus amigos. Tú decides si el código vale 1, 7 o 30 días o no caduca. Los demás lo instalan con «Modpack por código», y la página del enlace muestra lo que contiene antes de instalar nada.',
            'Sube una versión nueva y conserva el mismo código: todos los que instalaron el pack ven «Actualizar», y los archivos que cambiaron ellos mismos se quedan como estaban. Los archivos de mods que no están en Modrinth necesitan una confirmación al compartir y muestran un aviso al instalar.',
          ],
          shot: { file: '0.12.0/share-result.png', alt: 'Compartir un modpack de Minecraft en TRS Launcher con un código, un enlace o directamente con amigos', caption: 'Comparte un modpack: código, enlace o directo a tus amigos' },
        },
        {
          id: 'export',
          title: 'Exporta y haz copias de seguridad',
          text: [
            'Exporta cualquier instancia como modpack .mrpack, guarda un mundo como ZIP o elige archivos sueltos, todo desde la pestaña Compartir de la instancia. Los packs exportados dejan fuera el propio TRS Client y sus archivos privados.',
          ],
          shot: { file: '0.12.0/pack-update.png', alt: 'Actualizar un modpack de Minecraft compartido en TRS Launcher conservando tus propios cambios', caption: '¿Versión nueva? Actualiza con un clic y tus cambios se quedan' },
        },
      ],
      faq: [
        { q: '¿Qué cargadores de mods admite TRS Launcher?', a: 'Fabric, Quilt, Forge y NeoForge, además de Vanilla, para cada versión de Minecraft desde 1.7.10 hasta la más nueva, siempre que el cargador exista para esa versión.' },
        { q: '¿Puedo instalar modpacks de CurseForge?', a: 'Sí. Instálalos desde Descubrir o abre un .zip de CurseForge descargado; el pack se convierte en una instancia nueva. Los archivos .mrpack de Modrinth funcionan igual.' },
        { q: '¿Cómo comparto un modpack con mis amigos?', a: 'Abre la instancia, elige Compartir → Compartir modpack y selecciona lo que se incluye. Recibes un código TRS y un enlace, o envías el pack directamente a tus amigos. Ellos lo instalan con Biblioteca → «Modpack por código».' },
        { q: '¿Se conservan mis cambios al actualizar un modpack?', a: 'Sí. Cuando quien compartió el pack sube una versión nueva ves «Actualizar», y los archivos que cambiaste tú se quedan como estaban.' },
        { q: '¿Puedo usar shaders?', a: 'Sí. Instala paquetes de shaders de Modrinth o CurseForge, o elige el nivel «Shaders ligeros» o «Shaders bonitos» del preset de FPS boost, que añade Iris y un shader en Fabric, Quilt y NeoForge. Pulsa K en el juego para activar o desactivar los shaders.' },
      ],
      cta: { title: 'Empieza tu próximo modpack', text: 'Descarga TRS Launcher gratis e instala tu primer modpack en un minuto.' },
    },

    'fps-boost-pvp-client': {
      seo: {
        title: 'Launcher de rendimiento para Minecraft con FPS boost y PvP | TRS',
        description:
          'TRS Launcher es un launcher de rendimiento gratis para Minecraft: FPS boost, HUD de PvP con teclas y CPS, zoom y minimapa, desde 1.7.10 y 1.8.9.',
      },
      name: 'Cliente PvP y FPS boost',
      teaser: 'El TRS Client: FPS boost, HUD de PvP con teclas y CPS, zoom, minimapa y emotes, desde 1.7.10 hasta la última versión.',
      kicker: 'TRS Client',
      title: 'El launcher de rendimiento gratis para Minecraft con FPS boost y cliente PvP',
      lead: 'El TRS Client es el client mod que viene con TRS Launcher. Añade FPS boost, un HUD de PvP limpio, zoom, freelook, un minimapa, emotes y capas a Minecraft – desde 1.7.10 y 1.8.9 hasta la última versión – y sigue siendo justo en los servidores.',
      sections: [
        {
          id: 'versions',
          title: 'Un cliente para versiones antiguas y nuevas',
          text: [
            'El launcher añade el TRS Client a tus instancias automáticamente: Forge desde 1.7.10, Fabric y Quilt desde 1.14.4 y NeoForge, hasta la versión más nueva. Tanto si juegas PvP en 1.8.9 como la última versión, tienes el mismo menú (Mayús derecha), los mismos módulos y el mismo editor de HUD. Puedes desactivarlo en cualquier instancia.',
            'Los paquetes de módulos preparan el cliente para tu estilo de juego con un clic – PvP, Redstone, Comodidad o Mínimo – y, con los servicios TRS, tus módulos, diseños de HUD y teclas te siguen a cada PC.',
          ],
          shot: { file: '0.3.0/hud-editor.png', alt: 'Mover elementos del HUD en Minecraft con el editor de HUD del TRS Client', caption: 'Coloca cada elemento del HUD donde quieras' },
        },
        {
          id: 'fps',
          title: 'Un launcher de rendimiento con un FPS boost que puedes medir',
          text: [
            'Las instancias nuevas empiezan sin límite de fotogramas, con VSync desactivado y ajustes de Java optimizados. En Fabric, el TRS Client trae mods de rendimiento gratuitos como Lithium, FerriteCore, ImmediatelyFast y ModernFix donde existen para tu versión, y el preset de FPS boost del launcher añade Sodium y compañía en tres niveles: Máx. FPS, Shaders ligeros y Shaders bonitos.',
            'En el juego, FPS Boost pone todo en Bajo, Medio o Alto con un clic y muestra los FPS antes y después. Una comprobación de rendimiento encuentra lo que frena tus FPS en los ajustes de vídeo y lo arregla con un clic, Dynamic FPS baja la tasa de fotogramas en segundo plano y Entity Culling se salta lo que no puedes ver. Cada cambio se puede deshacer.',
          ],
          points: [
            'Modo gráfico «Bonito» o «Máx. FPS»',
            'La tarjeta gráfica dedicada en portátiles, en Windows y Linux',
            'Otros mods de rendimiento se detectan y conservan su parte del trabajo',
          ],
        },
        {
          id: 'pvp',
          title: 'HUD de PvP: teclas, CPS y contadores',
          text: [
            'Para PvP, el TRS Client muestra lo que importa en una pelea: teclas, contador de CPS, alcance, combos y velocidad, un contador de objetos para flechas, tótems, pociones, manzanas doradas y perlas de ender, y respuesta de golpe con marcador de impacto. Añade una mira propia, color de golpe, animaciones de 1.7, fuego bajo y una posición del escudo que deja la vista despejada.',
            'El chat tiene hora, mensajes repetidos agrupados y menciones, el indicador de ping muestra tu ping real con jitter y el modo streamer oculta nombres y direcciones de servidores.',
          ],
          shot: { file: '0.9.0/pvp-hud.png', alt: 'El HUD de PvP del TRS Client con contador de objetos, marcador de impacto y avisos', caption: 'HUD de contadores, marcador de impacto y avisos' },
        },
        {
          id: 'fair-play',
          title: 'Juego limpio en los servidores',
          text: [
            'Todo en el TRS Client es visualización o comodidad: sin cambios de alcance ni de hitbox, sin autoclic, y la respuesta de golpe nunca cambia tus ataques. Lo único que el cliente envía por su cuenta es Auto-GG, que sigue desactivado hasta que lo actives. El minimapa tiene un interruptor de juego limpio – sin vista de cuevas, y criaturas y jugadores solo cuando podrías verlos – y los servidores que piden juego limpio a los mods de mapas se respetan automáticamente.',
            'Algunos servidores prohíben funciones sueltas como freelook. Puedes desactivarlas, y freelook se apaga solo en los servidores que indiques.',
          ],
        },
        {
          id: 'maps',
          title: 'Zoom, freelook, minimapa y mapa del mundo',
          text: [
            'Zoom suave (V), freelook (Alt izquierda) y correr y agacharse fijos vienen incluidos. El minimapa se desliza con suavidad, toma sus colores de tu paquete de recursos, muestra cabezas de criaturas y coloca los puntos de ruta lejanos en su borde; bajo techo mira dentro de los edificios en lugar de mostrar el tejado. Pulsa M para un mapa del mundo a pantalla completa con zoom suave, lista de puntos de ruta, otras dimensiones y exportación a PNG.',
          ],
          shot: { file: '0.13.0/minimap.png', alt: 'El minimapa del TRS Client con puntos de ruta en el borde y cabezas reales de criaturas', caption: 'Minimapa con puntos de ruta en el borde y cabezas reales de criaturas' },
        },
        {
          id: 'extras',
          title: 'Emotes, capas, capturas y notas',
          text: [
            'Abre la rueda de emotes y saluda, baila o celebra: los demás jugadores de TRS lo ven. Las capas TRS se mueven como tela con la física de capas; muchas son gratis y algunas están animadas o en HD. Tras F2, una pequeña vista previa te deja editar, copiar o enviar tu captura, y el editor de capturas recorta, dibuja flechas y texto y pixela nombres. Cada mundo y servidor tiene además su propio cuaderno con listas de tareas y coordenadas en las que puedes hacer clic.',
          ],
          shot: { file: '0.5.0/emote-wheel.png', alt: 'La rueda de emotes del TRS Client en Minecraft', caption: 'La rueda de emotes' },
          link: { to: '/capes', label: 'Ver todas las capas TRS' },
        },
      ],
      faq: [
        { q: '¿TRS Launcher es un launcher de rendimiento?', a: 'Sí. TRS Launcher configura Java y la memoria por ti, inicia las instancias vanilla con la optimización TRS (Fabric, el TRS Client y mods de rendimiento por debajo) y quita los límites de fotogramas de las instancias nuevas. Funciona con todas las versiones y cargadores de mods, no solo con una versión de cliente.' },
        { q: '¿El TRS Client es gratis?', a: 'Sí. El TRS Client viene gratis con TRS Launcher, que es de código abierto bajo GPL-3.0.' },
        { q: '¿Qué versiones de Minecraft admite el TRS Client?', a: 'Forge desde 1.7.10 (incluida 1.8.9), Fabric y Quilt desde 1.14.4 y NeoForge, hasta la versión más nueva. Algunas funciones necesitan versiones más nuevas, por ejemplo las plantillas de circuitos desde 1.8.9.' },
        { q: '¿El TRS Client de verdad sube los FPS?', a: 'Quita límites habituales de FPS como el tope de fotogramas por defecto y VSync, trae mods de rendimiento en Fabric y te deja elegir entre «Bonito» y «Máx. FPS». Cuánto ganas depende de tu PC y de la versión: FPS Boost muestra los FPS antes y después para que lo compruebes.' },
        { q: '¿Está permitido el TRS Client en servidores PvP?', a: 'Solo muestra información que tu juego ya tiene y no cambia alcance, hitboxes ni clics. Las funciones que algunos servidores no permiten, como freelook, se pueden desactivar. Revisa siempre las reglas de tu servidor.' },
        { q: '¿Tiene teclas en pantalla y contador de CPS?', a: 'Sí: teclas, CPS, alcance, combos, velocidad, contador de objetos, ping y más. Mueve y cambia el tamaño de cada elemento en el editor de HUD.' },
      ],
      cta: { title: 'Más FPS, un HUD más limpio', text: 'Descarga TRS Launcher gratis: el TRS Client se añade a tus instancias automáticamente.' },
    },
  },
}
