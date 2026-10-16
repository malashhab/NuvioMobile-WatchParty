package com.nuvio.app.features.player

/**
 * Picks the subtitle made for the release being played. Subtitle sites name their files after
 * the release ("Movie.2023.1080p.WEB-DL.DDP5.1-FLUX.srt"), so a subtitle that shares the stream's
 * release group, source and service is far more likely to be timed right. Subtitles that say
 * nothing about their release keep their original order, behind the ones that match.
 */
internal object SubtitleReleaseMatching {
    private val sources = mapOf(
        "remux" to "remux",
        "bluray" to "bluray", "blu" to "bluray", "bdrip" to "bluray", "brrip" to "bluray", "bdremux" to "remux",
        "webdl" to "webdl", "web" to "web",
        "webrip" to "webrip",
        "hdtv" to "hdtv", "pdtv" to "hdtv",
        "dvdrip" to "dvd", "dvd" to "dvd",
        "hdrip" to "hdrip",
    )
    private val services = setOf(
        "amzn", "nf", "dsnp", "hmax", "atvp", "pcok", "pmtp", "hulu", "itunes", "stan", "crav",
    )
    private val resolutions = setOf("2160p", "1080p", "720p", "576p", "480p", "4k", "uhd")

    // Words a release group is never called, so "...-English.srt" isn't taken as a group.
    private val notGroups = setOf(
        "eng", "english", "sdh", "forced", "hi", "cc", "srt", "ass", "vtt", "sub", "subs", "full",
        "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ddp", "dd", "atmos", "dts",
    )

    private class Release(val tokens: Set<String>, val group: String?, val source: String?, val service: String?, val resolution: String?)

    /** [items] with the best release match first; ties keep their order. */
    fun <T> rank(items: List<T>, releaseText: String?, textOf: (T) -> String): List<T> {
        val release = parse(releaseText ?: return items) ?: return items
        if (items.size < 2) return items
        return items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<T>> { score(parse(textOf(it.value)), release) }.thenBy { it.index })
            .map { it.value }
    }

    /** The first of [items] passing [filter], preferring the best release match. */
    fun <T> best(items: List<T>, releaseText: String?, textOf: (T) -> String, filter: (T) -> Boolean): T? =
        rank(items.filter(filter), releaseText, textOf).firstOrNull()

    internal fun score(subtitleText: String, releaseText: String): Int {
        val release = parse(releaseText) ?: return 0
        return score(parse(subtitleText), release)
    }

    private fun score(subtitle: Release?, release: Release): Int {
        if (subtitle == null) return 0
        var score = 0
        if (release.group != null && (subtitle.group == release.group || release.group in subtitle.tokens)) score += 8
        if (release.source != null && subtitle.source != null) {
            score += if (release.source == subtitle.source || sameWebFamily(release.source, subtitle.source)) 4 else -2
        }
        if (release.service != null && subtitle.service == release.service) score += 3
        if (release.resolution != null && subtitle.resolution == release.resolution) score += 1
        return score
    }

    // A "WEB" release is either WEB-DL or WEBRip; their timing usually matches each other.
    private fun sameWebFamily(a: String, b: String): Boolean =
        (a == "web" && b.startsWith("web")) || (b == "web" && a.startsWith("web"))

    private fun parse(text: String): Release? {
        val name = text.lowercase()
            .replace("%20", " ").replace("web-dl", "webdl").replace("web dl", "webdl")
            .replace(Regex("\\.(srt|ass|ssa|vtt|sub|zip|gz|mkv|mp4|avi)\\b"), " ")
        val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        // The group comes last in a release name: "...H.264-FLUX".
        val group = Regex("-([a-z0-9]{2,20})(?![a-z0-9])").findAll(name)
            .map { it.groupValues[1] }
            .filter { it !in notGroups && it !in sources && it !in resolutions && !it.all(Char::isDigit) }
            .lastOrNull()
        val source = tokens.firstNotNullOfOrNull { sources[it] }
        val service = tokens.firstOrNull { it in services }
        val resolution = tokens.firstOrNull { it in resolutions }?.let { if (it == "4k" || it == "uhd") "2160p" else it }
        if (group == null && source == null && service == null && resolution == null) return null
        return Release(tokens.toSet(), group, source, service, resolution)
    }
}

/** What the stream says about its release: its name, description and file name. */
internal fun PlayerScreenRuntime.streamReleaseText(): String =
    listOf(activeStreamTitle, activeStreamSubtitle.orEmpty(), activeSourceUrl.substringAfterLast('/').substringBefore('?'))
        .joinToString(" ")

internal fun AddonSubtitle.releaseText(): String = "$display $id ${url.substringAfterLast('/')}"
