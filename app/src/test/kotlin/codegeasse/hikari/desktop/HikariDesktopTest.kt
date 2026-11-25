package codegeasse.hikari.desktop

import codegeasse.hikari.desktop.cs.Dex
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.nuvio.JsRun
import codegeasse.hikari.desktop.stremio.parseManifest
import codegeasse.hikari.desktop.ui.AppShell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HikariDesktopTest {
    @Test
    fun `sanitize keeps install dirs safe`() {
        assertEquals("My_Ext-1.2", Installer.sanitize("My Ext:1.2"))
        assertEquals("plugin", Installer.sanitize("..."))
        assertTrue(Installer.sanitize("a".repeat(200)).length <= 80)
    }

    @Test
    fun `shaKey is stable hex of fixed length`() {
        val a = Dex.shaKey("cs", "x", "1")
        val b = Dex.shaKey("cs", "x", "1")
        assertEquals(a, b)
        assertEquals(24, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `provider id prefixes map to engines`() {
        assertEquals(EngineKind.CS, AppShell.engineOfProvider("cs|Foo"))
        assertEquals(EngineKind.HIKI, AppShell.engineOfProvider("hiki|bar"))
        assertEquals(EngineKind.NUVIO, AppShell.engineOfProvider("nuvio|baz"))
        assertEquals(EngineKind.STREMIO, AppShell.engineOfProvider("stremio|123"))
        assertNull(AppShell.engineOfProvider("other|x"))
    }

    @Test
    fun `stremio manifest parses catalogs and resources`() {
        val body = """
            {"id":"cc.cinemeta","version":"1.0.0","name":"Cinemeta","description":"x",
             "resources":["catalog","meta","stream"],"types":["movie","series"],
             "idPrefixes":["tt"],
             "catalogs":[{"type":"movie","id":"top","name":"Top","extra":[{"name":"search"}]}]}
        """.trimIndent()
        val m = parseManifest("https://v3-cinemeta.strem.io/manifest.json", body)
        assertNotNull(m)
        assertEquals("cc.cinemeta", m!!.id)
        assertEquals(1, m.catalogs.size)
        assertTrue(m.catalogs[0].searchable)
    }

    @Test
    fun `nuvio results parse tolerates junk`() {
        assertTrue(JsRun.parseResults("not json").isEmpty())
        val parsed = JsRun.parseResults(
            """[{"title":"T","url":"https://e/x.mp4","quality":"1080p"},
                {"title":"NoUrl"},{"url":"","title":"Empty"}]""",
        )
        assertEquals(1, parsed.size)
        assertEquals("https://e/x.mp4", parsed[0].url)
        assertEquals("1080p", parsed[0].quality)
    }
}
