# TRS Client – FPS-Benchmark (Stand 2026-09-25)

Alle Zahlen: Windows 11, derselbe PC, Fabric-Entwicklungsstart (`runClient`), stumm, Fenster 1920×1009 auf
Bildschirm 2, VSync aus und Bildraten-Grenze „Unbegrenzt“ (sonst misst man nur die Grenze). Ø = Durchschnitt,
1 %/0,1 %-Low = Bildrate des langsamsten 1 %/0,1 % der Bilder.

**Wichtig zur Genauigkeit:** Läufe derselben Einstellung schwanken auf diesem Rechner um **±15–25 %** (andere
Programme/Agenten, Takt, Treiber). Einzelne Unterschiede unter ~20 % sind deshalb kein Beleg. Der Profiler
(JFR) zeigt die Kosten des TRS Clients genauer als der FPS-Vergleich.

## 1. Ursache „~100 FPS mit TRS“ (Testszene, 854×480, Fabric 1.21.1)

| Einstellung | Ø FPS | 1 %-Low |
|---|---:|---:|
| Vanilla-Standard einer frischen Instanz (VSync an, Max. Bildrate 120) | 91–100 | 53–96 |
| Neue TRS-Standards (VSync aus, unbegrenzt) | 476–511 | 92–125 |
| + Mods des Presets „Max FPS“ | 780 | 39 |

→ Die ~100 FPS kamen von den Vanilla-Standards neuer Instanzen, nicht vom TRS Client. Behoben: neue Instanzen
bekommen `maxFps:260` + `enableVsync:false`, der Leistungs-Check meldet eine Grenze unter „Unbegrenzt“.

## 2. Realistische Szene (Dorf, fester Seed 20260925, Sichtweite 12, sonst Minecraft-Standards)

Ablauf: `-PtrsAutotestOnly=realbench` (Kreativ-Flug auf festem, geländefolgendem Rundkurs über ein Dorf mit
Wald und Wasser; jede Position folgt nur aus der Tick-Nummer; Abweichung Soll/Ist wird geprüft – alle Läufe unten
„0,00 Blöcke, gültig“). 60-s-Läufe (Stand vor der Verkürzung auf 20 s).

### Minecraft 1.21.11 (Fabric)

| Variante | Ø FPS | 1 %-Low | 0,1 %-Low |
|---|---:|---:|---:|
| Vanilla (TRS Client aus, nur Messhaken) – Lauf A | 954 | 284 | 119 |
| Vanilla – Lauf B (anderer Zeitpunkt) | 739 | 315 | 189 |
| TRS Client (Standard-Module) – Lauf A | 740 | 228 | 117 |
| TRS Client – Lauf B (JFR an) | 766 | 78 | 34 |
| TRS Client, alle Module aus | 735 | 306 | 169 |
| TRS Client, HUD-Module aus | 698 | 198 | 78 |
| TRS Client, nur FPS-Anzeige an | 795 | 353 | 202 |
| TRS Client, keine Mixins + kein HUD | 793 | 279 | 145 |
| TRS Client + eingebaute Optimierungen (Jar-in-Jar) | 826 | 382 | 208 |
| Vanilla + Preset-Mods „Max FPS“ (Sodium & Co.) | 1172 | 512 | 323 |
| TRS Client + Preset-Mods | 1110 | 493 | 283 |
| TRS Client + Preset-Mods (JFR an) | 1061 | 312 | 137 |

### Profiler (JFR, Render-Thread, TRS Client allein, 1.21.11)

| Bereich | Anteil |
|---|---:|
| GPU-Treiber / Bild tauschen (wartet auf die Grafikkarte) | 79,1 % |
| sonstiges Rendern (Welt, Nebel, Puffer) | 7,2 % |
| HUD / Oberfläche (Vanilla + TRS) | 3,1 % |
| Chunk-Bau/Upload im Render-Thread | 2,1 % |
| Himmel/Wolken | 1,8 % |
| **TRS Client gesamt** | **0,8 %** (davon HUD 0,73 %) |

Allokationen im Render-Thread durch TRS: ~550 MB je Minute, davon ~920 MB-Stichprobengewicht aus
`GuiGraphics.drawString(String)` → `Language.getVisualOrder` (ab 1.21.6 rechnet Vanilla für jeden HUD-Text in
jedem Bild die Bidi-Reihenfolge neu). **Behoben:** `Gfx.text` merkt sich die Reihenfolge je Text (≥ 1.21.6,
Fabric/Forge/NeoForge).

In der Testszene (200 Wesen, Truhen, Namensschilder) mit Preset-Mods: TRS gesamt 3,4 % des Render-Threads
(HUD), Wesen 23,7 %, Block-Entities 10,9 %.

### Befund „Bisect“

- TRS mit allen Modulen aus ≈ TRS Standard ≈ Vanilla (735 / 740 / 739 in denselben Stunden) – der TRS Client
  kostet in der realistischen Szene innerhalb der Messgenauigkeit **nichts Messbares**; laut Profiler ~1 %.
- Der früher gesehene Abstand „Preset-Mods ohne TRS-Module 1145 vs. mit 780“ (Testszene) ließ sich nicht
  wiederholen: dieselbe Messung ergab jetzt 1139 (Module aus) vs. **1322** (Module an). Der 780er-Lauf hatte
  einen 5,4-s-Hänger (1 %-Low 39) – ein Ausreißer, keine Modul-Kosten.
- Größter echter Hebel bleibt: Grenze/VSync (×5) und Sodium & Co. (+40–50 %).

## 3. Welche Mods bündelt OneClient (Polyfrost)?

Aus dem echten Quelltext (OneLauncher, GPL-3; Paketlisten in Polyfrost/DataStorageV2): Performance-Paket für
Fabric 1.21.1–26.3 u. a. Sodium, Sodium Extra, Lithium, ImmediatelyFast, EntityCulling, FerriteCore, ModernFix,
C2ME, Iris, BadOptimizations, Dynamic FPS, ScalableLux, VMP … – als Modrinth-Downloads (.mrpack), **ohne
vorab gesetzte Mod-Configs oder options.txt** (Pakete enthalten nur `modrinth.index.json`). Ein Schlüssel
`"chunk_renderer": "NEO"` existiert weder dort noch in Sodiums Config-Schema.

## Reproduzieren

```
./gradlew :fabric:1.21.11:runClient -PtrsAutotest -PtrsAutotestOnly=realbench -PtrsBenchOptions=real \
  -PtrsBenchCenter=-192,-224 [-PtrsBenchVanilla] [-PtrsBenchMods=<ordner>] [-PtrsBenchJfr=<datei.jfr>] \
  [-PtrsBenchSeconds=20] [-PtrsBenchOff=all|hud|<modul,…>] [-PtrsNoBundledMods]
./gradlew :1.8.9:runClient -PtrsAutotest -PtrsAutotestOnly=realbench -PtrsBenchOptions=real   (im Ordner legacy)
```
Dorf-Mitte je Version: 1.21.11/1.20.1 `-192,-224`, 1.16.5 `64,864` (`-PtrsBenchCenter=locate` sucht sie).
