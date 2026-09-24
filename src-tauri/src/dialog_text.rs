//! Titel und Filternamen der nativen Dateidialoge in der Sprache der
//! Oberfläche. Die Dialoge öffnet Rust, deshalb liegen die Texte hier und
//! nicht in `app/locales`.

use trs_core::settings::Language;

use crate::LauncherState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(usize)]
pub enum DialogText {
    SaveModpack,
    PickModpack,
    /// Filtername für `.mrpack`.
    ModrinthModpack,
    PickImportFolder,
    PickInstanceIcon,
    PickInstanceBanner,
    /// Filtername für Bilder.
    Images,
    PickSkin,
    /// Filtername für Skin-PNGs.
    Skins,
    AddContent,
    /// Filtername für `.jar`/`.zip`.
    MinecraftContent,
    PickJava,
    PickCape,
    /// Filtername für Umhang-PNGs.
    Cape,
    /// Filtername für `.mrpack` und CurseForge-`.zip`.
    AnyModpack,
}

/// Je Text eine Zeile in der Reihenfolge von [`Language::ALL`]:
/// en, de, es, fr, pl, pt-BR, tr, nl.
const TEXTS: [[&str; 8]; 15] = [
    [
        "Save modpack",
        "Modpack speichern",
        "Guardar modpack",
        "Enregistrer le modpack",
        "Zapisz modpack",
        "Salvar modpack",
        "Mod paketini kaydet",
        "Modpack opslaan",
    ],
    [
        "Choose a modpack file",
        "Modpack-Datei wählen",
        "Elige un archivo de modpack",
        "Choisir un fichier de modpack",
        "Wybierz plik modpacka",
        "Escolher arquivo de modpack",
        "Mod paketi dosyası seç",
        "Modpackbestand kiezen",
    ],
    [
        "Modrinth modpack",
        "Modrinth-Modpack",
        "Modpack de Modrinth",
        "Modpack Modrinth",
        "Modpack Modrinth",
        "Modpack do Modrinth",
        "Modrinth mod paketi",
        "Modrinth-modpack",
    ],
    [
        "Choose a folder with Minecraft data",
        "Ordner mit Minecraft-Daten wählen",
        "Elige una carpeta con datos de Minecraft",
        "Choisir un dossier contenant des données Minecraft",
        "Wybierz folder z danymi Minecrafta",
        "Escolher uma pasta com dados do Minecraft",
        "Minecraft verilerini içeren klasörü seç",
        "Map met Minecraft-gegevens kiezen",
    ],
    [
        "Choose an image for the instance",
        "Bild für die Instanz wählen",
        "Elige una imagen para la instancia",
        "Choisir une image pour l'instance",
        "Wybierz obraz dla instancji",
        "Escolher uma imagem para a instância",
        "Örnek için bir resim seç",
        "Afbeelding voor de instantie kiezen",
    ],
    [
        "Choose a banner for the instance",
        "Banner für die Instanz wählen",
        "Elige un banner para la instancia",
        "Choisir une bannière pour l'instance",
        "Wybierz baner dla instancji",
        "Escolher um banner para a instância",
        "Örnek için bir afiş seç",
        "Banner voor de instantie kiezen",
    ],
    ["Images", "Bilder", "Imágenes", "Images", "Obrazy", "Imagens", "Resimler", "Afbeeldingen"],
    [
        "Choose a skin file (PNG, 64×64)",
        "Skin-Datei wählen (PNG, 64×64)",
        "Elige un archivo de skin (PNG, 64×64)",
        "Choisir un fichier de skin (PNG, 64×64)",
        "Wybierz plik skina (PNG, 64×64)",
        "Escolher arquivo de skin (PNG, 64×64)",
        "Skin dosyası seç (PNG, 64×64)",
        "Skinbestand kiezen (PNG, 64×64)",
    ],
    ["Skins", "Skins", "Skins", "Skins", "Skiny", "Skins", "Skinler", "Skins"],
    [
        "Add mods, resource packs or shaders",
        "Mods, Ressourcenpakete oder Shader hinzufügen",
        "Añadir mods, paquetes de recursos o shaders",
        "Ajouter des mods, packs de ressources ou shaders",
        "Dodaj mody, paczki zasobów lub shadery",
        "Adicionar mods, pacotes de recursos ou shaders",
        "Mod, kaynak paketi veya gölgelendirici ekle",
        "Mods, resourcepacks of shaders toevoegen",
    ],
    [
        "Minecraft content",
        "Minecraft-Inhalte",
        "Contenido de Minecraft",
        "Contenu Minecraft",
        "Zawartość Minecrafta",
        "Conteúdo do Minecraft",
        "Minecraft içeriği",
        "Minecraft-inhoud",
    ],
    [
        "Choose java.exe or javaw.exe",
        "java.exe oder javaw.exe wählen",
        "Elige java.exe o javaw.exe",
        "Choisir java.exe ou javaw.exe",
        "Wybierz java.exe lub javaw.exe",
        "Escolher java.exe ou javaw.exe",
        "java.exe veya javaw.exe seç",
        "java.exe of javaw.exe kiezen",
    ],
    [
        "Choose a cape image (PNG, 64×32 or 22×17)",
        "Umhang-Bild wählen (PNG, 64×32 oder 22×17)",
        "Elige una imagen de capa (PNG, 64×32 o 22×17)",
        "Choisir une image de cape (PNG, 64×32 ou 22×17)",
        "Wybierz obraz peleryny (PNG, 64×32 lub 22×17)",
        "Escolher imagem da capa (PNG, 64×32 ou 22×17)",
        "Pelerin resmi seç (PNG, 64×32 veya 22×17)",
        "Cape-afbeelding kiezen (PNG, 64×32 of 22×17)",
    ],
    ["Cape", "Umhang", "Capa", "Cape", "Peleryna", "Capa", "Pelerin", "Cape"],
    [
        "Modpack (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
        "Mod paketi (Modrinth, CurseForge)",
        "Modpack (Modrinth, CurseForge)",
    ],
];

impl DialogText {
    /// Text in der gewünschten Sprache.
    pub fn text(self, language: Language) -> &'static str {
        let column = Language::ALL.iter().position(|l| *l == language).unwrap_or(0);
        TEXTS[self as usize][column]
    }
}

/// Sprache der Oberfläche aus den Einstellungen – vor jedem Dialog lesen,
/// damit ein Sprachwechsel sofort greift.
pub async fn language(launcher: &LauncherState) -> Language {
    launcher.settings().await.ui.language
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: [DialogText; 15] = [
        DialogText::SaveModpack,
        DialogText::PickModpack,
        DialogText::ModrinthModpack,
        DialogText::PickImportFolder,
        DialogText::PickInstanceIcon,
        DialogText::PickInstanceBanner,
        DialogText::Images,
        DialogText::PickSkin,
        DialogText::Skins,
        DialogText::AddContent,
        DialogText::MinecraftContent,
        DialogText::PickJava,
        DialogText::PickCape,
        DialogText::Cape,
        DialogText::AnyModpack,
    ];

    #[test]
    fn every_text_exists_in_every_language() {
        assert_eq!(ALL.len(), TEXTS.len());
        assert_eq!(Language::ALL.len(), TEXTS[0].len());
        for (i, text) in ALL.iter().enumerate() {
            assert_eq!(*text as usize, i, "Reihenfolge von ALL passt zu TEXTS");
            for language in Language::ALL {
                let s = text.text(language);
                assert!(!s.trim().is_empty(), "{text:?} fehlt für {}", language.code());
                assert!(!s.chars().any(char::is_control));
            }
        }
    }

    #[test]
    fn columns_follow_language_order() {
        assert_eq!(DialogText::SaveModpack.text(Language::En), "Save modpack");
        assert_eq!(DialogText::SaveModpack.text(Language::De), "Modpack speichern");
        assert_eq!(DialogText::Images.text(Language::Es), "Imágenes");
        assert_eq!(DialogText::Images.text(Language::Nl), "Afbeeldingen");
        assert_eq!(DialogText::Cape.text(Language::Pl), "Peleryna");
        assert_eq!(DialogText::Cape.text(Language::PtBr), "Capa");
        assert_eq!(DialogText::Cape.text(Language::Tr), "Pelerin");
        assert_eq!(DialogText::Cape.text(Language::Fr), "Cape");
    }
}
