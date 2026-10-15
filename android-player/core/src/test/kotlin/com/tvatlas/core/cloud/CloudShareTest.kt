package com.tvatlas.core.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudShareTest {
    @Test fun pastedMessagesAndProviderCodes() {
        val q = CloudShareParser.parse("给你分享了「示例视频」 链接：https://pan.quark.cn/s/abcdef123456?pwd=aB12 提取码：aB12")
        assertEquals(CloudProvider.QUARK, q.provider); assertEquals("aB12", q.code); assertEquals("示例视频", q.name)
        assertEquals(CloudProvider.BAIDU, CloudShareParser.parse("https://pan.baidu.com/s/1ExampleShare_123?pwd=cd34").provider)
        val s = CloudShareParser.parse("https://115cdn.com/s/sExampleShare?password=ef56&#\n访问码：ef56")
        assertEquals(CloudProvider.PAN115, s.provider); assertEquals("ef56", s.code)
        assertFalse(s.toString().contains("ef56")); assertFalse(s.toString().contains(s.shareId))
    }
    @Test fun explicitCodeRepairsConflictingPaste() {
        assertThrows(IllegalArgumentException::class.java) { CloudShareParser.parse("https://pan.quark.cn/s/abcdef123456?pwd=ab12 提取码：cd34") }
        assertEquals("zz99", CloudShareParser.parse("https://pan.quark.cn/s/abcdef123456?pwd=ab12 提取码：cd34", "zz99").code)
    }
    @Test fun rejectLookalikesCredentialsAndMultipleShares() {
        for (s in listOf("https://pan.quark.cn.attacker.test/s/abcdef", "https://pan.quark.cn@attacker.test/s/abcdef", "http://pan.baidu.com/s/1abcdef", "https://pan.quark.cn:8443/s/abcdef", "https://pan.quark.cn/s/abcde https://115.com/s/abcdef"))
            assertThrows(IllegalArgumentException::class.java) { CloudShareParser.parse(s) }
    }
    @Test fun duplicatePasteIsOneShareAndMediaIsNotAFolder() {
        assertEquals("abcde", CloudShareParser.parse("https://115.com/s/abcde\nhttps://115.com/s/abcde").shareId)
        assertTrue(CloudFile("f", "EP01.MKV", false).video)
        assertFalse(CloudFile("f", "videos.mp4", true).video)
    }
    @Test fun episodesAreSortedNaturallyAndFoldersStayFirst() {
        val files = listOf(CloudFile("10", "E10.mp4", false), CloudFile("2", "E2.mp4", false), CloudFile("1", "E01.mp4", false), CloudFile("d", "影片", true))
        assertEquals(listOf("d", "1", "2", "10"), files.episodeOrder().map { it.id })
    }
}
