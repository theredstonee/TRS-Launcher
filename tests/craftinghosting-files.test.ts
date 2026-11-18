import { describe, expect, it } from 'vitest'
import {
  type CraftingInfo,
  type ServerFile,
  MOTD_FOOTER,
  MOTD_MAX,
  addonBaseName,
  addonConfigPath,
  addonKind,
  addonSearchFacets,
  fileIcon,
  joinServerPath,
  matchConfigFolder,
  matchesProperty,
  parentServerPath,
  propertiesPatch,
  propertyField,
  propertyGroup,
  serverAddonsSchema,
  serverCrumbs,
  serverDirSchema,
  serverGameVersion,
  serverPropertiesSchema,
  sortServerFiles,
  validEntryName,
  validPropertyValue,
  validServerPath,
  addonCategoryNames,
  installedVersion,
  looksInstalled,
  newestServerVersion,
  serverVersions,
  versionFitsServer,
  dimensionId,
  versionChangeValid,
  serverWorldsSchema,
  suggestWorldName,
  uniqueWorldName,
  validWorldName,
  worldUploadProgressSchema,
} from '../app/utils/craftinghosting'

// Partner-Server: Dateien, alle Einstellungen (server.properties), Plugins/Mods.

const INFO: CraftingInfo = {
  plan: { name: 'Free 4 GB', memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
  serverTypes: [
    { id: 'paper', name: 'Paper', versions: ['latest', '1.21.11', '26.3', '26.3-rc1', '1.8.9'] },
    { id: 'fabric', name: 'Fabric', versions: ['latest', '1.21.11'] },
    { id: 'forge', name: 'Forge', versions: ['latest'] },
  ],
  limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
  eulaUrl: 'https://www.minecraft.net/eula',
  full: false,
  features: { files: true, properties: true, addons: true, worlds: true, worldDownload: true, ownAddress: true, wakeOnJoin: false, versionChange: false, backups: false },
  notice: null,
}

function entry(name: string, type: 'file' | 'directory' = 'file'): ServerFile {
  return { name, path: `/${name}`, type, size: 1, modifiedAt: null, editable: type === 'file', viewable: type === 'file', protected: false, lock: null }
}

describe('MOTD', () => {
  it('feste zweite Zeile und Länge der ersten', () => {
    expect(MOTD_FOOTER).toBe('Free server by craftinghosting.free × trs-launcher.de')
    expect(MOTD_MAX).toBe(58)
  })
})

describe('Pfade und Dateiliste', () => {
  it('prüft Pfade wie der Kern', () => {
    for (const ok of ['/', '/plugins', '/plugins/', '/plugins/Foo/config.yml', `/${'a'.repeat(511)}`]) expect(validServerPath(ok), ok).toBe(true)
    for (const bad of ['', 'plugins', '/../etc', '/a/../b', '/a/./b', '/a//b', '/a\\b', '/a\nb', '/a /b', `/${'a'.repeat(512)}`]) {
      expect(validServerPath(bad), bad).toBe(false)
    }
  })

  it('Ordnernamen', () => {
    expect(validEntryName('Neu')).toBe(true)
    for (const bad of ['', ' ', '..', '.', 'a/b', 'a\\b', ' a', 'a\u0007', 'x'.repeat(101)]) expect(validEntryName(bad), bad).toBe(false)
  })

  it('verbindet, geht hoch und baut Brotkrumen', () => {
    expect(joinServerPath('/', 'a')).toBe('/a')
    expect(joinServerPath('/plugins/', 'a')).toBe('/plugins/a')
    expect(parentServerPath('/plugins/Foo')).toBe('/plugins')
    expect(parentServerPath('/plugins')).toBe('/')
    expect(parentServerPath('/')).toBe('/')
    expect(serverCrumbs('/plugins/Foo')).toEqual([
      { name: '/', path: '/' },
      { name: 'plugins', path: '/plugins' },
      { name: 'Foo', path: '/plugins/Foo' },
    ])
    expect(serverCrumbs('/')).toEqual([{ name: '/', path: '/' }])
  })

  it('Ordner zuerst, Namen natürlich sortiert', () => {
    const sorted = sortServerFiles([entry('b.txt'), entry('world', 'directory'), entry('a10.txt'), entry('a2.txt'), entry('config', 'directory')])
    expect(sorted.map((e) => e.name)).toEqual(['config', 'world', 'a2.txt', 'a10.txt', 'b.txt'])
  })

  it('Symbole nach Art', () => {
    expect(fileIcon(entry('plugins', 'directory'))).toBe('folder')
    expect(fileIcon(entry('server.properties'))).toBe('config')
    expect(fileIcon(entry('config.yml'))).toBe('config')
    expect(fileIcon(entry('latest.log'))).toBe('log')
    expect(fileIcon(entry('2026-11-18-1.log.gz'))).toBe('log')
    expect(fileIcon(entry('backup.zip'))).toBe('archive')
    expect(fileIcon(entry('LuckPerms.jar'))).toBe('jar')
    expect(fileIcon(entry('level.dat'))).toBe('data')
    expect(fileIcon(entry('server-icon.png'))).toBe('image')
    expect(fileIcon(entry('README'))).toBe('text')
    expect(fileIcon(entry('x.bin'))).toBe('file')
  })

  it('Schemas lehnen kaputte Antworten ab', () => {
    expect(serverDirSchema.safeParse({ dir: 'relativ', writable: true, entries: [] }).success).toBe(false)
    expect(serverDirSchema.safeParse({ dir: '/', writable: true, entries: [{ ...entry('a'), type: 'symlink' }] }).success).toBe(false)
    // Sperrgrund kommt von der API; Unbekanntes wird zur allgemeinen Sperre.
    const locked = serverDirSchema.parse({ dir: '/', writable: false, entries: [{ ...entry('paper.jar'), protected: true, lock: 'quantum' }] })
    expect(locked.entries[0]!.lock).toBe('other')
    expect(locked.writable).toBe(false)
    expect(serverDirSchema.parse({ dir: '/', writable: true, entries: [{ ...entry('eula.txt'), lock: 'system' }] }).entries[0]!.lock).toBe('system')
    expect(serverPropertiesSchema.safeParse({ properties: { 'Bad Key': 'x' }, locked: [] }).success).toBe(false)
    expect(serverPropertiesSchema.safeParse({ properties: { pvp: 'true' }, locked: ['server-port'] }).success).toBe(true)
    expect(serverAddonsSchema.safeParse({ kind: null, dir: '/', items: [] }).success).toBe(true)
    expect(serverAddonsSchema.safeParse({ kind: 'datapacks', dir: '/', items: [] }).success).toBe(false)
  })
})

describe('Alle Server-Einstellungen', () => {
  it('bekannte Schlüssel haben Gruppe und Eingabeart', () => {
    expect(propertyField('pvp')).toEqual({ kind: 'bool' })
    expect(propertyField('max-players')).toEqual({ kind: 'number', min: 1, max: 100 })
    expect(propertyField('difficulty')).toMatchObject({ kind: 'select', options: ['peaceful', 'easy', 'normal', 'hard'] })
    expect(propertyField('gamemode')).toMatchObject({ kind: 'select' })
    expect(propertyField('level-type')).toMatchObject({ kind: 'select' })
    expect(propertyField('white-list')).toEqual({ kind: 'readonly' })
    expect(propertyField('motd')).toEqual({ kind: 'motd' })
    expect(propertyField('level-seed')).toEqual({ kind: 'text' })
    expect(propertyGroup('pvp')).toBe('gameplay')
    expect(propertyGroup('view-distance')).toBe('world')
    expect(propertyGroup('irgendwas')).toBe('other')
  })

  it('unbekannte Schlüssel: Text, mit true/false als Schalter', () => {
    expect(propertyField('bug-report-link', 'https://x')).toEqual({ kind: 'text' })
    expect(propertyField('custom-flag', 'false')).toEqual({ kind: 'bool' })
    expect(propertyField('custom-flag')).toEqual({ kind: 'text' })
  })

  it('prüft Werte', () => {
    expect(validPropertyValue({ kind: 'bool' }, 'true')).toBe(true)
    expect(validPropertyValue({ kind: 'bool' }, 'yes')).toBe(false)
    expect(validPropertyValue(propertyField('max-players'), '20')).toBe(true)
    expect(validPropertyValue(propertyField('max-players'), '0')).toBe(false)
    expect(validPropertyValue(propertyField('max-players'), '1.5')).toBe(false)
    expect(validPropertyValue(propertyField('max-players'), '')).toBe(false)
    expect(validPropertyValue(propertyField('network-compression-threshold'), '-1')).toBe(true)
    expect(validPropertyValue({ kind: 'motd' }, 'x'.repeat(58))).toBe(true)
    expect(validPropertyValue({ kind: 'motd' }, 'x'.repeat(59))).toBe(false)
    expect(validPropertyValue({ kind: 'motd' }, '§cRot')).toBe(false)
    expect(validPropertyValue({ kind: 'text' }, 'a\nb')).toBe(false)
    expect(validPropertyValue({ kind: 'text' }, 'x'.repeat(501))).toBe(false)
    expect(validPropertyValue({ kind: 'text' }, '')).toBe(true)
  })

  it('PATCH enthält nur geänderte, nicht gesperrte Schlüssel', () => {
    const before = { pvp: 'true', motd: 'Hallo', 'server-port': '25568', 'white-list': 'true', 'max-players': '8' }
    const after = { ...before, pvp: 'false', 'server-port': '1', 'white-list': 'false', 'max-players': '8', 'neu-key': 'x' }
    expect(propertiesPatch(before, after, ['server-port'])).toEqual({ pvp: 'false', 'neu-key': 'x' })
    expect(propertiesPatch(before, { ...before })).toEqual({})
    expect(propertiesPatch({}, { 'Bad Key': 'x' })).toEqual({})
  })

  it('Suche über Schlüssel, Beschriftung und Wert', () => {
    expect(matchesProperty('', 'pvp', 'PvP', 'true')).toBe(true)
    expect(matchesProperty('SICHT', 'view-distance', 'Sichtweite (Chunks)', '10')).toBe(true)
    expect(matchesProperty('view', 'view-distance', 'Sichtweite', '10')).toBe(true)
    expect(matchesProperty('25568', 'server-port', 'server-port', '25568')).toBe(true)
    expect(matchesProperty('nether', 'pvp', 'PvP', 'true')).toBe(false)
  })
})

describe('Plugins und Mods', () => {
  it('Art je Servertyp', () => {
    expect(addonKind('paper')).toBe('plugins')
    expect(addonKind('purpur')).toBe('plugins')
    expect(addonKind('fabric')).toBe('mods')
    expect(addonKind('forge')).toBe('mods')
    expect(addonKind('vanilla')).toBeNull()
    expect(addonKind(undefined)).toBeNull()
  })

  it('Minecraft-Version: latest = neueste Release', () => {
    expect(serverGameVersion('paper', '1.21.11', INFO)).toBe('1.21.11')
    expect(serverGameVersion('paper', 'latest', INFO)).toBe('26.3')
    expect(serverGameVersion('fabric', 'latest', INFO)).toBe('1.21.11')
    // Typ ohne Release-Versionen: neueste aller Typen.
    expect(serverGameVersion('forge', 'latest', INFO)).toBe('26.3')
    expect(serverGameVersion('paper', 'latest', null)).toBeNull()
  })

  it('Modrinth-Filter je Servertyp', () => {
    expect(addonSearchFacets('paper', 'latest', INFO)).toEqual({
      kind: 'plugin',
      loaders: ['paper', 'purpur', 'spigot', 'bukkit'],
      gameVersions: ['26.3'],
      serverUsable: true,
    })
    expect(addonSearchFacets('fabric', '1.21.11', INFO)).toEqual({ kind: 'mod', loaders: ['fabric'], gameVersions: ['1.21.11'], serverUsable: true })
    expect(addonSearchFacets('forge', '1.20.1', INFO)).toMatchObject({ kind: 'mod', loaders: ['forge'], gameVersions: ['1.20.1'] })
    expect(addonSearchFacets('vanilla', 'latest', INFO)).toBeNull()
    expect(addonSearchFacets('paper', 'latest', null)?.gameVersions).toEqual([])
  })

  it('Jar-Name ohne Version', () => {
    expect(addonBaseName('LuckPerms-Bukkit-5.4.102.jar')).toBe('LuckPerms-Bukkit')
    expect(addonBaseName('EssentialsX-2.20.1.jar')).toBe('EssentialsX')
    expect(addonBaseName('worldedit-bukkit-7.3.0.jar')).toBe('worldedit-bukkit')
    expect(addonBaseName('Geyser-Spigot.jar')).toBe('Geyser-Spigot')
    expect(addonBaseName('Chunky_v1.4.10.jar')).toBe('Chunky')
    expect(addonBaseName('ViaVersion 5.0.jar')).toBe('ViaVersion')
    expect(addonBaseName('123.jar')).toBe('123')
    expect(addonBaseName('Foo.jar.disabled')).toBe('Foo')
  })

  it('Konfigurationsordner aus dem Jar-Namen', () => {
    const folders = ['LuckPerms', 'WorldEdit', 'Essentials', 'Geyser-Spigot', 'bStats', 'Chunky']
    expect(matchConfigFolder('LuckPerms-Bukkit-5.4.102.jar', folders)).toBe('LuckPerms')
    expect(matchConfigFolder('worldedit-bukkit-7.3.0.jar', folders)).toBe('WorldEdit')
    expect(matchConfigFolder('Geyser-Spigot.jar', folders)).toBe('Geyser-Spigot')
    expect(matchConfigFolder('Chunky-1.4.10.jar', folders)).toBe('Chunky')
    expect(matchConfigFolder('EssentialsX-2.20.1.jar', folders)).toBe('Essentials')
    expect(matchConfigFolder('Unbekannt-1.0.jar', folders)).toBeNull()
    expect(matchConfigFolder('ab-1.0.jar', ['ab'])).toBe('ab')
    expect(matchConfigFolder('ab-1.0.jar', ['abc'])).toBeNull()

    expect(addonConfigPath('plugins', 'LuckPerms-Bukkit-5.4.102.jar', folders)).toBe('/plugins/LuckPerms')
    expect(addonConfigPath('plugins', 'Unbekannt-1.0.jar', folders)).toBe('/plugins')
    expect(addonConfigPath('mods', 'lithium-fabric-0.15.jar', folders)).toBe('/config')
  })
})

describe('Welten', () => {
  it('Namen wie in der API', () => {
    // Nur die Form – reservierte Namen (plugins …) lehnt die API ab.
    for (const ok of ['world', 'Skyblock_2', 'a-b', 'x'.repeat(32), 'plugins']) expect(validWorldName(ok), ok).toBe(true)
    for (const bad of ['', 'a b', '../x', 'grün', 'x'.repeat(33)]) expect(validWorldName(bad), bad).toBe(false)
  })

  it('Vorschlag aus dem Ordnernamen und freie Namen', () => {
    expect(suggestWorldName('Meine Insel!')).toBe('Meine_Insel')
    expect(suggestWorldName('Grüne Wiese')).toBe('Grune_Wiese')
    expect(suggestWorldName('???')).toBe('world')
    expect(suggestWorldName('x'.repeat(40))).toBe('x'.repeat(32))
    expect(uniqueWorldName('world', ['world', 'world-2'])).toBe('world-3')
    expect(uniqueWorldName('neu', ['world'])).toBe('neu')
    expect(uniqueWorldName('x'.repeat(32), ['x'.repeat(32)])).toBe(`${'x'.repeat(30)}-2`)
  })

  it('Dimensionen für die Anzeige', () => {
    expect(dimensionId('overworld')).toBe('overworld')
    expect(dimensionId('minecraft:the_nether')).toBe('nether')
    expect(dimensionId('DIM-1')).toBe('nether')
    expect(dimensionId('the_end')).toBe('end')
    expect(dimensionId('aether')).toBeNull()
  })

  it('Schemas', () => {
    const ok = { active: 'world', exists: false, worlds: [{ name: 'world', active: true, dimensions: ['overworld'] }] }
    expect(serverWorldsSchema.safeParse(ok).success).toBe(true)
    expect(serverWorldsSchema.safeParse({ ...ok, active: '../x' }).success).toBe(false)
    expect(worldUploadProgressSchema.safeParse({ phase: 'download', percent: 40 }).success).toBe(true)
  })
})

describe('Modrinth-Browser für den Server', () => {
  const v = (id: string, game: string[], loaders: string[], type = 'release', fileName = `${id}.jar`) => ({
    id,
    gameVersions: game,
    loaders,
    versionType: type,
    fileName,
  })
  const plugin = { gameVersions: ['1.21.11'], loaders: ['paper', 'purpur', 'spigot', 'bukkit'] }
  const fabric = { gameVersions: ['1.21.11'], loaders: ['fabric'] }

  it('Versionen nach Loader und Minecraft-Version', () => {
    expect(versionFitsServer(v('a', ['1.21.11'], ['spigot']), plugin)).toBe(true)
    expect(versionFitsServer(v('b', ['1.21.11'], ['fabric']), plugin)).toBe(false)
    expect(versionFitsServer(v('c', ['1.20.1'], ['paper']), plugin)).toBe(false)
    expect(versionFitsServer(v('d', ['1.21.11'], ['fabric', 'quilt']), fabric)).toBe(true)
    // Version unbekannt: nur der Loader zählt.
    expect(versionFitsServer(v('e', ['1.8.9'], ['paper']), { gameVersions: [], loaders: ['paper'] })).toBe(true)
  })

  it('Liste, „alle Versionen“ und neueste passende (Release zuerst)', () => {
    const list = [v('beta', ['1.21.11'], ['fabric'], 'beta'), v('rel', ['1.21.11'], ['fabric']), v('old', ['1.20.1'], ['fabric']), v('forge', ['1.21.11'], ['forge'])]
    expect(serverVersions(list, fabric, false).map((x) => x.id)).toEqual(['beta', 'rel'])
    expect(serverVersions(list, fabric, true)).toHaveLength(4)
    expect(newestServerVersion(list, fabric)?.id).toBe('rel')
    expect(newestServerVersion([v('b', ['1.21.11'], ['fabric'], 'beta')], fabric)?.id).toBe('b')
    expect(newestServerVersion(list, { gameVersions: ['26.3'], loaders: ['fabric'] })).toBeNull()
  })

  it('installierte Version über den Dateinamen', () => {
    const list = [v('new', ['1.21.11'], ['paper'], 'release', 'LuckPerms-Bukkit-5.5.0.jar'), v('old', ['1.21.11'], ['paper'], 'release', 'LuckPerms-Bukkit-5.4.102.jar')]
    expect(installedVersion(list, ['luckperms-bukkit-5.4.102.jar', 'other.jar'])).toEqual({ version: list[1], file: 'luckperms-bukkit-5.4.102.jar' })
    expect(installedVersion(list, ['LuckPerms-Bukkit-5.5.0.jar.disabled'])?.version.id).toBe('new')
    expect(installedVersion(list, ['x.jar'])).toBeNull()
  })

  it('Vermutung „installiert“ in der Trefferliste', () => {
    expect(looksInstalled({ slug: 'luckperms', title: 'LuckPerms' }, ['LuckPerms-Bukkit-5.4.102.jar'])).toBe(true)
    expect(looksInstalled({ slug: 'worldedit', title: 'WorldEdit' }, ['worldedit-bukkit-7.3.0.jar'])).toBe(true)
    expect(looksInstalled({ slug: 'essentialsx', title: 'EssentialsX' }, ['LuckPerms-5.4.jar'])).toBe(false)
    expect(looksInstalled({ slug: 'ab', title: 'AB' }, ['ab-1.0.jar'])).toBe(false)
  })

  it('Kategorie-Chips ohne Loader und doppelte', () => {
    const tags = [
      { name: 'economy', projectType: 'mod', header: 'categories' },
      { name: 'economy', projectType: 'plugin', header: 'categories' },
      { name: 'paper', projectType: 'plugin', header: 'categories' },
      { name: 'fabric', projectType: 'mod', header: 'categories' },
      { name: 'adventure', projectType: 'mod', header: 'categories' },
      { name: '16x', projectType: 'resourcepack', header: 'resolutions' },
    ]
    expect(addonCategoryNames(tags, (n) => n)).toEqual(['adventure', 'economy'])
  })
})

describe('Versionswechsel', () => {
  it('nur mit angebotenem Typ/Version und echter Änderung', () => {
    const current = { serverType: 'paper', version: '1.21.11' }
    expect(versionChangeValid({ serverType: 'paper', version: '1.21.11', keepWorld: true }, current, INFO)).toBe(false)
    expect(versionChangeValid({ serverType: 'paper', version: '26.3', keepWorld: true }, current, INFO)).toBe(true)
    expect(versionChangeValid({ serverType: 'fabric', version: '1.21.11', keepWorld: false }, current, INFO)).toBe(true)
    expect(versionChangeValid({ serverType: 'fabric', version: '9.9', keepWorld: true }, current, INFO)).toBe(false)
    expect(versionChangeValid({ serverType: 'nope', version: 'latest', keepWorld: true }, current, INFO)).toBe(false)
    expect(versionChangeValid({ serverType: 'paper', version: '26.3', keepWorld: true }, current, null)).toBe(false)
  })
})
