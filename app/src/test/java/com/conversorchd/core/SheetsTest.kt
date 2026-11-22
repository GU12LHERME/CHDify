package com.conversorchd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetsTest {

    private val cue = """
        FILE "Jogo (Track 01).bin" BINARY
          TRACK 01 MODE2/2352
            INDEX 01 00:00:00
        FILE "Jogo (Track 02).bin" BINARY
          TRACK 02 AUDIO
            INDEX 00 00:00:00
            INDEX 01 00:02:00
        file musica.wav WAVE
          TRACK 03 AUDIO
            INDEX 01 00:00:00
    """.trimIndent()

    @Test
    fun cueReferencedFiles() {
        assertEquals(listOf("Jogo (Track 01).bin", "Jogo (Track 02).bin", "musica.wav"), Sheets.cueFiles(cue))
    }

    @Test
    fun cueRewriteUsesAliasesAndKeepsTracks() {
        val aliases = Sheets.aliasesFor(Sheets.cueFiles(cue))
        assertEquals("t01.bin", aliases["Jogo (Track 01).bin"])
        assertEquals("t03.wav", aliases["musica.wav"])
        val out = Sheets.rewriteCue(cue, aliases)
        assertTrue(out.contains("FILE \"t01.bin\" BINARY"))
        assertTrue(out.contains("file \"t03.wav\" WAVE"))
        assertTrue(out.contains("INDEX 01 00:02:00"))
        assertEquals(listOf("t01.bin", "t02.bin", "t03.wav"), Sheets.cueFiles(out))
    }

    @Test
    fun gdiParseAndRewrite() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n"
        val files = Sheets.gdiFiles(gdi)
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), files)
        val out = Sheets.rewriteGdi(gdi, Sheets.aliasesFor(files))
        assertTrue(out.contains("2 756 0 2352 t02.raw 0"))
        assertTrue(out.startsWith("3\r\n"))
    }

    @Test
    fun latin1CueIsDecoded() {
        val bytes = "FILE \"Ação.bin\" BINARY\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("Ação.bin"), Sheets.cueFiles(Sheets.decode(bytes)))
    }

    @Test
    fun leafNameStripsDirectories() {
        assertEquals("a.bin", Sheets.leafName("dir\\sub/a.bin"))
    }

    @Test
    fun formatting() {
        assertEquals("00:02:00", Format.msf(150))
        assertEquals("1:01:01", Format.duration(3661))
        assertEquals("4,00 GB", Format.bytes(4L * 1024 * 1024 * 1024))
        assertEquals("50,0%", Format.percent(0.5))
    }
}
