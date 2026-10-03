package dev.theredstonee.trsgame.overlay

// Erzeugt – gleiche Texte wie OverlayStrings.swift (ein Test vergleicht beide).

/** Texte des Editors im Spiel in den 8 Launcher-Sprachen (ohne Android-Ressourcen). */
object OverlayStrings {
    val LANGUAGES = listOf("en", "de", "es", "fr", "pl", "pt-BR", "tr", "nl")

    private val TEXTS: Map<String, List<String>> = mapOf(
        "editor.title" to listOf("Edit controls", "Steuerung bearbeiten", "Editar controles", "Modifier les commandes", "Edytuj sterowanie", "Editar controles", "Kontrolleri düzenle", "Bediening bewerken"),
        "editor.hint" to listOf("Tap a button, drag to move, drag the corner to resize", "Knopf antippen, ziehen verschiebt, Ecke ändert die Größe", "Toca un botón, arrastra para mover, la esquina cambia el tamaño", "Touche un bouton, glisse pour déplacer, le coin change la taille", "Dotknij przycisku, przeciągnij, aby przesunąć, róg zmienia rozmiar", "Toque num botão, arraste para mover, o canto muda o tamanho", "Bir düğmeye dokun, taşımak için sürükle, köşe boyutu değiştirir", "Tik op een knop, sleep om te verplaatsen, de hoek wijzigt de grootte"),
        "editor.add" to listOf("Add", "Neu", "Añadir", "Ajouter", "Dodaj", "Adicionar", "Ekle", "Toevoegen"),
        "editor.action" to listOf("Action", "Aktion", "Acción", "Action", "Akcja", "Ação", "Eylem", "Actie"),
        "editor.shape" to listOf("Shape", "Form", "Forma", "Forme", "Kształt", "Forma", "Şekil", "Vorm"),
        "editor.smaller" to listOf("Smaller", "Kleiner", "Más pequeño", "Plus petit", "Mniejszy", "Menor", "Küçült", "Kleiner"),
        "editor.bigger" to listOf("Bigger", "Größer", "Más grande", "Plus grand", "Większy", "Maior", "Büyüt", "Groter"),
        "editor.fainter" to listOf("Fainter", "Blasser", "Más tenue", "Plus pâle", "Bledszy", "Mais fraco", "Soluk", "Vager"),
        "editor.stronger" to listOf("Stronger", "Kräftiger", "Más intenso", "Plus net", "Wyraźniejszy", "Mais forte", "Belirgin", "Sterker"),
        "editor.grid" to listOf("Grid", "Raster", "Cuadrícula", "Grille", "Siatka", "Grade", "Izgara", "Raster"),
        "editor.remove" to listOf("Remove", "Entfernen", "Quitar", "Retirer", "Usuń", "Remover", "Kaldır", "Verwijderen"),
        "editor.save" to listOf("Save", "Speichern", "Guardar", "Enregistrer", "Zapisz", "Salvar", "Kaydet", "Opslaan"),
        "editor.cancel" to listOf("Cancel", "Abbrechen", "Cancelar", "Annuler", "Anuluj", "Cancelar", "İptal", "Annuleren"),
        "editor.saved" to listOf("Controls saved", "Steuerung gespeichert", "Controles guardados", "Commandes enregistrées", "Zapisano sterowanie", "Controles salvos", "Kontroller kaydedildi", "Bediening opgeslagen"),
        "editor.saveFailed" to listOf("Couldn’t save the controls", "Steuerung konnte nicht gespeichert werden", "No se pudieron guardar los controles", "Impossible d’enregistrer les commandes", "Nie udało się zapisać sterowania", "Não foi possível salvar os controles", "Kontroller kaydedilemedi", "Bediening kon niet worden opgeslagen"),
        "editor.pickAction" to listOf("Choose an action", "Aktion wählen", "Elige una acción", "Choisir une action", "Wybierz akcję", "Escolha uma ação", "Bir eylem seç", "Kies een actie"),
        "editor.otherKey" to listOf("Other key…", "Andere Taste …", "Otra tecla…", "Autre touche…", "Inny klawisz…", "Outra tecla…", "Başka tuş…", "Andere toets…"),
        "editor.pickKey" to listOf("Choose a key", "Taste wählen", "Elige una tecla", "Choisir une touche", "Wybierz klawisz", "Escolha uma tecla", "Bir tuş seç", "Kies een toets"),
        "preset.moveStick" to listOf("Move stick", "Lauf-Stick", "Stick de movimiento", "Stick de déplacement", "Gałka ruchu", "Analógico de movimento", "Hareket çubuğu", "Loopstick"),
        "preset.cameraStick" to listOf("Camera stick", "Kamera-Stick", "Stick de cámara", "Stick caméra", "Gałka kamery", "Analógico de câmera", "Kamera çubuğu", "Camerastick"),
        "preset.jump" to listOf("Jump", "Springen", "Saltar", "Sauter", "Skok", "Pular", "Zıpla", "Springen"),
        "preset.sneak" to listOf("Sneak (hold)", "Schleichen (halten)", "Agacharse (mantener)", "S’accroupir (maintenir)", "Skradanie (przytrzymaj)", "Agachar (segurar)", "Eğil (basılı tut)", "Sluipen (vasthouden)"),
        "preset.sneakToggle" to listOf("Sneak (toggle)", "Schleichen (einrasten)", "Agacharse (fijo)", "S’accroupir (verrouillé)", "Skradanie (przełącznik)", "Agachar (fixo)", "Eğil (sabit)", "Sluipen (vast)"),
        "preset.sprint" to listOf("Sprint (toggle)", "Sprinten (einrasten)", "Correr (fijo)", "Sprint (verrouillé)", "Sprint (przełącznik)", "Correr (fixo)", "Koş (sabit)", "Sprinten (vast)"),
        "preset.attack" to listOf("Attack", "Angreifen", "Atacar", "Attaquer", "Atak", "Atacar", "Saldır", "Aanvallen"),
        "preset.use" to listOf("Use", "Benutzen", "Usar", "Utiliser", "Użyj", "Usar", "Kullan", "Gebruiken"),
        "preset.place" to listOf("Place", "Setzen", "Colocar", "Poser", "Postaw", "Colocar", "Koy", "Plaatsen"),
        "preset.breakBlock" to listOf("Break", "Abbauen", "Romper", "Casser", "Niszcz", "Quebrar", "Kır", "Breken"),
        "preset.pick" to listOf("Pick block", "Block auswählen", "Elegir bloque", "Choisir le bloc", "Wybierz blok", "Escolher bloco", "Blok seç", "Blok kiezen"),
        "preset.sneakPlace" to listOf("Sneak + place", "Schleichend setzen", "Colocar agachado", "Poser accroupi", "Postaw z kucaniem", "Colocar agachado", "Eğilerek koy", "Sluipend plaatsen"),
        "preset.inventory" to listOf("Inventory", "Inventar", "Inventario", "Inventaire", "Ekwipunek", "Inventário", "Envanter", "Inventaris"),
        "preset.drop" to listOf("Drop item", "Gegenstand fallen lassen", "Soltar objeto", "Lâcher l’objet", "Wyrzuć przedmiot", "Soltar item", "Eşyayı at", "Voorwerp laten vallen"),
        "preset.offhand" to listOf("Swap hands", "Hände tauschen", "Cambiar de mano", "Échanger les mains", "Zamień ręce", "Trocar mãos", "El değiştir", "Handen wisselen"),
        "preset.perspective" to listOf("Perspective (F5)", "Perspektive (F5)", "Perspectiva (F5)", "Perspective (F5)", "Perspektywa (F5)", "Perspectiva (F5)", "Bakış açısı (F5)", "Perspectief (F5)"),
        "preset.zoom" to listOf("Zoom", "Zoom", "Zoom", "Zoom", "Przybliżenie", "Zoom", "Yakınlaştır", "Zoom"),
        "preset.debug" to listOf("Debug screen (F3)", "Debug-Bildschirm (F3)", "Pantalla de depuración (F3)", "Écran de débogage (F3)", "Ekran debugowania (F3)", "Tela de depuração (F3)", "Hata ayıklama ekranı (F3)", "Debugscherm (F3)"),
        "preset.chunks" to listOf("Chunk borders (F3+G)", "Chunk-Grenzen (F3+G)", "Bordes de chunk (F3+G)", "Bordures de chunk (F3+G)", "Granice chunków (F3+G)", "Bordas de chunk (F3+G)", "Chunk sınırları (F3+G)", "Chunkgrenzen (F3+G)"),
        "preset.hitboxes" to listOf("Hitboxes (F3+B)", "Hitboxen (F3+B)", "Hitboxes (F3+B)", "Hitbox (F3+B)", "Hitboxy (F3+B)", "Hitboxes (F3+B)", "Vuruş kutuları (F3+B)", "Hitboxen (F3+B)"),
        "preset.signal" to listOf("Redstone overlay (F6)", "Redstone-Overlay (F6)", "Capa de redstone (F6)", "Surcouche redstone (F6)", "Nakładka redstone (F6)", "Sobreposição de redstone (F6)", "Redstone katmanı (F6)", "Redstone-laag (F6)"),
        "preset.signalLegacy" to listOf("Redstone overlay (F8, 1.8)", "Redstone-Overlay (F8, 1.8)", "Capa de redstone (F8, 1.8)", "Surcouche redstone (F8, 1.8)", "Nakładka redstone (F8, 1.8)", "Sobreposição de redstone (F8, 1.8)", "Redstone katmanı (F8, 1.8)", "Redstone-laag (F8, 1.8)"),
        "preset.chat" to listOf("Chat", "Chat", "Chat", "Chat", "Czat", "Chat", "Sohbet", "Chat"),
        "preset.keyboard" to listOf("Keyboard", "Tastatur", "Teclado", "Clavier", "Klawiatura", "Teclado", "Klavye", "Toetsenbord"),
        "preset.menu" to listOf("Pause menu", "Pausenmenü", "Menú de pausa", "Menu pause", "Menu pauzy", "Menu de pausa", "Duraklatma menüsü", "Pauzemenu"),
        "preset.trsMenu" to listOf("TRS menu", "TRS-Menü", "Menú TRS", "Menu TRS", "Menu TRS", "Menu TRS", "TRS menüsü", "TRS-menu"),
        "preset.emotes" to listOf("Emote wheel", "Emote-Rad", "Rueda de emotes", "Roue d’emotes", "Koło emotek", "Roda de emotes", "Emote çarkı", "Emote-wiel"),
        "preset.hotbar" to listOf("Hotbar", "Hotbar", "Barra rápida", "Barre d’action", "Pasek szybkiego dostępu", "Barra rápida", "Hızlı erişim çubuğu", "Hotbar"),
        "preset.prev" to listOf("Previous slot", "Platz zurück", "Casilla anterior", "Emplacement précédent", "Poprzedni slot", "Espaço anterior", "Önceki yuva", "Vorig vak"),
        "preset.next" to listOf("Next slot", "Platz weiter", "Casilla siguiente", "Emplacement suivant", "Następny slot", "Próximo espaço", "Sonraki yuva", "Volgend vak"),
    )

    val KEYS: Set<String> get() = TEXTS.keys

    /** Sprache aus einem Locale-Tag („de-DE“, „pt-BR“, „pt“) – sonst Englisch. */
    fun languageIndex(tag: String): Int {
        val t = tag.replace('_', '-')
        val exact = LANGUAGES.indexOfFirst { it.equals(t, ignoreCase = true) }
        if (exact >= 0) return exact
        val base = t.substringBefore('-').lowercase()
        val byBase = LANGUAGES.indexOfFirst { it.substringBefore('-').lowercase() == base }
        return if (byBase >= 0) byBase else 0
    }

    fun get(key: String, languageTag: String): String {
        val list = TEXTS[key] ?: return key
        return list.getOrNull(languageIndex(languageTag))?.takeIf { it.isNotEmpty() } ?: list[0]
    }
}
