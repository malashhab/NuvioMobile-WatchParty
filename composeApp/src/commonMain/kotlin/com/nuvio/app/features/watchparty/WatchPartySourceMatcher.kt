package com.nuvio.app.features.watchparty

data class WatchPartySourceMatch<T>(
    val item: T,
    /** 0 is the strongest match. */
    val tier: Int,
) {
    /** The very same file, so there is no point waiting for slower addons. */
    val isExactFile: Boolean
        get() = tier <= EXACT_FILE_TIER

    private companion object {
        const val EXACT_FILE_TIER = 1
    }
}

/** Finds the follower's stream that best matches the controller's, from strongest evidence to weakest. */
object WatchPartySourceMatcher {
    private val tiers: List<(wanted: WatchPartySource, candidate: WatchPartySource) -> Boolean> = listOf(
        // Same torrent and file.
        { wanted, candidate ->
            sameText(wanted.infoHash, candidate.infoHash) &&
                (wanted.fileIdx == null || candidate.fileIdx == null || wanted.fileIdx == candidate.fileIdx)
        },
        // Same file name, possibly through a different service.
        { wanted, candidate -> sameText(wanted.filename, candidate.filename) },
        // Same addon and exact file size.
        { wanted, candidate ->
            sameAddon(wanted, candidate) && wanted.videoSize != null && wanted.videoSize == candidate.videoSize
        },
        // Same addon and same release line. Only the first line is compared, because later lines often
        // hold live numbers such as seeders.
        { wanted, candidate ->
            sameAddon(wanted, candidate) &&
                sameText(wanted.name, candidate.name) &&
                sameText(firstLine(wanted.description), firstLine(candidate.description))
        },
        // Same addon and same quality group, which Nuvio also uses to pick the next episode.
        { wanted, candidate -> sameAddon(wanted, candidate) && sameText(wanted.bingeGroup, candidate.bingeGroup) },
        // Same addon and same stream name, e.g. the same quality label.
        { wanted, candidate -> sameAddon(wanted, candidate) && sameText(wanted.name, candidate.name) },
    )

    fun <T> bestMatch(
        wanted: WatchPartySource,
        candidates: List<T>,
        fingerprintOf: (T) -> WatchPartySource,
    ): WatchPartySourceMatch<T>? {
        val fingerprints = candidates.map { it to fingerprintOf(it) }
        tiers.forEachIndexed { tier, matches ->
            fingerprints.firstOrNull { (_, candidate) -> matches(wanted, candidate) }?.let { (item, _) ->
                return WatchPartySourceMatch(item = item, tier = tier)
            }
        }
        return null
    }

    private fun sameAddon(wanted: WatchPartySource, candidate: WatchPartySource): Boolean =
        wanted.addonId != null && wanted.addonId == candidate.addonId

    private fun sameText(a: String?, b: String?): Boolean {
        val normalized = a?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return normalized == b?.trim()?.lowercase()
    }

    private fun firstLine(text: String?): String? = text?.lineSequence()?.firstOrNull { it.isNotBlank() }
}
