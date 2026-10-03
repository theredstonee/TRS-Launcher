package dev.theredstonee.trs.game.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CrashInfoTest {
    private class Msg {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Msg) = bytes(field, m.out.toByteArray())
        fun fixed64(field: Int) = apply { varint(((field shl 3) or 1).toLong()); out.write(ByteArray(8)) }
    }

    private fun frame(pc: Long, file: String, fn: String) =
        Msg().int(1, pc).fixed64(2).str(4, fn).int(5, 12).str(6, file)

    @Test
    fun tombstoneShowsSignalAbortAndCrashingThread() {
        val crashing = Msg().int(1, 4242).str(2, "Render thread")
            .msg(4, frame(0x1a2b, "/data/app/x/lib/arm64/libmobileglues.so", "glCreateShader"))
            .msg(4, frame(0x10, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort"))
        val other = Msg().int(1, 7).str(2, "main").msg(4, frame(1, "/system/lib64/libart.so", "x"))
        val tomb = Msg().int(5, 4000).int(6, 4242)
            .msg(10, Msg().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xdead))
            .str(14, "boom\nsecond line")
            .msg(16, Msg().int(1, 7).msg(2, other))
            .msg(16, Msg().int(1, 4242).msg(2, crashing))
        val lines = CrashInfo.Tombstone.lines(tomb.out.toByteArray())
        assertEquals("[TRS] signal 11 (SIGSEGV), SEGV_MAPERR, Adresse 0xdead", lines[0])
        assertEquals("[TRS] Abbruch: boom", lines[1])
        assertEquals("[TRS] Thread 4242 (Render thread):", lines[2])
        assertEquals("[TRS]   #00 pc 1a2b libmobileglues.so (glCreateShader+12)", lines[3])
        assertEquals(5, lines.size)
    }

    @Test
    fun brokenTombstoneThrowsInsteadOfLooping() {
        val bad = byteArrayOf(0x52, 0x7f, 0x01)
        assertTrue(runCatching { CrashInfo.Tombstone.lines(bad) }.isFailure)
    }

    @Test
    fun tailReadsLastLines() {
        val f = File.createTempFile("trs-tail", ".log")
        try {
            f.writeText((1..200).joinToString("\n") { "line $it" } + "\n")
            assertEquals(listOf("line 199", "line 200"), CrashInfo.tail(f, 2))
            assertTrue(CrashInfo.tail(File(f.path + ".missing"), 5).isEmpty())
        } finally {
            f.delete()
        }
    }
}
