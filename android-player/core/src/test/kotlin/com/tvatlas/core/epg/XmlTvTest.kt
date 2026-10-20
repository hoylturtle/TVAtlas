package com.tvatlas.core.epg
import org.junit.Assert.*
import org.junit.Test

class XmlTvTest {
    @Test fun programmeImagesAreScopedValidatedAndOptionalInOldCaches() {
        val guide = XmlTv.parse("""<tv><channel id="x"><icon src="https://example.com/logo.png"/></channel>
            <programme channel="x" start="20261013120000 +0800" stop="20261013123000 +0800"><title>节目一</title><icon src="https://example.com/programme.jpg"/></programme>
            <programme channel="x" start="20261013123000 +0800" stop="20261013130000 +0800"><title>节目二</title></programme>
            <programme channel="x" start="20261013130000 +0800" stop="20261013133000 +0800"><title>节目三</title><icon src="file:///tmp/private.jpg"/></programme>
            <programme channel="x" start="20261013133000 +0800" stop="20261013140000 +0800"><title>节目四</title><icon src="https://user:password@example.com/a.jpg"/></programme></tv>""".toByteArray())
        assertEquals("https://example.com/programme.jpg", guide.programmes[0].imageUrl)
        assertTrue(guide.programmes.drop(1).all { it.imageUrl == null })
        val old = kotlinx.serialization.json.Json.decodeFromString<Programme>("""{"channel":"x","start":1,"end":2,"title":"旧缓存"}""")
        assertNull(old.imageUrl)
        val saved = kotlinx.serialization.json.Json.encodeToString(Programme.serializer(), guide.programmes[0])
        assertEquals(guide.programmes[0], kotlinx.serialization.json.Json.decodeFromString<Programme>(saved))
    }

    @Test fun timezoneMissingEndAndNameMatching() {
        val guide = XmlTv.parse("""<tv><channel id="CCTV1"><display-name>CCTV-1</display-name></channel>
            <programme channel="CCTV1" start="20261013120000 +0800"><title>午间新闻</title></programme>
            <programme channel="CCTV1" start="20261013123000 +0800" stop="20261013130000 +0800"><title>节目二</title></programme></tv>""".toByteArray())
        assertEquals(2, guide.programmes.size)
        assertEquals(guide.programmes[1].start, guide.programmes[0].end)
        assertEquals(XmlTv.time("20261013040000 +0000"), guide.programmes[0].start)
        assertEquals("cctv1", Guide.normalize("CCTV-1 综合"))
        assertEquals("无线新闻台", Guide.normalize("無綫新聞台"))
        assertNull(XmlTv.time("20260230000000 +0800"))
    }
    @Test fun doctypeAndUtf16EntityDeclarationsAreRejected() {
        val xml = """<!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]><tv><programme channel="x" start="20261013120000 +0800"><title>&x;</title></programme></tv>"""
        listOf(xml.toByteArray(), xml.toByteArray(Charsets.UTF_16)).forEach { data ->
            try { XmlTv.parse(data); fail("DTD accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun emptyOrReversedProgrammesAreNotAccepted() {
        try { XmlTv.parse("<tv/>".toByteArray()); fail("empty accepted") } catch (_: IllegalArgumentException) { }
        try { XmlTv.parse("<tv><programme channel='x' start='20261013120000 +0800' stop='20261013110000 +0800'><title>x</title></programme></tv>".toByteArray()); fail("reversed accepted") } catch (_: IllegalArgumentException) { }
    }
}
