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
    /// Filtername für Umhang-Bilder (PNG, JPEG, WebP, GIF, Studio-JSON).
    Cape,
    /// Filtername für `.mrpack` und CurseForge-`.zip`.
    AnyModpack,
    SavePreset,
    PickPreset,
    /// Filtername für Preset-Dateien.
    PresetFile,
    PickClipsFolder,
    /// Java-Auswahl unter Linux (`…/bin/java`).
    PickJavaUnix,
    /// Dateien in den Spielordner der Instanz kopieren (Tab „Dateien“).
    UploadFiles,
    /// Clip „Speichern unter …“.
    SaveClip,
    /// Filtername für `.mp4`.
    Mp4Video,
}

impl DialogText {
    /// Titel des Java-Dialogs für dieses System.
    pub fn pick_java() -> Self {
        if cfg!(windows) { Self::PickJava } else { Self::PickJavaUnix }
    }
}

/// Je Text eine Zeile in der Reihenfolge von [`Language::ALL`]:
/// en, de, es, fr, pl, pt-BR, tr, nl.
const TEXTS: [[&str; 8]; 23] = [
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
        "Choose images for your cape (PNG, JPEG, WebP, GIF – several = frames)",
        "Bilder für den Umhang wählen (PNG, JPEG, WebP, GIF – mehrere = Frames)",
        "Elige imágenes para tu capa (PNG, JPEG, WebP, GIF – varias = fotogramas)",
        "Choisir des images pour la cape (PNG, JPEG, WebP, GIF – plusieurs = images)",
        "Wybierz obrazy peleryny (PNG, JPEG, WebP, GIF – kilka = klatki)",
        "Escolher imagens para a capa (PNG, JPEG, WebP, GIF – várias = quadros)",
        "Pelerin için resim seç (PNG, JPEG, WebP, GIF – birden çok = kareler)",
        "Afbeeldingen voor de cape kiezen (PNG, JPEG, WebP, GIF – meerdere = frames)",
    ],
    [
        "Cape images",
        "Umhang-Bilder",
        "Imágenes de capa",
        "Images de cape",
        "Obrazy peleryny",
        "Imagens da capa",
        "Pelerin resimleri",
        "Cape-afbeeldingen",
    ],
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
    [
        "Save preset",
        "Preset speichern",
        "Guardar preset",
        "Enregistrer le préréglage",
        "Zapisz preset",
        "Salvar predefinição",
        "Ön ayarı kaydet",
        "Preset opslaan",
    ],
    [
        "Choose a preset file",
        "Preset-Datei wählen",
        "Elige un archivo de preset",
        "Choisir un fichier de préréglage",
        "Wybierz plik presetu",
        "Escolher arquivo de predefinição",
        "Ön ayar dosyası seç",
        "Presetbestand kiezen",
    ],
    [
        "TRS preset",
        "TRS-Preset",
        "Preset de TRS",
        "Préréglage TRS",
        "Preset TRS",
        "Predefinição TRS",
        "TRS ön ayarı",
        "TRS-preset",
    ],
    [
        "Choose a folder for clips",
        "Ordner für Clips wählen",
        "Elige una carpeta para los clips",
        "Choisir un dossier pour les clips",
        "Wybierz folder na klipy",
        "Escolher uma pasta para os clipes",
        "Klipler için klasör seç",
        "Map voor clips kiezen",
    ],
    [
        "Choose the java program (…/bin/java)",
        "Java-Programm wählen (…/bin/java)",
        "Elige el programa java (…/bin/java)",
        "Choisir le programme java (…/bin/java)",
        "Wybierz program java (…/bin/java)",
        "Escolher o programa java (…/bin/java)",
        "java programını seç (…/bin/java)",
        "Het java-programma kiezen (…/bin/java)",
    ],
    [
        "Upload files to the instance",
        "Dateien in die Instanz hochladen",
        "Subir archivos a la instancia",
        "Téléverser des fichiers dans l'instance",
        "Prześlij pliki do instancji",
        "Enviar arquivos para a instância",
        "Örneğe dosya yükle",
        "Bestanden naar de instantie uploaden",
    ],
    [
        "Save clip as",
        "Clip speichern unter",
        "Guardar clip como",
        "Enregistrer le clip sous",
        "Zapisz klip jako",
        "Salvar clipe como",
        "Klibi farklı kaydet",
        "Clip opslaan als",
    ],
    ["MP4 video", "MP4-Video", "Vídeo MP4", "Vidéo MP4", "Wideo MP4", "Vídeo MP4", "MP4 videosu", "MP4-video"],
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

    const ALL: [DialogText; 23] = [
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
        DialogText::SavePreset,
        DialogText::PickPreset,
        DialogText::PresetFile,
        DialogText::PickClipsFolder,
        DialogText::PickJavaUnix,
        DialogText::UploadFiles,
        DialogText::SaveClip,
        DialogText::Mp4Video,
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
        assert_eq!(DialogText::Cape.text(Language::Pl), "Obrazy peleryny");
        assert_eq!(DialogText::Cape.text(Language::PtBr), "Imagens da capa");
        assert_eq!(DialogText::Cape.text(Language::Tr), "Pelerin resimleri");
        assert_eq!(DialogText::Cape.text(Language::Fr), "Images de cape");
    }
}
