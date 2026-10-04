import type { ContentKind, PresetInput, PresetItem } from '~/types'
import { t } from './i18n'
import type { PresetColorName, PresetIconName } from './schemas'

// Startvorlagen für neue Presets: kuratierte Modrinth-Projekte, versionsunabhängig
// wie jedes Preset (beim Installieren kommt nur, was es für Version + Loader gibt).
// IDs am 2026-10-04 über die Modrinth-API geprüft. Danach frei bearbeitbar.

export const presetTemplateIds = ['performance', 'shader', 'pvp', 'redstone'] as const
export type PresetTemplateId = (typeof presetTemplateIds)[number]

export interface PresetTemplate {
  id: PresetTemplateId
  icon: PresetIconName
  color: PresetColorName
  items: PresetItem[]
}

const CDN = 'https://cdn.modrinth.com/data/'

function mr(projectId: string, title: string, icon: string, kind: ContentKind = 'mod'): PresetItem {
  return { source: 'modrinth', projectId, title, iconUrl: `${CDN}${projectId}/${icon}`, kind }
}

const sodium = mr('AANobbMI', 'Sodium', '295862f4724dc3f78df3447ad6072b2dcd3ef0c9_96.webp')
const entityCulling = mr('NNAgCjsB', 'Entity Culling', '7873452d6cede4daed12da3d7d8c193ab88b4fd6_96.webp')
const immediatelyFast = mr('5ZwdcRci', 'ImmediatelyFast', 'e57b6b451425692ac17ad322d5e14bea686a383a_96.webp')

export const presetTemplates: Record<PresetTemplateId, PresetTemplate> = {
  // Bewährte Client-Optimierungen. ModernFix fehlt bewusst: vor 1.20 hängt es mit Lithium
  // bei der Weltenerstellung (das fertige FPS-Boost-Preset lässt es dort selbst weg).
  performance: {
    id: 'performance',
    icon: 'bolt',
    color: 'amber',
    items: [
      sodium,
      mr('gvQqBUqZ', 'Lithium', 'bcc8686c13af0143adf4285d741256af824f70b7_96.webp'),
      mr('uXXizFIs', 'FerriteCore', '222a126f26f8f9ae1eb339f3b767677f18bff31f_96.webp'),
      entityCulling,
      immediatelyFast,
      mr('51shyZVL', 'More Culling', 'c51b07193b56e952269ef50101d12aecba2b4747_96.webp'),
      mr('LQ3K71Q1', 'Dynamic FPS', '5056368d0d87c1a9f3efead0cb48ab39a4ea87bf_96.webp'),
      mr('fQEb0iXm', 'Krypton', '3ea60899d060a9286e03b87bfa9e71d0cbe2dde7_96.webp'),
    ],
  },
  // Iris + Sodium und eine Auswahl Shaderpakete von sparsam bis schön.
  shader: {
    id: 'shader',
    icon: 'moon',
    color: 'violet',
    items: [
      sodium,
      mr('YL57xq9U', 'Iris Shaders', '18d0e7f076d3d6ed5bedd472b853909aac5da202_96.webp'),
      mr('HVnmMxH1', 'Complementary Shaders - Reimagined', '79cb7c8123bbc54945305b2ebad6b8881efdf5f8_96.webp', 'shaderpack'),
      mr('R6NEzAwj', 'Complementary Shaders - Unbound', 'c85ce4049aac76360d2cd24fd9a7003de01ef312_96.webp', 'shaderpack'),
      mr('Q1vvjJYV', 'BSL Shaders', '2a611a3cb434fb52fb81fa5dace13c5d8b67e55d_96.webp', 'shaderpack'),
      mr('izsIPI7a', 'MakeUp - Ultra Fast', 'a08432baa86b8ffd58c08f4b3a001ef976ff764d_96.webp', 'shaderpack'),
    ],
  },
  // Flüssige FPS, klare Sicht und Hilfen im Kampf. Zoom und HUD bringt der TRS Client selbst mit.
  pvp: {
    id: 'pvp',
    icon: 'sword',
    color: 'redstone',
    items: [
      sodium,
      entityCulling,
      immediatelyFast,
      mr('EsAfCjCV', 'AppleSkin', 'icon.png'),
      mr('MS1ZMyR7', 'Better Ping Display [Fabric]', 'icon.png'),
      mr('Gou1gmGj', 'Low Fire', '010bdbcac9a6c6649757795d32c6794883e6aba3_96.webp'),
      mr('uFGhGxal', 'PvP Crosshair', 'ed273bdaa07cf207bdc5a126f5e478433f64f317.png', 'resourcepack'),
      mr('I1HDemew', 'Spunky PVP Texture Pack', '6c6f9cd115b65efd455b0cb03257ce987a7238f5_96.webp', 'resourcepack'),
    ],
  },
  // Bauen und Testen von Schaltungen: Schaltpläne, Infos, Werkzeuge. MaLiLib kommt automatisch mit.
  redstone: {
    id: 'redstone',
    icon: 'cube',
    color: 'pink',
    items: [
      mr('bEpr0Arc', 'Litematica', '25b5529d7a3b030ac136a6ce879d8ed2a1aa4a8d.png'),
      mr('UMxybHE8', 'MiniHUD', '4adf057a251f694983af139a06839e33bcd7a419.png'),
      mr('t5wuYk45', 'Tweakeroo', '35af76cfb1d3074c5e8575d5f7385bb6c083c9d6.png'),
      mr('TQTTVgYE', 'Carpet', '3ad650635d067b6bfa09403cf5e70e0947a05c07_96.webp'),
      mr('1u6JkXh5', 'WorldEdit', '30698991048ced77e60c4e8284007d3782f2e6a3_96.webp'),
    ],
  },
}

export function isPresetTemplateId(value: unknown): value is PresetTemplateId {
  return typeof value === 'string' && (presetTemplateIds as readonly string[]).includes(value)
}

/** Eingabe für ein neues Preset aus einer Vorlage (Name übersetzt, Einträge kopiert). */
export function templateInput(id: PresetTemplateId): PresetInput {
  const template = presetTemplates[id]
  return {
    name: t(`presets.templates.${id}.name`),
    auto: false,
    items: template.items.map((i) => ({ ...i })),
    icon: template.icon,
    color: template.color,
  }
}
