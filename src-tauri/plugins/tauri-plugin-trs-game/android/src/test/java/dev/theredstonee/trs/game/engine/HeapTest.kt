package dev.theredstonee.trs.game.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class HeapTest {
    @Test
    fun heapFollowsDeviceMemory() {
        // Genug frei: Wunsch gilt.
        assertEquals(4096, JvmLauncher.capHeap(4096, 16_000, 9_000))
        // Wenig frei (Handy mit vielen Apps): drei Viertel des freien Speichers.
        assertEquals(3000, JvmLauncher.capHeap(4096, 12_000, 4_000))
        // Kleines Gerät: höchstens die Hälfte des RAMs.
        assertEquals(2000, JvmLauncher.capHeap(4096, 4_000, 3_500))
        // Nie unter 1 GB.
        assertEquals(1024, JvmLauncher.capHeap(4096, 3_000, 800))
    }
}
