package com.tvatlas.core

import com.tvatlas.core.subscription.*
import com.tvatlas.core.model.ProxyType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTest {
    private val sample = """
        proxies:
          - {name: 香港, type: ss, server: node.example.test, port: 443, cipher: aes-128-gcm, password: fixture-secret}
          - {name: 美国, type: vmess, server: other.example.test, port: 443, uuid: 11111111-1111-1111-1111-111111111111, alterId: 0, cipher: auto, tls: true}
          - {name: VLESS, type: vless, server: third.example.test, port: 443, uuid: 22222222-2222-2222-2222-222222222222, tls: true}
          - {name: Trojan, type: trojan, server: fourth.example.test, port: 443, password: fixture-secret, tls: true}
        """.trimIndent()
    @Test fun importsProtocolsChineseNamesAndStableIdentity() {
        val a = ClashSubscription.parse(sample, "subscription")
        assertEquals(4, a.nodes.size)
        assertTrue(a.nodes.all { it.profile.type == ProxyType.MIHOMO })
        assertEquals("香港", a.nodes[0].profile.name)
        assertEquals(a.nodes[0].profile.id, ClashSubscription.parse(sample.replace("node.example.test", "new.example.test"), "subscription").nodes[0].profile.id)
        assertNotEquals(a.nodes[0].profile.id, ClashSubscription.parse(sample, "other").nodes[0].profile.id)
    }
    @Test fun ignoresForeignRulesProvidersAndSystemPorts() {
        val imported = ClashSubscription.parse(sample + "\nmixed-port: 7890\ntun: {enable: true}\nrules: ['MATCH,REJECT']", "s")
        val ports = imported.nodes.associate { it.profile.id to (15000 + imported.nodes.indexOf(it)) }
        val json = Json.parseToJsonElement(MihomoConfig.build(imported.nodes.map { it.config }, ports, "app", "ephemeral")).jsonObject
        assertFalse(json.containsKey("tun")); assertFalse(json.containsKey("mixed-port"))
        assertEquals("MATCH,DIRECT", json.getValue("rules").jsonArray[0].jsonPrimitive.content)
        json.getValue("listeners").jsonArray.forEach {
            assertEquals("127.0.0.1", it.jsonObject.getValue("listen").jsonPrimitive.content)
            assertTrue(it.jsonObject.getValue("proxy").jsonPrimitive.content in ports)
            assertEquals(1, it.jsonObject.getValue("users").jsonArray.size)
        }
    }
    @Test fun duplicateNamesBadPortsAndLocalKeysAreRejected() {
        for (bad in listOf(sample.replace("name: 美国", "name: 香港"), sample.replace("port: 443", "port: 0"), sample.replace("cipher: auto", "private-key: /private/file"))) {
            assertThrows(SubscriptionException::class.java) { ClashSubscription.parse(bad, "s") }
        }
    }
    @Test fun parserErrorsNeverEchoSecrets() {
        val error = assertThrows(SubscriptionException::class.java) { ClashSubscription.parse("proxies: [{password: secret-token, broken", "s") }
        assertFalse(error.message!!.contains("secret-token"))
        assertThrows(SubscriptionException::class.java) { ClashSubscription.parse("!!java.net.URL ['https://example.test']", "s") }
    }
    @Test fun unsupportedEntriesReportedAndProviderOnlyRejected() {
        val result = ClashSubscription.parse(sample + "\n  - {name: ignored, type: unknown}", "s")
        assertEquals(4, result.nodes.size); assertEquals(1, result.skipped)
        assertThrows(SubscriptionException::class.java) { ClashSubscription.parse("proxy-providers: {}", "s") }
    }
    @Test fun listenerPortsCannotCollideOrPointToUnknownNodes() {
        val nodes = ClashSubscription.parse(sample, "s").nodes.map { it.config }
        assertThrows(IllegalArgumentException::class.java) { MihomoConfig.build(nodes, mapOf("missing" to 15000), "a", "b") }
        val ports = nodes.associate { it.getValue("name").jsonPrimitive.content to 15000 }
        assertThrows(IllegalArgumentException::class.java) { MihomoConfig.build(nodes, ports, "a", "b") }
    }
}
