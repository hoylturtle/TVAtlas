package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodSubscriptionTest {
    @Test fun extractsNativeDataOnlyResolvesRelativeApisAndNeverTreatsSpiderAsApi() {
        val result = VodSubscription.parse("""{"spider":"https://bad.test/execute.jar","sites":[
          {"name":"Native","type":1,"api":"../api.php/provide/vod"},
          {"name":"Spider","type":3,"api":"csp_Guard","ext":"https://example.test/api.php"},
          {"name":"XML","type":0,"api":"https://example.test/xml"},
          {"name":"JS","type":3,"api":"https://example.test/rule.js"}]}""", "https://source.test/config/main.json")
        assertEquals(listOf(SubscribedVodSource("Native", "https://source.test/api.php/provide/vod")), result.sources)
        assertEquals(3, result.unsupported)
        assertTrue("Spider" in result.unsupportedNames)
    }
    @Test fun desktopCmsAliasesImportWithoutReinterpretingSpiderOrExecutingRules() {
        val result = VodSubscription.parse("""{"format":"tvbox-desktop/1","sites":[
          {"name":"CMS","type":"cms","api":"https://a.test/api.php/provide/vod"},
          {"name":"Mixed case","type":" MacCMS ","api":"https://b.test/api.php/provide/vod"},
          {"name":"Numeric native","type":"1","api":"https://c.test/api.php/provide/vod"},
          {"name":"Numeric spider","type":3,"api":"https://bad.test/api.php/provide/vod"},
          {"name":"Rule","type":"html","api":"https://bad.test/rules"},
          {"name":"JSON unknown","type":"json","api":"https://bad.test/api"},
          {"name":"Spider alias","type":"cms","api":"csp_Guard"}
        ],"parses":[{"url":"https://bad.test/execute"}]}""", "https://config.test/main.json")
        assertEquals(listOf("CMS", "Mixed case", "Numeric native"), result.sources.map { it.name })
        assertEquals(4, result.unsupported)
        assertFalse(result.sources.any { it.api.contains("bad.test") })
    }
    @Test fun rejectsCredentialsNonHttpAndLocalNetworkTargets() {
        for (api in listOf("file:///etc/passwd", "https://user:password@host.test/api", "http://127.0.0.1/api", "http://172.20.0.1/api", "http://localhost/api")) {
            val text = """{"sites":[{"name":"fixture","type":1,"api":"$api"}]}"""
            assertTrue(VodSubscription.parse(text, "https://source.test/").sources.isEmpty())
        }
    }
}
