package com.tvatlas.core.vod

import com.tvatlas.core.model.stableId

enum class VodKind(val label: String) { MOVIE("电影"), SERIES("剧集"), VARIETY("综艺"), ANIMATION("动漫"), SHORT_DRAMA("短剧"), OTHER("其他") }
enum class VodSort(val label: String) { DEFAULT("最近更新"), YEAR("年份优先"), TITLE("片名") }
data class VodFilter(val kind: VodKind = VodKind.MOVIE, val genre: String = "全部", val region: String = "全部",
    val year: String = "全部", val sort: VodSort = VodSort.YEAR) {
    val primaryGroup: String get() = if (kind == VodKind.MOVIE) genre else region
}
data class VodOffer(val source: String, val movie: CatalogMovie)
data class UnifiedMovie(val id: String, val movie: CatalogMovie, val offers: List<VodOffer>)
object VodQuality {
    fun height(text: String): Int = when {
        Regex("(?i)(2160|4k|超高清)").containsMatchIn(text) -> 2160
        Regex("(?i)(1080|蓝光|藍光|fullhd|fhd)").containsMatchIn(text) -> 1080
        Regex("(?i)(720|高清|hd)").containsMatchIn(text) -> 720
        Regex("(?i)(480|标清|標清|sd)").containsMatchIn(text) -> 480
        else -> 0
    }
}
object UnifiedVod {
    val browseKinds = VodKind.entries.filterNot { it == VodKind.OTHER }
    val movieGroups = listOf("全部", "动作片", "喜剧片", "爱情片", "科幻片", "悬疑片", "恐怖片", "剧情片", "战争片", "动画片", "纪录片", "其他")
    private val shortGenres = listOf("爽文", "逆袭", "甜宠", "虐恋", "古装", "重生", "穿越", "都市", "悬疑", "复仇", "青春", "家庭")
    fun genreKey(value: String): String = if (value in movieGroups) value.removeSuffix("片") else value
    fun genreLabel(kind: VodKind, value: String): String = if (kind == VodKind.MOVIE) movieGroups.firstOrNull { genreKey(it) == genreKey(value) } ?: value else value
    val seriesGroups = listOf("全部", "大陆", "香港", "台湾", "港台", "日本", "韩国", "日韩", "美国", "英国", "泰国", "欧美", "其他")
    fun kind(category: String): VodKind = when {
        category.contains("短剧") || category.contains("短劇") || category.contains("微剧") || category.contains("微劇") ||
            category in setOf("反转爽剧", "反轉爽劇", "女频恋爱", "女頻戀愛", "都市脑洞", "都市腦洞", "脑洞悬疑", "腦洞懸疑", "年代穿越") -> VodKind.SHORT_DRAMA
        category.contains("动画电影") || category.contains("动漫电影") || category == "动画片" -> VodKind.MOVIE
        category.contains("综艺") || category.contains("綜藝") -> VodKind.VARIETY
        category.contains("动漫") || category.contains("动画") || category.contains("動漫") -> VodKind.ANIMATION
        listOf("剧", "剧集", "动漫", "动画", "短剧").any(category::contains) && !category.contains("剧情") -> VodKind.SERIES
        listOf("电影", "片", "剧情").any(category::contains) -> VodKind.MOVIE
        else -> VodKind.OTHER
    }
    fun region(movie: CatalogMovie): String = region(movie.area.ifBlank { movie.category })
    fun region(text: String): String = when {
        listOf("大陆", "大陸", "内地", "國產", "国产").any(text::contains) || text == "中国" -> "大陆"
        text.contains("港台") -> "港台"
        text.contains("日韩") || text.contains("日韓") -> "日韩"
        listOf("香港", "港剧", "港劇", "港澳").any(text::contains) -> "香港"
        listOf("台湾", "台灣", "台剧", "台劇").any(text::contains) -> "台湾"
        listOf("日本", "日剧", "日劇").any(text::contains) -> "日本"
        listOf("韩国", "韓國", "韩剧", "韓劇").any(text::contains) -> "韩国"
        listOf("美国", "美國", "美剧", "美劇").any(text::contains) -> "美国"
        listOf("英国", "英國").any(text::contains) -> "英国"
        listOf("泰国", "泰國", "泰剧", "泰劇").any(text::contains) -> "泰国"
        listOf("欧美", "歐美", "法国", "法國", "德国", "德國", "意大利", "西班牙", "加拿大").any(text::contains) -> "欧美"
        else -> "其他"
    }
    fun regions(movie: CatalogMovie): Set<String> = movie.area.takeIf { it.isNotBlank() }
        ?.split(Regex("[,，/、;；]+"))?.map { region(it.trim()) }?.toSet() ?: setOf(region(movie.category))
    fun genres(movie: CatalogMovie): Set<String> {
        val text = (movie.genres + " " + movie.category).replace("记录", "纪录")
        return (movieGroups.map(::genreKey) + shortGenres + listOf("古装", "犯罪", "都市", "真人秀", "脱口秀", "音乐", "舞蹈", "晚会", "访谈", "旅游", "美食", "冒险", "热血", "奇幻", "搞笑")).filter { it !in listOf("全部", "其他") && text.contains(it) }.toSet().ifEmpty { setOf("其他") }
    }
    fun matches(movie: CatalogMovie, kind: VodKind, group: String): Boolean = kind(movie.category) == kind &&
        (group == "全部" || if (kind == VodKind.SERIES) region(movie) == group else genreKey(group) in genres(movie))
    fun genresFor(kind: VodKind): List<String> = when (kind) {
        VodKind.VARIETY -> listOf("全部", "真人秀", "脱口秀", "音乐", "舞蹈", "晚会", "访谈", "旅游", "美食", "其他")
        VodKind.ANIMATION -> listOf("全部", "冒险", "热血", "奇幻", "搞笑", "科幻", "动画", "其他")
        VodKind.SERIES -> movieGroups.map(::genreKey).filterNot { it == "动画" } + listOf("古装", "犯罪", "都市")
        VodKind.SHORT_DRAMA -> listOf("全部") + shortGenres + "其他"
        else -> movieGroups
    }
    fun matches(movie: CatalogMovie, filter: VodFilter): Boolean {
        val year = Regex("(?:19|20)\\d{2}").find(movie.year)?.value
        return kind(movie.category) == filter.kind &&
            (filter.genre == "全部" || genreKey(filter.genre) in genres(movie)) &&
            (filter.region == "全部" || filter.region in regions(movie) || filter.region == "欧美" && regions(movie).any { it in setOf("美国", "英国", "欧美") }) &&
            when (filter.year) {
                "全部" -> true
                "未知" -> year == null
                "更早" -> year != null && year.toInt() < 2000
                else -> year == filter.year
            }
    }
    fun sorted(items: List<UnifiedMovie>, sort: VodSort): List<UnifiedMovie> = when (sort) {
        VodSort.DEFAULT -> items.sortedByDescending { it.movie.updatedAt }
        VodSort.YEAR -> items.sortedWith(compareByDescending<UnifiedMovie> { Regex("(?:19|20)\\d{2}").find(it.movie.year)?.value?.toIntOrNull() ?: 0 }.thenByDescending { it.movie.updatedAt })
        VodSort.TITLE -> items.sortedBy { it.movie.name }
    }
    fun identity(movie: CatalogMovie): String {
        if (Regex("tt\\d{5,12}").matches(movie.imdbId)) return "${kind(movie.category)}|imdb:${movie.imdbId}"
        if (movie.discoveryOnly && movie.identityNamespace.isNotBlank()) return "${kind(movie.category)}|addon:${movie.identityNamespace}:${movie.id}"
        val title = movie.name.lowercase().replace(Regex("[\\s\\p{P}]+"), "")
        val year = Regex("(?:19|20)\\d{2}").find(movie.year)?.value ?: "unknown"
        val identityAreas = if (movie.area.isBlank()) regions(movie) else movie.area.split(Regex("[,，/、;；]+"))
            .map { value -> region(value.trim()).takeUnless { it == "其他" } ?: value.trim().lowercase() }.toSet()
        return "${kind(movie.category)}|$title|$year|${identityAreas.sorted().joinToString(",")}"
    }
    fun merge(offers: List<VodOffer>): List<UnifiedMovie> = offers.groupBy { identity(it.movie) }.map { (key, values) ->
        val unique = values.distinctBy { it.source to it.movie.id }
        val base = unique.first().movie
        val best = base.copy(id = stableId(key), updatedAt = unique.maxOf { it.movie.updatedAt }, poster = unique.firstNotNullOfOrNull { it.movie.poster },
            summary = unique.maxByOrNull { it.movie.summary.length }?.movie?.summary.orEmpty(),
            episodes = unique.flatMap { it.movie.episodes }.distinctBy { it.url })
        UnifiedMovie(best.id, best, unique)
    }
    fun categoryIds(types: List<CatalogType>, kind: VodKind, group: String): List<String> {
        val matching = types.filter { kind(it.name) == kind }.filter {
            group == "全部" || if (kind != VodKind.MOVIE) region(it.name) == group else genres(CatalogMovie("", "", it.name, emptyList())).contains(genreKey(group))
        }
        // Prefer leaf-like genre/region categories to broad parent labels.
        return matching.filterNot { it.name in listOf("电影", "电影片", "电视剧", "连续剧", "综艺", "综艺片", "动漫", "动漫片") }.ifEmpty { matching }.map { it.id }
    }
}
