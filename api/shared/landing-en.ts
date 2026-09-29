// Themen-Seiten auf Englisch (Standard). Aufbau und Regeln: shared/landing.ts.

import type { LandingTexts } from './landing'

export const landingEn: LandingTexts = {
  common: {
    topics: 'Topics',
    faqTitle: 'Questions and answers',
    related: 'More about TRS Launcher',
    download: 'Download for free',
    features: 'All features',
    allQuestions: 'All questions',
    note: 'Free · Open source (GPL-3.0) · Windows & Linux',
    learnMore: 'Learn more',
    onThisPage: 'On this page',
  },
  pages: {
    'minecraft-launcher': {
      seo: {
        title: 'Free Minecraft Launcher for Windows & Linux – TRS Launcher',
        description:
          'TRS Launcher is a free, open-source Minecraft: Java Edition launcher for Windows and Linux: every version, Microsoft login, mods, clips, friends.',
      },
      name: 'Minecraft launcher',
      teaser: 'The free, open-source launcher for Minecraft: Java Edition – every version, Microsoft login, clips and friends.',
      kicker: 'Minecraft: Java Edition launcher',
      title: 'The free Minecraft launcher for Windows and Linux',
      lead: 'TRS Launcher – the Redstone Launcher by TheRedstonee – installs and starts every Minecraft: Java Edition version. It is free, open source under GPL-3.0 and brings everything you need to play: instances, mod loaders, your Microsoft accounts, clips, friends and its own client mod, the TRS Client.',
      sections: [
        {
          id: 'instances',
          title: 'Every Minecraft version in its own instance',
          text: [
            'An instance is a separate Minecraft installation with its own version, mods, worlds and settings. Create as many as you like – one for the newest release, one for 1.8.9 PvP, one for a big modpack – and they never get in each other’s way. The library shows them as cards with sorting, filters and your own groups.',
            'Every release from 1.7.10 up to the newest version is available, snapshots included, with Vanilla, Fabric, Quilt, Forge or NeoForge. The launcher downloads the matching Java for every version by itself, so you never have to install or choose Java by hand.',
          ],
          points: [
            'Vanilla, Fabric, Quilt, Forge and NeoForge with one click',
            'Java is downloaded automatically for each Minecraft version',
            'Every instance has tabs for content, files, worlds, screenshots, history and logs',
          ],
          shot: { file: '0.2.1/library.png', alt: 'The instance library of TRS Launcher with Minecraft instances sorted into groups', caption: 'The library with your own groups' },
        },
        {
          id: 'accounts',
          title: 'Microsoft login and multiple accounts',
          text: [
            'You sign in with your Microsoft account on Microsoft’s own sign-in page – the launcher never sees your password. Add several accounts and switch between them right from the title bar. With the TRS Client you can even switch accounts inside the game, without restarting it.',
            'Skins and capes have their own page with a 3D preview: collect skins, try them on and put them on your account. With the optional TRS services your skin library follows you to every PC.',
          ],
        },
        {
          id: 'import',
          title: 'Bring your instances from other launchers',
          text: [
            'Already play with another launcher? “Import from another launcher” finds the launchers installed on your PC and shows, for every instance, what comes along: worlds, mods, resource and shader packs, settings and your server list. The Minecraft version and mod loader are detected for you, and imported mods stay updatable.',
            'Sign-in data of other launchers is never read or copied, and their files are only read – your old setup stays exactly as it was.',
          ],
        },
        {
          id: 'together',
          title: 'Clips, friends, chat and world hosting',
          text: [
            'Press F9 in the game to save the last moments as a video clip, or F10 to record a whole session. Only the game window is recorded, and clips stay on your PC; play, trim and share them in the clip gallery. Clips are available on Windows.',
            'With the optional TRS online features you get a friends list, direct messages and group chats – in the launcher and in the game. Host your singleplayer world for up to 10 friends without port forwarding: they see it under Social → Worlds and join with one click or a join code.',
          ],
          shot: { file: '0.8.0/worlds.png', alt: 'Social → Worlds in TRS Launcher with a friend’s open Minecraft world to join', caption: 'Join your friends’ worlds right from the launcher' },
        },
        {
          id: 'crash-helper',
          title: 'A crash helper that explains what went wrong',
          text: [
            'When Minecraft crashes, the launcher reads the crash report and the log and explains in plain words what happened: which mods clash, which dependency is missing, or whether memory, the Java version or the graphics driver is the problem. Buttons like “Disable mod”, “Install dependency” or “Increase RAM” fix it with one click, and every change is confirmed first.',
            'The analysis runs only on your PC. The Logs tab adds search, filters and collapsible stack traces, and you can share a log as a link when you ask for help.',
          ],
          shot: { file: '0.10.0/crash-helper.png', alt: 'The crash helper of TRS Launcher explaining a Minecraft crash with a button to fix it', caption: 'The cause in plain words and a button to fix it' },
        },
        {
          id: 'extras',
          title: 'Achievements, automatic updates and eight languages',
          text: [
            'Achievements reward playtime, trying out launcher features and being part of the community; some come with a cape or an emote. New launcher versions download in the background while you play and install with one click, and the TRS Client gets fixes through its own update channel.',
            'The launcher speaks English, German and Spanish, plus French, Polish, Portuguese (Brazil), Turkish and Dutch as beta.',
          ],
          shot: { file: '0.14.0/achievements.png', alt: 'Achievements in TRS Launcher with points, rarities and rewards', caption: 'Achievements with points, rarities and rewards' },
        },
        {
          id: 'privacy',
          title: 'Private and open source',
          text: [
            'TRS Launcher has no telemetry, analytics or advertising. Online features such as capes, friends and chat only switch on after you agree, and you can delete all TRS data at any time. The whole source code is on GitHub under GPL-3.0, so anyone can check how it works.',
            'On Windows 10 and 11 it installs for your user without admin rights. On Linux it runs as AppImage, .deb, .rpm or from the AUR.',
          ],
          link: { to: '/download', label: 'Download for Windows or Linux' },
        },
      ],
      faq: [
        { q: 'Is TRS Launcher a free Minecraft launcher?', a: 'Yes. TRS Launcher is free and open source under GPL-3.0. You only need your own Minecraft: Java Edition account.' },
        { q: 'Is it for Java Edition or Bedrock Edition?', a: 'TRS Launcher is made for Minecraft: Java Edition. Bedrock Edition is not supported.' },
        { q: 'Which operating systems does it run on?', a: 'Windows 10 and 11 (64-bit) and current 64-bit Linux distributions such as Arch, Ubuntu, Debian and Fedora.' },
        { q: 'Can I use more than one Microsoft account?', a: 'Yes. Add as many accounts as you like and switch between them in the title bar – with the TRS Client even inside the game.' },
        { q: 'Can I move my instances from another launcher?', a: 'Yes. Worlds, mods, resource and shader packs, settings and server lists come along, and the version and mod loader are detected for you. Sign-in data of other launchers is never touched.' },
      ],
      cta: { title: 'Get the free Minecraft launcher', text: 'Download TRS Launcher for Windows or Linux – the TRS Client is included.' },
    },

    'redstone-launcher': {
      seo: {
        title: 'Redstone Launcher for Minecraft – TRS Launcher & Redstone Tools',
        description:
          'TRS Launcher is the redstone Minecraft launcher: a redstone design and the TRS Client with signal strength, redstone overlay, clock meter and circuit library.',
      },
      name: 'Redstone launcher',
      teaser: 'Redstone design from the start page to the game menus, plus redstone tools and a circuit library in the TRS Client.',
      kicker: 'The Redstone Launcher',
      title: 'The Minecraft launcher made for redstone',
      lead: 'TRS Launcher by TheRedstonee is the Redstone Launcher for two reasons: it is built in the redstone look, from the start page to the menus in the game, and the TRS Client inside comes with tools that help you build, understand and debug redstone circuits.',
      sections: [
        {
          id: 'design',
          title: 'A launcher in the redstone look',
          text: [
            'Open the launcher and a real redstone circuit runs across the start page: clocks, pistons, lamps and flickering torches. The main line leads to the play button and charges up while your game starts – once Minecraft runs, the lamp glows. The circuit runs quietly behind every page, and you can reduce the animations in the settings if you prefer.',
            'The TRS Client continues the look in the game: a redstone title screen with your own skin on a slowly turning redstone turntable, stone buttons that light up, server cards with redstone ping bars and loading screens with a row of redstone lamps. Every menu can go back to the classic look.',
          ],
          shot: { file: '0.4.0/running.png', alt: 'The TRS Launcher start page with a redstone circuit and a glowing lamp while Minecraft runs', caption: 'While the game runs, the lamp glows' },
        },
        {
          id: 'signal',
          title: 'See signal strength, delays and outputs',
          text: [
            'Look at redstone dust, a repeater, a comparator or a piston and the TRS Client shows the signal strength from 0 to 15, the repeater delay, the comparator mode and its output. No more guessing why a line goes dark after 15 blocks.',
            'The redstone overlay (F6) goes one step further: it writes the signal strength as a number above every piece of redstone dust around you – ideal for long lines, item sorters and compact builds.',
          ],
          shot: { file: '0.5.0/redstone-overlay.png', alt: 'Redstone signal strength shown as a number above every redstone dust in the TRS Client', caption: 'The redstone overlay shows the signal of every dust' },
        },
        {
          id: 'clock',
          title: 'Measure redstone clocks in ticks',
          text: [
            'The clock meter measures the frequency, period and pulse length of a redstone clock in ticks and draws the signal on a small oscilloscope. Use it to check a clock, tune a repeater loop or find out why a farm triggers too often.',
          ],
          points: [
            'Frequency, period and pulse length in game ticks',
            'A small oscilloscope shows the signal over time',
            'The “Redstone” module pack sets up the client for building with one click',
          ],
        },
        {
          id: 'circuits',
          title: 'A circuit library with ghost blocks',
          text: [
            'The circuit library in the TRS Client collects ready-made redstone circuits: logic gates, a repeater chain, a torch tower, clocks, memory circuits such as an RS latch, T flip-flops and a D latch, pulse circuits, a 2×2 piston door, a hidden staircase, farm basics like an item filter, an automatic furnace and an item elevator, and displays. Each one comes with an explanation, difficulty, size, a material list that checks your inventory, the Minecraft version it needs and whether it runs reliably on servers – plus a 3D preview you can view layer by layer.',
            'Pick a circuit and place it as a template in your world. Ghost blocks show what goes where: green when a block is right, red when it is wrong and grey while it is missing, with a progress bar and a layer-by-layer view. It is display only – nothing is built for you and nothing is sent to the server. Templates work from Minecraft 1.8.9 (not on 1.7.10 and 1.13.2).',
          ],
          shot: { file: '0.10.0/circuit-ghost.png', alt: 'A redstone circuit shown as ghost blocks in a Minecraft world with the TRS Client', caption: 'Build a circuit block by block from a template' },
        },
        {
          id: 'share-circuits',
          title: 'New circuits without a mod update',
          text: [
            'The circuits come from the TRS server, so new ones appear without updating the mod, and a local copy keeps working offline. Built something clever? Mark it in your world (up to 16×16×16 blocks), give it a name, category and short description and submit it – the team reviews every submission.',
            'All published circuits are also on this website, each with its own page and a download for the structure block.',
          ],
          shot: { file: '0.10.0/circuits.png', alt: 'The circuit library of the TRS Client with ready-made redstone circuits, explanation and materials', caption: 'Ready-made redstone circuits with explanation and materials' },
          link: { to: '/circuits', label: 'Browse the circuit library' },
        },
        {
          id: 'maker',
          title: 'Made by a redstone fan',
          text: [
            'TRS Launcher is developed by TheRedstonee, a redstone fan who builds the tools he wants to use himself. That is why the TRS Client treats redstone as a first-class feature next to performance and PvP, why the launcher looks the way it does – and why its name is often shortened to “the Redstone Launcher”.',
          ],
        },
      ],
      faq: [
        { q: 'Why is TRS Launcher called the Redstone Launcher?', a: 'Because redstone is at its heart: the launcher and the TRS Client are designed in the redstone look, and the TRS Client includes redstone tools such as signal strength, a redstone overlay, a clock meter and a circuit library. It is developed by TheRedstonee.' },
        { q: 'Which redstone tools does the TRS Client have?', a: 'Signal strength for dust, repeaters, comparators and pistons, a redstone overlay with the signal above every dust (F6), a clock meter in ticks and a circuit library with ghost-block templates.' },
        { q: 'Do the ghost blocks build the circuit for me?', a: 'No. Templates are display only: they show what goes where, and you place every block yourself. Nothing is sent to the server.' },
        { q: 'Can I share my own redstone circuits?', a: 'Yes. Mark a circuit in your world (up to 16×16×16 blocks), add a name, category and description and submit it while signed in with TRS. After a review it appears in the library and on the website.' },
        { q: 'Is the redstone design only in the launcher?', a: 'No. The TRS Client brings the redstone title screen and menus into the game in every supported Minecraft version. Each menu can go back to the classic look.' },
      ],
      cta: { title: 'Build better circuits', text: 'Download TRS Launcher for free – the TRS Client with all redstone tools is included.' },
    },

    modpacks: {
      seo: {
        title: 'Minecraft Modpacks: Fabric, Forge, NeoForge & Quilt | TRS',
        description:
          'Install Minecraft mods and modpacks from Modrinth and CurseForge with TRS Launcher: Fabric, Forge, NeoForge and Quilt, share packs by code, keep your changes.',
      },
      name: 'Mods & modpacks',
      teaser: 'Fabric, Forge, NeoForge and Quilt with Modrinth and CurseForge built in – install, update and share modpacks.',
      kicker: 'Mods & modpacks',
      title: 'Minecraft modpacks for Fabric, Forge, NeoForge and Quilt',
      lead: 'TRS Launcher is a Minecraft mod launcher with Modrinth and CurseForge built in. Pick a mod loader, install single mods or a complete modpack with one click, share your own pack with a code and keep it up to date – without leaving the app.',
      sections: [
        {
          id: 'loaders',
          title: 'Every mod loader, set up for you',
          text: [
            'Create an instance with Vanilla, Fabric, Quilt, Forge or NeoForge for any Minecraft release from 1.7.10 up to the newest version. The launcher installs the loader, its libraries and the right Java for you – no installer files to run, no folders to copy.',
            'Every instance has its own mods, resource packs, shaders, data packs and worlds, so a big tech modpack and a light Fabric setup live side by side without getting in each other’s way.',
          ],
        },
        {
          id: 'discover',
          title: 'Modrinth and CurseForge in one place',
          text: [
            'The Discover page searches mods, modpacks, resource packs, shaders and data packs on Modrinth and CurseForge – one switch changes the platform, the filters stay the same. Every project has its own page with description, gallery, versions and dependencies.',
            'Install a mod and its required dependencies come along. Updates are found for content from both platforms, and you can switch a mod to another version while the instance history shows what changed. If an author only allows downloads on CurseForge itself, the launcher shows the files with a button to their page and picks them up from your downloads folder.',
          ],
          shot: { file: '0.2.0/content.png', alt: 'Mods, resource packs and shaders of a Minecraft instance in one content list in TRS Launcher', caption: 'Everything an instance contains in one list' },
        },
        {
          id: 'install',
          title: 'Install modpacks with one click',
          text: [
            'Modpacks from Modrinth and CurseForge – from Discover or as a downloaded .mrpack or .zip file – become a new instance. Big packs download large files first and several at a time, and the new instance shows its progress until everything is there.',
            'When you install a pack you choose once whether the TRS Client is added. If the pack already brings mods that overlap with it, such as its own minimap or HUD, the launcher tells you which ones and preselects “Without TRS Client”. Your choice is saved and can be changed in the instance settings.',
          ],
          shot: { file: '0.10.0/modpack-choice.png', alt: 'Installing a Minecraft modpack with or without the TRS Client in TRS Launcher', caption: 'Install a modpack with or without the TRS Client' },
        },
        {
          id: 'compat',
          title: 'Mods that work together',
          text: [
            'Some mods only work with certain versions of other mods. The launcher reads those rules from the mod files: presets pick versions that fit together, updates that would break another mod are held back, and before every start it checks that no mod is missing a mod it needs. If the game still crashes, the crash helper names the mods involved and offers a fix.',
          ],
          points: [
            'Required dependencies are installed automatically',
            'Instances with a known clashing pair get a “Fix” button',
            'Your own presets: a set of mods, resource packs and shaders you can apply to any instance',
          ],
        },
        {
          id: 'share',
          title: 'Share your modpack with a code',
          text: [
            'Made the perfect pack? Share it from the instance: the launcher uploads your mod list and the settings you pick and gives you a code (TRS-XXXX-XXXX) and a link – or sends the pack straight to friends. You decide whether the code works for 1, 7 or 30 days or has no expiry. Others install it with “Modpack by code”, and the link page shows what is inside before anything is installed.',
            'Upload a new version and keep the same code: everyone who installed the pack sees “Update”, and files they changed themselves stay as they are. Mod files that are not on Modrinth need a confirmation when sharing and show a warning when installing.',
          ],
          shot: { file: '0.12.0/share-result.png', alt: 'Sharing a Minecraft modpack in TRS Launcher with a code, a link or directly with friends', caption: 'Share a modpack: code, link or straight to friends' },
        },
        {
          id: 'export',
          title: 'Export and back up',
          text: [
            'Export any instance as a .mrpack modpack, back up a world as a ZIP or pick single files – all from the Share tab of the instance. Exported packs leave out the TRS Client itself and its private files.',
          ],
          shot: { file: '0.12.0/pack-update.png', alt: 'Updating a shared Minecraft modpack in TRS Launcher while your own changes stay', caption: 'New version? Update with one click, your own changes stay' },
        },
      ],
      faq: [
        { q: 'Which mod loaders does TRS Launcher support?', a: 'Fabric, Quilt, Forge and NeoForge – plus Vanilla – for every Minecraft release from 1.7.10 to the newest version, wherever the loader exists for that version.' },
        { q: 'Can I install CurseForge modpacks?', a: 'Yes. Install them from Discover or open a downloaded CurseForge .zip; the pack becomes a new instance. Modrinth .mrpack files work the same way.' },
        { q: 'How do I share a modpack with friends?', a: 'Open the instance, choose Share → Share modpack and pick what goes along. You get a TRS code and a link, or you send the pack straight to friends. They install it with Library → “Modpack by code”.' },
        { q: 'Do my own changes survive a modpack update?', a: 'Yes. When the person who shared the pack uploads a new version you see “Update”, and files you changed yourself stay as they are.' },
        { q: 'Can I use shaders?', a: 'Yes. Install shader packs from Modrinth or CurseForge, or choose the “Light shaders” or “Pretty shaders” level of the FPS boost preset, which adds Iris and a shader on Fabric, Quilt and NeoForge. Press K in the game to turn shaders on or off.' },
      ],
      cta: { title: 'Start your next modpack', text: 'Download TRS Launcher for free and install your first modpack in a minute.' },
    },

    'fps-boost-pvp-client': {
      seo: {
        title: 'Minecraft Performance Launcher with FPS Boost & PvP Client | TRS',
        description:
          'TRS Launcher is a free Minecraft performance launcher: FPS boost, PvP HUD with keystrokes and CPS, zoom and minimap – from 1.7.10 and 1.8.9 to the newest.',
      },
      name: 'FPS boost & PvP client',
      teaser: 'The TRS Client: FPS boost, PvP HUD with keystrokes and CPS, zoom, minimap and emotes – from 1.7.10 to the newest version.',
      kicker: 'TRS Client',
      title: 'The free Minecraft performance launcher with FPS boost and PvP client',
      lead: 'The TRS Client is the client mod that comes with TRS Launcher. It adds an FPS boost, a clean PvP HUD, zoom, freelook, a minimap, emotes and capes to Minecraft – from 1.7.10 and 1.8.9 up to the newest release – and it stays fair on servers.',
      sections: [
        {
          id: 'versions',
          title: 'One client for old and new versions',
          text: [
            'The launcher adds the TRS Client to your instances automatically: Forge from 1.7.10, Fabric and Quilt from 1.14.4 and NeoForge, up to the newest version. Whether you play 1.8.9 PvP or the latest release, you get the same menu (Right Shift), the same modules and the same HUD editor. You can switch it off for any instance.',
            'Module packs set the client up for your play style with one click – PvP, Redstone, Comfort or Minimal – and with the TRS services your modules, HUD layouts and keys follow you to every PC.',
          ],
          shot: { file: '0.3.0/hud-editor.png', alt: 'Moving HUD elements in Minecraft with the HUD editor of the TRS Client', caption: 'Place every HUD element where you want it' },
        },
        {
          id: 'fps',
          title: 'A performance launcher with an FPS boost you can measure',
          text: [
            'New instances start with an unlimited frame rate, VSync off and tuned Java settings. On Fabric the TRS Client brings free performance mods such as Lithium, FerriteCore, ImmediatelyFast and ModernFix wherever they exist for your version, and the launcher’s FPS boost preset adds Sodium and friends in three levels: Max FPS, Light shaders and Pretty shaders.',
            'In the game, FPS Boost sets everything to Low, Medium or High with one click and shows the FPS before and after. A performance check finds FPS killers in your video settings and fixes them with one click, Dynamic FPS lowers the frame rate in the background, and Entity Culling skips what you cannot see. Every change can be undone.',
          ],
          points: [
            'Graphics mode “Pretty” or “Max FPS”',
            'The dedicated graphics card on laptops, on Windows and Linux',
            'Other performance mods are detected and keep their part of the job',
          ],
        },
        {
          id: 'pvp',
          title: 'PvP HUD: keystrokes, CPS and counters',
          text: [
            'For PvP the TRS Client shows what matters in a fight: keystrokes, a CPS counter, reach, combo and speed display, an item counter for arrows, totems, potions, golden apples and ender pearls, and hit feedback with a hit marker. Add a custom crosshair, hit colour, 1.7 animations, low fire and a shield position that keeps your view clear.',
            'The chat gets timestamps, stacked repeated messages and mentions, the ping display shows your real ping with jitter, and streamer mode hides names and server addresses.',
          ],
          shot: { file: '0.9.0/pvp-hud.png', alt: 'The PvP HUD of the TRS Client with item counter, hit marker and warnings', caption: 'Counter HUD, hit marker and warnings' },
        },
        {
          id: 'fair-play',
          title: 'Fair play on servers',
          text: [
            'Everything in the TRS Client is display or comfort: no reach or hitbox changes, no auto-clicking, and hit feedback never changes your attacks. The only thing the client sends by itself is Auto-GG, which stays off until you switch it on. The minimap has a Fair Play switch – no cave view, and mobs and players only when you could see them – and servers that ask map mods for fair play are respected automatically.',
            'Some servers ban single features such as freelook. You can switch those off, and freelook turns itself off on servers you list.',
          ],
        },
        {
          id: 'maps',
          title: 'Zoom, freelook, minimap and world map',
          text: [
            'Smooth zoom (V), freelook (Left Alt) and toggle sprint and sneak are built in. The minimap glides smoothly, takes its colours from your resource pack, shows mob heads and puts far-away waypoints on its edge; indoors it looks inside buildings instead of showing the roof. Press M for a fullscreen world map with smooth zoom, a waypoint list, other dimensions and PNG export.',
          ],
          shot: { file: '0.13.0/minimap.png', alt: 'The TRS Client minimap with waypoints on the edge and real mob heads', caption: 'Minimap with waypoints on the edge and real mob heads' },
        },
        {
          id: 'extras',
          title: 'Emotes, capes, screenshots and notes',
          text: [
            'Open the emote wheel and wave, dance or cheer – other TRS players see it. TRS capes swing like cloth with cape physics; many are free, some are animated or in HD. After F2 a small preview lets you edit, copy or send your screenshot, and the screenshot editor crops, draws arrows and text and pixelates names. Every world and server also gets its own notebook with checklists and clickable coordinates.',
          ],
          shot: { file: '0.5.0/emote-wheel.png', alt: 'The emote wheel of the TRS Client in Minecraft', caption: 'The emote wheel' },
          link: { to: '/cosmetics', label: 'See all TRS capes' },
        },
      ],
      faq: [
        { q: 'Is TRS Launcher a performance launcher?', a: 'Yes. TRS Launcher sets up Java and memory for you, starts vanilla instances with the TRS optimization (Fabric, the TRS Client and performance mods under the hood) and removes the frame rate limits of new instances. It works for every version and mod loader, not only for one client version.' },
        { q: 'Is the TRS Client free?', a: 'Yes. The TRS Client comes free with TRS Launcher, which is open source under GPL-3.0.' },
        { q: 'Which Minecraft versions does the TRS Client support?', a: 'Forge from 1.7.10 (including 1.8.9), Fabric and Quilt from 1.14.4 and NeoForge, up to the newest version. A few features need newer versions, for example the circuit templates from 1.8.9.' },
        { q: 'Does the TRS Client really boost FPS?', a: 'It removes common FPS limits such as the default frame cap and VSync, brings performance mods on Fabric and lets you choose Pretty or Max FPS. How much you gain depends on your PC and version – FPS Boost shows the FPS before and after, so you can check.' },
        { q: 'Is the TRS Client allowed on PvP servers?', a: 'It only shows information your game already has and does not change reach, hitboxes or clicks. Features some servers do not allow, such as freelook, can be switched off. Always check the rules of your server.' },
        { q: 'Does it have keystrokes and a CPS counter?', a: 'Yes – keystrokes, CPS, reach, combo, speed, an item counter, ping and more. Move and resize every element in the HUD editor.' },
      ],
      cta: { title: 'More FPS, a cleaner HUD', text: 'Download TRS Launcher for free – the TRS Client is added to your instances automatically.' },
    },
  },
}
