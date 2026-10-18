package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodEpisodeChoicesTest {
    @org.junit.Test fun nextEpisodeUsesNumberNotProviderOrderAndNeverSkipsGapsOrSpecials() {
        fun choice(name: String) = VodEpisodeChoice(name, emptyList())
        val choices = listOf(choice("第03集"), choice("花絮"), choice("EP2"), choice("第01集"))
        org.junit.Assert.assertEquals("EP2", VodEpisodeChoices.next(choices, "第01集")!!.name)
        org.junit.Assert.assertEquals("第03集", VodEpisodeChoices.next(choices, "02")!!.name)
        org.junit.Assert.assertNull(VodEpisodeChoices.next(choices, "第03集"))
        org.junit.Assert.assertNull(VodEpisodeChoices.next(listOf(choice("第03集")), "第01集"))
        org.junit.Assert.assertNull(VodEpisodeChoices.next(choices, "正片"))
        org.junit.Assert.assertNull(VodEpisodeChoices.next(choices, "花絮"))
    }
    @Test fun mergesSameEpisodeAcrossLinesWithoutMergingOtherEpisodes() {
        val episodes = listOf(
            CatalogEpisode("第01集", "https://a.test/1.m3u8", "a"),
            CatalogEpisode("第02集", "https://a.test/2.m3u8", "a"),
            CatalogEpisode("EP1", "https://b.test/1.mp4", "b"),
            CatalogEpisode("第1集预告", "https://b.test/trailer.mp4", "b"))
        val choices = VodEpisodeChoices.of(CatalogMovie("film", "影片", "剧集", episodes))
        assertEquals(3, choices.size)
        assertEquals(listOf(episodes[0], episodes[2]), choices[0].candidates)
        assertEquals(listOf(episodes[1]), choices[1].candidates)
        assertEquals(listOf(episodes[3]), choices[2].candidates)
    }
    @Test fun unifiesFeatureQualityLabelsAndRemovesDuplicateUrls() {
        val episodes = listOf(CatalogEpisode("HD", "https://a.test/main.mp4", "a"),
            CatalogEpisode("正片", "https://b.test/main.mp4", "b"), CatalogEpisode("高清", "https://a.test/main.mp4", "c"))
        val choice = VodEpisodeChoices.of(CatalogMovie("film", "影片", "电影", episodes)).single()
        assertEquals("正片", choice.name)
        assertEquals(2, choice.candidates.size)
    }
}
