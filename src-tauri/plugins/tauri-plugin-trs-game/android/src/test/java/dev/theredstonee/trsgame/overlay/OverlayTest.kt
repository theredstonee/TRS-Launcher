package dev.theredstonee.trsgame.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Zeichnet alle Eingaben auf. */
class FakeSink(var grabbed: Boolean = true) : InputSink {
    val events = ArrayList<String>()
    var dx = 0f
    var dy = 0f
    var keyboard = false

    override fun sendKey(glfwKey: Int, scancode: Int, down: Boolean, mods: Int) {
        events += "key $glfwKey ${if (down) "down" else "up"}"
    }
    override fun sendChar(codepoint: Int) {
        events += "char $codepoint"
    }
    override fun sendMouseButton(button: Int, down: Boolean) {
        events += "mouse $button ${if (down) "down" else "up"}"
    }
    override fun moveMouseRelative(dx: Float, dy: Float) {
        this.dx += dx
        this.dy += dy
    }
    override fun moveMouseAbsolute(x: Float, y: Float) {
        events += "abs ${x.toInt()} ${y.toInt()}"
    }
    override fun scroll(dx: Float, dy: Float) {
        events += "scroll ${dy.toInt()}"
    }
    override fun isGrabbed(): Boolean = grabbed
    override fun showKeyboard(show: Boolean) {
        keyboard = show
        events += "keyboard $show"
    }

    fun clicksAndKeys() = events.filter { it.startsWith("key") || it.startsWith("mouse") }
}

/** Sucht `src-tauri/crates/core/src/controls` von hier aus nach oben (oder `-Dtrs.controlsDir`). */
fun builtinDir(): File {
    System.getProperty("trs.controlsDir")?.let { return File(it) }
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "crates/core/src/controls")
        if (candidate.isDirectory) return candidate
        val nested = File(dir, "src-tauri/crates/core/src/controls")
        if (nested.isDirectory) return nested
        dir = dir.parentFile
    }
    error("controls dir not found")
}

private const val W = 2000f
private const val H = 1000f

class JsonTest {
    @Test
    fun parsesAndWrites() {
        val v = Json.parse("""{"a":[1,2.5,-3e2],"b":"x\"ä\n","c":true,"d":null,"e":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"ä\n", v["b"])
        assertEquals(true, v["c"])
        assertTrue(v.containsKey("d") && v["d"] == null)
        val back = Json.parse(Json.write(v))
        assertEquals(v, back)
    }

    @Test
    fun rejectsGarbage() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"x", "1 2", "{\"a\":01x}", "[" .repeat(40) + "]".repeat(40))) {
            try {
                Json.parse(bad)
                fail("should fail: $bad")
            } catch (_: Json.ParseException) {
            }
        }
    }
}

class LayoutTest {
    @Test
    fun builtinsParseValidateAndRoundTrip() {
        for (id in listOf("pvp", "build", "redstone")) {
            val l = Layout.parse(File(builtinDir(), "$id.json").readText())
            assertEquals(id, l.id)
            assertEquals(1, l.builtinRev)
            assertTrue(l.buttons.any { it.isJoystick })
            assertTrue(l.buttons.all { b -> b.icon == null || b.icon in Icons.NAMES })
            assertEquals(l, Layout.parse(l.toJson()))
        }
        val red = Layout.parse(File(builtinDir(), "redstone.json").readText())
        assertEquals(Action.Key(71, listOf(292)), red.buttons.first { it.id == "chunks" }.action)
        assertEquals(Action.Mouse(1, listOf(340)), red.buttons.first { it.id == "sneakPlace" }.action)
        assertFalse(red.gestures.tapAttack)
        Layout.validate(Layout.fallback())
    }

    @Test
    fun validationMatchesRust() {
        val good = Layout.parse(File(builtinDir(), "pvp.json").readText())
        fun bad(change: (Layout) -> Layout) {
            try {
                Layout.validate(change(good))
                fail("should be invalid")
            } catch (_: Layout.InvalidLayout) {
            }
        }
        bad { it.copy(version = 2) }
        bad { it.copy(id = "../x") }
        bad { it.copy(name = " ") }
        bad { it.copy(buttons = emptyList()) }
        bad { it.copy(buttons = it.buttons.map { b -> b.copy(x = 0.95f) }) }
        bad { it.copy(buttons = it.buttons + it.buttons[0]) }
        bad { it.copy(buttons = listOf(it.buttons[1].copy(label = null, icon = null))) }
        bad { it.copy(buttons = listOf(it.buttons[1].copy(action = Action.Key(5)))) }
        bad { it.copy(buttons = listOf(it.buttons[1].copy(action = Action.Key(71, listOf(71))))) }
        bad { it.copy(buttons = listOf(it.buttons[1].copy(opacity = 0f))) }
        bad { it.copy(gestures = it.gestures.copy(cameraSensitivity = 9f)) }
        // Unbekannte Felder stören nicht, fehlende Standardwerte werden ergänzt.
        val l = Layout.parse(
            """{"version":1,"id":"x","name":"X","profile":"custom","new":1,"buttons":[{"id":"a","label":"A","x":0,"y":0,"w":0.1,"h":0.1,"action":{"type":"key","key":65}}],
               "gestures":{"tapAttack":true,"holdUse":false,"swipeHotbar":false,"cameraSensitivity":1}}""",
        )
        assertEquals(0.6f, l.buttons[0].opacity)
        assertEquals(Shape.ROUND, l.buttons[0].shape)
        assertTrue(l.gestures.haptics)
    }

    @Test
    fun trsKeysStayDownLongEnoughForTheModsTick() {
        val layout = Layout.parse(
            """{"version":1,"id":"x","name":"X","profile":"custom","buttons":[{"id":"t","icon":"trs","x":0,"y":0,"w":0.1,"h":0.1,"action":{"type":"special","special":"trsMenu"}}],
               "gestures":{"tapAttack":true,"holdUse":false,"swipeHotbar":false,"cameraSensitivity":1}}""",
        )
        val sink = FakeSink()
        val c = OverlayController(sink, layout)
        c.setSize(1000f, 500f)
        c.down(1, 50f, 25f, 1000)
        c.up(1, 50f, 25f, 1010)
        // Kurz angetippt: F13 bleibt gedrückt, bis die Mod sie in einem Tick sieht.
        assertEquals(listOf("key 302 down"), sink.clicksAndKeys())
        assertTrue(c.needsTick())
        c.tick(1060)
        assertEquals(listOf("key 302 down"), sink.clicksAndKeys())
        c.tick(1130)
        assertEquals(listOf("key 302 down", "key 302 up"), sink.clicksAndKeys())
        assertFalse(c.needsTick())
    }

    @Test
    fun iconsAreEightByEight() {
        for ((name, rows) in Icons.BITMAPS) {
            assertEquals(name, 8, rows.size)
            assertTrue(name, rows.all { it.length == 8 && it.all { c -> c == '#' || c == '.' } })
            assertTrue(name, Icons.pixels(name).isNotEmpty())
        }
    }

    @Test
    fun visibilityDefaults() {
        assertEquals(Show.ALWAYS, Button.defaultShow(Action.Fn(Special.MENU)))
        assertEquals(Show.ALWAYS, Button.defaultShow(Action.Key(Glfw.KEY_ESCAPE)))
        assertEquals(Show.GAME, Button.defaultShow(Action.Key(Glfw.KEY_SPACE)))
        val b = Button(id = "a", label = "A", x = 0f, y = 0f, w = 0.1f, h = 0.1f, action = Action.Key(65), show = Show.MENU)
        assertTrue(b.visible(grabbed = false) && !b.visible(grabbed = true))
    }
}

class GeometryTest {
    @Test
    fun fractionsAndPixelsWithInsets() {
        val safe = Geometry.safeRect(W, H, Insets(left = 100f, bottom = 40f))
        assertEquals(Box(100f, 0f, 1900f, 960f), safe)
        val px = Geometry.toPx(Box(0.5f, 0.5f, 0.1f, 0.2f), safe)
        assertEquals(Box(1050f, 480f, 190f, 192f), px)
        val back = Geometry.fromPx(px, safe)
        assertEquals(0.5f, back.x, 1e-5f)
        assertEquals(0.2f, back.h, 1e-5f)
        assertEquals(Box(0f, 0f, MIN_SIZE, MIN_SIZE), Geometry.fromPx(px, Box(0f, 0f, 0f, 0f)))
    }

    @Test
    fun hitRoundVsRect() {
        val safe = Geometry.safeRect(W, H)
        val round = Button(id = "r", label = "R", x = 0.1f, y = 0.1f, w = 0.2f, h = 0.2f, action = Action.Key(65))
        // Kreis: Durchmesser = kürzere Seite (200 px), Mitte (400, 200).
        assertTrue(Geometry.hit(round, safe, 400f, 200f))
        assertFalse(Geometry.hit(round, safe, 210f, 110f))
        assertTrue(Geometry.hit(round.copy(shape = Shape.RECT), safe, 210f, 110f))
    }

    @Test
    fun clampSnapAndInsetsText() {
        assertEquals(Box(0.9f, 0f, 0.1f, 0.1f), Geometry.clamp(Box(1.2f, -0.3f, 0.1f, 0.1f)))
        assertEquals(Box(0f, 0f, MIN_SIZE, 1f), Geometry.clamp(Box(0f, 0f, 0f, 3f)))
        assertEquals(0.13f, Geometry.snap(0.1312f), 1e-6f)
        assertEquals(Insets(10f, 0f, 20f, 5f), Geometry.parseInsets("10, 0,20,5"))
        assertNull(Geometry.parseInsets("1,2,3"))
        assertNull(Geometry.parseInsets("1,2,-3,4"))
        assertEquals("10,0,20,5", Geometry.formatInsets(Insets(10f, 0f, 20f, 5f)))
    }
}

class GestureTest {
    private fun controller(sink: FakeSink, gestures: Gestures = Gestures()): OverlayController {
        // Nur ein Knopf oben links; der Rest ist freie Fläche.
        val l = Layout(
            id = "t", name = "T", profile = "custom",
            buttons = listOf(Button(id = "a", label = "A", x = 0f, y = 0f, w = 0.05f, h = 0.1f, action = Action.Key(65))),
            gestures = gestures,
        )
        return OverlayController(sink, l).apply { setSize(W, H) }
    }

    @Test
    fun tapAttacksOrPlaces() {
        val sink = FakeSink()
        val c = controller(sink)
        c.down(1, 1000f, 500f, 0)
        c.up(1, 1001f, 500f, 100)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())

        val build = FakeSink()
        val c2 = controller(build, Gestures(tapAttack = false, holdUse = false))
        c2.down(1, 1000f, 500f, 0)
        c2.up(1, 1000f, 500f, 50)
        assertEquals(listOf("mouse 1 down", "mouse 1 up"), build.clicksAndKeys())
    }

    @Test
    fun holdUsesOrBreaksUntilRelease() {
        val sink = FakeSink()
        val c = controller(sink, Gestures(holdUse = false))
        c.down(1, 1000f, 500f, 0)
        assertTrue(c.tick(100))
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
        c.tick(GestureTiming.HOLD_MS)
        assertEquals(listOf("mouse 0 down"), sink.clicksAndKeys())
        // Während des Haltens umsehen geht weiter.
        c.move(1, 1100f, 500f)
        assertTrue(sink.dx > 0f)
        c.up(1, 1100f, 500f, 900)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())
    }

    @Test
    fun swipeLooksAroundWithoutClick() {
        val sink = FakeSink()
        val c = controller(sink, Gestures(cameraSensitivity = 2f))
        c.density = 2f
        c.down(1, 1000f, 500f, 0)
        c.move(1, 1100f, 450f)
        c.tick(1000)
        c.up(1, 1100f, 450f, 1200)
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
        // 100 px bei Dichte 2 = 50 dp × 3 × Empfindlichkeit 2.
        assertEquals(300f, sink.dx, 0.01f)
        assertEquals(-150f, sink.dy, 0.01f)
    }

    @Test
    fun menuCursorTapDragAndLongPress() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        // Antippen: Zeiger springt hin, dann Linksklick.
        c.down(1, 500f, 400f, 0)
        c.up(1, 500f, 400f, 80)
        assertEquals(listOf("abs 500 400", "mouse 0 down", "mouse 0 up"), sink.events)
        assertEquals(500f, c.cursor.x)

        // Halten, dann ziehen: linke Taste gedrückt, Zeiger folgt dem Finger.
        sink.events.clear()
        c.down(2, 500f, 400f, 1000)
        c.tick(1000 + GestureTiming.LONG_PRESS_MS)
        assertTrue(c.cursorHeld())
        c.move(2, 600f, 400f, 1500)
        c.up(2, 650f, 400f, 1600)
        assertEquals(listOf("abs 500 400", "mouse 0 down", "abs 600 400", "abs 650 400", "mouse 0 up"), sink.events)

        // Halten ohne Bewegung: Rechtsklick beim Loslassen.
        sink.events.clear()
        c.down(3, 300f, 200f, 2000)
        c.tick(2000 + GestureTiming.LONG_PRESS_MS)
        assertEquals(listOf("abs 300 200"), sink.events)
        c.up(3, 302f, 200f, 2600)
        assertEquals(listOf("abs 300 200", "mouse 1 down", "mouse 1 up"), sink.events)
        assertFalse(c.cursorHeld())
    }

    @Test
    fun twoFingersScrollInMenus() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 500f, 400f, 0)
        c.down(2, 700f, 400f, 10)
        c.move(2, 700f, 400f + GestureTiming.SCROLL_STEP_DP * 2.5f)
        c.up(2, 700f, 460f, 100)
        c.up(1, 500f, 400f, 110)
        assertEquals(2, sink.events.count { it == "scroll 1" })
        // Der erste Finger klickt nach dem Scrollen nicht mehr.
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
    }
}

class ControllerTest {
    private val pvp by lazy { Layout.parse(File(builtinDir(), "pvp.json").readText()) }
    private val redstone by lazy { Layout.parse(File(builtinDir(), "redstone.json").readText()) }

    private fun center(c: OverlayController, id: String): Pair<Float, Float> {
        val b = c.layout.buttons.first { it.id == id }
        val r = Geometry.toPx(b, c.safe)
        return r.cx to r.cy
    }

    @Test
    fun joystickPressesWasdAndSprints() {
        val sink = FakeSink()
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val (cx, cy) = center(c, "move")
        val stick = c.layout.buttons.first { it.id == "move" }
        val radius = Geometry.circleOf(Geometry.toPx(stick, c.safe)).r
        c.down(1, cx, cy, 0)
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
        c.move(1, cx, cy - radius * 0.8f)
        assertEquals(listOf("key 87 down"), sink.clicksAndKeys())
        c.move(1, cx + radius * 0.8f, cy)
        assertEquals(listOf("key 87 down", "key 87 up", "key 68 down"), sink.clicksAndKeys())
        c.move(1, cx, cy - radius * 1.3f)
        assertTrue(sink.events.contains("key ${Glfw.KEY_LEFT_CONTROL} down"))
        c.up(1, cx, cy, 500)
        assertTrue(sink.events.containsAll(listOf("key 87 up", "key ${Glfw.KEY_LEFT_CONTROL} up")))
        assertTrue(c.knobs.isEmpty())
    }

    @Test
    fun toggleLatchesAndChordsWrap() {
        val sink = FakeSink()
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val (sx, sy) = center(c, "sprint")
        c.down(1, sx, sy, 0)
        c.up(1, sx, sy, 50)
        assertEquals(listOf("key 341 down"), sink.clicksAndKeys())
        assertTrue("sprint" in c.latched)
        c.down(1, sx, sy, 100)
        c.up(1, sx, sy, 150)
        assertEquals(listOf("key 341 down", "key 341 up"), sink.clicksAndKeys())

        val rs = FakeSink()
        val r = OverlayController(rs, redstone).apply { setSize(W, H) }
        val (gx, gy) = center(r, "chunks")
        r.down(1, gx, gy, 0)
        r.up(1, gx, gy, 50)
        assertEquals(listOf("key 292 down", "key 71 down", "key 71 up", "key 292 up"), rs.clicksAndKeys())
    }

    @Test
    fun sameKeyFromTwoButtonsIsCounted() {
        val sink = FakeSink()
        val c = OverlayController(sink, redstone).apply { setSize(W, H) }
        val (lx, ly) = center(c, "sneakLock")
        val (dx, dy) = center(c, "down")
        c.down(1, lx, ly, 0)
        c.up(1, lx, ly, 10)
        c.down(2, dx, dy, 20)
        c.up(2, dx, dy, 30)
        // Schleichen bleibt eingerastet, obwohl „runter“ losgelassen wurde.
        assertEquals(listOf("key 340 down"), sink.clicksAndKeys())
        c.down(1, lx, ly, 40)
        assertEquals(listOf("key 340 down", "key 340 up"), sink.clicksAndKeys())
    }

    @Test
    fun hotbarTapAndSwipe() {
        val sink = FakeSink()
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val bar = Geometry.toPx(c.layout.buttons.first { it.id == "hotbar" }, c.safe)
        val y = bar.cy
        c.down(1, bar.x + bar.w * (4.5f / 9f), y, 0)
        assertEquals(listOf("key 53 down", "key 53 up"), sink.clicksAndKeys())
        c.move(1, bar.x + bar.w * (5.5f / 9f), y)
        c.move(1, bar.x + bar.w * (5.6f / 9f), y)
        c.up(1, bar.x + bar.w * (5.6f / 9f), y, 300)
        assertEquals(listOf("key 53 down", "key 53 up", "key 54 down", "key 54 up"), sink.clicksAndKeys())
        assertEquals(8, c.slotAt(c.layout.buttons.first { it.id == "hotbar" }, bar.x + bar.w + 50f))
    }

    @Test
    fun passThroughAimsWhileHolding() {
        val sink = FakeSink()
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val (ax, ay) = center(c, "attack")
        c.down(1, ax, ay, 0)
        c.move(1, ax + 30f, ay)
        c.up(1, ax + 30f, ay, 100)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())
        assertTrue(sink.dx > 0f)
    }

    @Test
    fun menusShowOnlyMenuButtonsAndChatOpensKeyboard() {
        val sink = FakeSink(grabbed = false)
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val visible = c.visibleButtons().map { it.id }.toSet()
        assertEquals(setOf("inventory", "chat", "keyboard", "menu", "trs"), visible)
        // Der (unsichtbare) Angriffsknopf ist im Menü freie Fläche → Zeiger.
        val (ax, ay) = center(c, "attack")
        c.down(1, ax, ay, 0)
        c.up(1, ax, ay, 50)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())

        sink.events.clear()
        sink.grabbed = true
        val (tx, ty) = center(c, "chat")
        c.down(2, tx, ty, 100)
        c.up(2, tx, ty, 150)
        assertEquals(listOf("key 84 down", "key 84 up", "keyboard true"), sink.events)
        assertTrue(c.keyboardShown)
    }

    @Test
    fun cancelReleasesHeldButNotLatched() {
        val sink = FakeSink()
        val c = OverlayController(sink, pvp).apply { setSize(W, H) }
        val (jx, jy) = center(c, "jump")
        c.down(1, jx, jy, 0)
        c.cancelAll()
        assertEquals(listOf("key 32 down", "key 32 up"), sink.clicksAndKeys())
        c.held.releaseAll()
    }
}

class EditorAndStoreTest {
    private val pvp by lazy { Layout.parse(File(builtinDir(), "pvp.json").readText()) }

    @Test
    fun dragSnapsAndClamps() {
        val e = EditorModel(pvp)
        val safe = Geometry.safeRect(W, H)
        val jump = pvp.buttons.first { it.id == "jump" }
        val (x, y) = Geometry.toPx(jump, safe).let { it.cx to it.cy }
        e.begin(x, y, safe, 40f)
        assertEquals("jump", e.selectedId)
        e.dragTo(x + 21.3f, y, safe)
        // 21,3 px = 0,01065 → am Raster (0,01) eingerastet.
        assertEquals(Geometry.snap(jump.x + 21.3f / W), e.selected!!.x, 1e-4f)
        assertEquals(0.9f, e.selected!!.x, 1e-4f)
        e.dragTo(x + 5000f, y, safe)
        assertEquals(1f - jump.w, e.selected!!.x, 1e-4f)
        e.end()
        assertTrue(e.dirty)

        // Griff unten rechts vergrößert.
        val sel = e.selected!!
        val handle = e.handleBox(sel, safe, 40f)
        e.begin(handle.cx, handle.cy, safe, 40f)
        e.dragTo(handle.cx - 400f, handle.cy - 400f, safe)
        assertEquals(MIN_SIZE, e.selected!!.w, 1e-4f)
        e.end()

        // Tippen ins Leere hebt die Auswahl auf.
        e.begin(1000f, 300f, safe, 40f)
        assertNull(e.selectedId)
    }

    @Test
    fun addChangeRemoveAndResult() {
        val e = EditorModel(pvp)
        val id = e.add(Action.Key(86), null, "zoom")
        assertNotNull(id)
        assertEquals(Action.Key(86), e.selected!!.action)
        e.setAction(Action.Key(71, listOf(292)), "F3+G", null)
        assertEquals("F3+G", e.selected!!.label)
        assertNull(e.selected!!.icon)
        e.cycleShape()
        assertEquals(Shape.RECT, e.selected!!.shape)
        e.setOpacity(2f)
        assertEquals(1f, e.selected!!.opacity)
        e.scale(10f)
        assertTrue(e.selected!!.x + e.selected!!.w <= 1f)
        val result = e.result()
        assertNull(result.builtinRev)
        assertEquals(pvp.buttons.size + 1, result.buttons.size)
        e.remove()
        assertEquals(pvp.buttons.size, e.layout.buttons.size)
    }

    @Test
    fun storeSavesAtomicallyAndFallsBack() {
        val dir = Files.createTempDirectory("trs-controls").toFile()
        try {
            val store = LayoutStore(dir)
            assertEquals(Layout.fallback(), store.load("redstone"))
            File(builtinDir(), "pvp.json").copyTo(File(dir, "pvp.json"))
            assertEquals("pvp", store.load("custom-weg").id)
            File(dir, "kaputt.json").writeText("{")
            assertEquals("pvp", store.load("kaputt").id)
            assertEquals("pvp", store.load("../pvp").id)

            val edited = pvp.copy(builtinRev = null, buttons = pvp.buttons.dropLast(1))
            store.save(edited)
            assertEquals(edited, store.read("pvp"))
            assertFalse(File(dir, ".pvp.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stringsCompleteInAllLanguages() {
        for (key in OverlayStrings.KEYS) {
            for (lang in OverlayStrings.LANGUAGES) assertTrue("$key/$lang", OverlayStrings.get(key, lang).isNotBlank())
        }
        assertEquals("Speichern", OverlayStrings.get("editor.save", "de-DE"))
        assertEquals("Salvar", OverlayStrings.get("editor.save", "pt"))
        assertEquals("Save", OverlayStrings.get("editor.save", "ja-JP"))
        for (p in ActionPresets.ALL) assertTrue(p.key, "preset.${p.key}" in OverlayStrings.KEYS)
        for (p in ActionPresets.ALL) assertTrue(p.key, p.icon == null || p.icon in Icons.NAMES)
    }
}

class MenuGestureTest {
    private fun controller(sink: FakeSink): OverlayController {
        val l = Layout(
            id = "t", name = "T", profile = "custom",
            buttons = listOf(Button(id = "a", label = "A", x = 0f, y = 0f, w = 0.05f, h = 0.1f, action = Action.Key(65))),
            gestures = Gestures(),
        )
        return OverlayController(sink, l).apply { setSize(W, H) }
    }

    @Test
    fun cursorStartsCentredAndStaysInView() {
        val cur = MenuCursor()
        cur.setBounds(W, H)
        assertEquals(1000f, cur.x)
        assertEquals(500f, cur.y)
        val sink = FakeSink(grabbed = false)
        cur.moveBy(5000f, -5000f, sink)
        assertEquals(W - 1f, cur.x)
        assertEquals(0f, cur.y)
        // Neue Größe: Position bleibt, nur eingeklemmt.
        cur.setBounds(800f, 600f)
        assertEquals(799f, cur.x)
    }

    @Test
    fun tapTeleportsAndClicks() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 1500f, 700f, 0)
        assertEquals(emptyList<String>(), sink.events)
        c.move(1, 1503f, 702f, 40)
        c.up(1, 1503f, 702f, 120)
        assertEquals(listOf("abs 1500 700", "mouse 0 down", "mouse 0 up"), sink.events)
        assertEquals(1500f to 700f, c.cursor.x to c.cursor.y)
    }

    @Test
    fun slowSwipeMovesOneToOne() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        // Zeiger in der Mitte (1000, 500); Finger ganz woanders.
        c.down(1, 200f, 800f, 0)
        c.move(1, 220f, 800f, 100) // über die Toleranz: 20 px 1:1
        c.move(1, 240f, 790f, 200) // 0,22 dp/ms = langsam → 1:1
        c.up(1, 240f, 790f, 300)
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
        assertEquals(1040f, c.cursor.x, 0.01f)
        assertEquals(490f, c.cursor.y, 0.01f)
        assertEquals("abs 1040 490", sink.events.last())
    }

    @Test
    fun fastSwipeIsAccelerated() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 200f, 800f, 0)
        c.move(1, 210f, 800f, 10)
        // 100 px in 20 ms = 5 dp/ms → volle Verstärkung.
        c.move(1, 310f, 800f, 30)
        c.up(1, 310f, 800f, 40)
        assertEquals(1000f + 10f + 100f * MenuGesture.MAX_GAIN, c.cursor.x, 0.01f)
        assertEquals(emptyList<String>(), sink.clicksAndKeys())
        // Verlauf der Verstärkung.
        assertEquals(1f, MenuGesture.gain(0.1f), 0f)
        assertEquals(MenuGesture.MAX_GAIN, MenuGesture.gain(9f), 0f)
        val mid = MenuGesture.gain((MenuGesture.SLOW_DP_PER_MS + MenuGesture.FAST_DP_PER_MS) / 2)
        assertTrue(mid > 1f && mid < MenuGesture.MAX_GAIN)
    }

    @Test
    fun swipeAfterTapKeepsGoingFromCursor() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 100f, 100f, 0)
        c.up(1, 100f, 100f, 50)
        c.down(2, 900f, 900f, 1000)
        c.move(2, 900f, 870f, 1100)
        c.up(2, 900f, 870f, 1200)
        assertEquals(100f to 70f, c.cursor.x to c.cursor.y)
    }

    @Test
    fun longPressRightClicksAndHoldDragHoldsLeft() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 400f, 300f, 0)
        assertTrue(c.tick(GestureTiming.LONG_PRESS_MS - 1))
        assertFalse(c.tick(GestureTiming.LONG_PRESS_MS))
        // Kleine Zitterbewegung bleibt Rechtsklick.
        c.move(1, 404f, 303f, 600)
        c.up(1, 404f, 303f, 700)
        assertEquals(listOf("mouse 1 down", "mouse 1 up"), sink.clicksAndKeys())

        // Loslassen nach der Haltezeit ohne Tick dazwischen zählt auch als Halten.
        sink.events.clear()
        c.down(2, 400f, 300f, 1000)
        c.up(2, 400f, 300f, 1000 + GestureTiming.LONG_PRESS_MS + 50)
        assertEquals(listOf("mouse 1 down", "mouse 1 up"), sink.clicksAndKeys())

        sink.events.clear()
        c.down(3, 400f, 300f, 2000)
        c.tick(2000 + GestureTiming.LONG_PRESS_MS)
        c.move(3, 450f, 300f, 2500)
        c.move(3, 700f, 500f, 2510)
        assertEquals(listOf("mouse 0 down"), sink.clicksAndKeys())
        // Ziehen folgt dem Finger genau (keine Beschleunigung).
        assertEquals(700f to 500f, c.cursor.x to c.cursor.y)
        c.up(3, 710f, 500f, 2600)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())
        assertEquals("abs 710 500", sink.events[sink.events.size - 2])
    }

    @Test
    fun twoFingersScrollAndCancelDrag() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 400f, 300f, 0)
        c.tick(GestureTiming.LONG_PRESS_MS)
        c.move(1, 500f, 300f, 500)
        assertEquals(listOf("mouse 0 down"), sink.clicksAndKeys())
        // Zweiter Finger: Ziehen endet, Wischen nach oben scrollt runter.
        c.down(2, 800f, 600f, 600)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())
        c.move(1, 600f, 300f, 610)
        c.move(2, 800f, 600f - GestureTiming.SCROLL_STEP_DP * 3.2f, 620)
        assertEquals(3, sink.events.count { it == "scroll -1" })
        c.up(2, 800f, 500f, 700)
        c.up(1, 600f, 300f, 710)
        assertEquals(listOf("mouse 0 down", "mouse 0 up"), sink.clicksAndKeys())
        // Der erste Finger bewegt den Zeiger nach dem Abbruch nicht mehr.
        assertEquals(500f, c.cursor.x)
    }

    @Test
    fun inGameKeepsCameraAndCursorSurvivesGrab() {
        val sink = FakeSink(grabbed = false)
        val c = controller(sink)
        c.down(1, 300f, 300f, 0)
        c.up(1, 300f, 300f, 50)
        sink.grabbed = true
        sink.events.clear()
        c.down(2, 1000f, 500f, 100)
        c.move(2, 1100f, 500f, 150)
        c.up(2, 1100f, 500f, 200)
        assertTrue(sink.dx > 0f)
        assertTrue(sink.events.none { it.startsWith("abs") })
        sink.grabbed = false
        assertEquals(300f to 300f, c.cursor.x to c.cursor.y)
    }
}
