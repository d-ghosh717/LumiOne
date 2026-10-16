package com.lumione.player.player

import java.util.Locale

/**
 * Strict track and artist matching utility.
 * Ensures LumiOne NEVER silently substitutes a remix, cover, live version,
 * instrumental, acoustic, or alternate recording for the requested track.
 */
object TrackMatcher {

    // Pure media publishing artifacts (safe to ignore for audio matching)
    private val PUBLISHING_TAGS_REGEX = Regex(
        "(?i)\\b(official\\s*(music\\s*)?video|official\\s*audio|official\\s*lyric\\s*video|official\\s*visualizer|lyrics?|visualizer|hd|4k|remaster(ed)?|explicit)\\b"
    )

    // Version, modifier, and alternate recording indicators
    private val MODIFIER_KEYWORDS = listOf(
        "remix", "rework", "edit", "extended", "radio edit", "live", "acoustic",
        "cover", "instrumental", "karaoke", "sped up", "spedup", "speed up",
        "slowed", "reverb", "nightcore", "daycore", "mashup", "bootleg",
        "remake", "alternate", "version", "tribute", "orchestral", "orchestra",
        "piano", "guitar", "violin", "drum", "instrument", "8d", "lo-fi", "lofi",
        "bass boosted", "bassboosted", "oneupped", "vip", "flip", "parody", "tribute"
    )

    private val FEATURED_ARTIST_REGEX = Regex(
        "(?i)\\b(feat|ft|featuring|with)\\b.*"
    )

    /**
     * Cleans publishing metadata while preserving core musical and version identifiers.
     */
    fun cleanTitle(title: String): String {
        return title
            .replace(PUBLISHING_TAGS_REGEX, " ")
            .replace(FEATURED_ARTIST_REGEX, " ")
            .replace(Regex("[\\[\\]\\(\\)\\{\\}\\\"\\']"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Cleans artist names by removing label/channel suffixes.
     */
    fun cleanArtist(artist: String): String {
        return artist
            .replace(FEATURED_ARTIST_REGEX, " ")
            .replace(Regex("(?i)\\b(vevo|topic|records|music|official)\\b"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Checks whether candidate title contains any modifier keyword not present in the query.
     */
    fun hasUnwantedModifier(queryTitle: String, candidateTitle: String): Boolean {
        val qLower = queryTitle.lowercase(Locale.ROOT)
        val cLower = candidateTitle.lowercase(Locale.ROOT)

        for (mod in MODIFIER_KEYWORDS) {
            val modRegex = Regex("(?i)\\b${Regex.escape(mod)}\\b")
            val inCandidate = modRegex.containsMatchIn(cLower) || cLower.contains(mod)
            val inQuery = modRegex.containsMatchIn(qLower) || qLower.contains(mod)

            if (inCandidate && !inQuery) {
                return true
            }
        }
        return false
    }

    /**
     * Inspects bracketed / parenthetical phrases in candidate title to ensure no version tag is snuck in.
     */
    private fun hasUnmatchedBracketContent(queryTitle: String, candidateTitle: String): Boolean {
        val bracketRegex = Regex("[\\[\\(]([^\\]\\)]+)[\\]\\)]")
        val candidateMatches = bracketRegex.findAll(candidateTitle)

        for (match in candidateMatches) {
            val inner = match.groupValues[1].trim()
            val cleanedInner = inner.replace(PUBLISHING_TAGS_REGEX, "").trim()
            if (cleanedInner.isNotBlank()) {
                // If the candidate bracket contains words not in query title, it's an alternate version
                val queryLower = queryTitle.lowercase(Locale.ROOT)
                val innerTokens = cleanedInner.lowercase(Locale.ROOT)
                    .split(Regex("[^a-zA-Z0-9]+"))
                    .filter { it.length > 2 }

                for (token in innerTokens) {
                    if (!queryLower.contains(token)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Strict matcher:
     * 1. Rejects any unwanted modifiers (remix, cover, acoustic, sped up, live, etc.).
     * 2. Rejects unmatched parenthetical/bracket version additions (e.g. "(OneUpped)").
     * 3. Requires strong artist match between candidate and query.
     * 4. Requires exact core title token match without extra descriptor tokens.
     */
    fun isReliableMatch(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String
    ): Boolean {
        // 1. Modifier check: Reject if candidate contains any modifier not in query
        if (hasUnwantedModifier(queryTitle, candidateTitle)) {
            return false
        }

        // 2. Bracket check: Reject if candidate has extra bracket version information
        if (hasUnmatchedBracketContent(queryTitle, candidateTitle)) {
            return false
        }

        val cQueryArtist = cleanArtist(queryArtist)
        val cCandArtist = cleanArtist(candidateArtist)

        // 3. Artist match verification
        if (cQueryArtist.isNotBlank()) {
            val queryArtistTokens = cQueryArtist.split(" ").filter { it.length > 1 }
            val candArtistTokens = cCandArtist.split(" ").filter { it.length > 1 }

            val artistMatchesInArtistField = queryArtistTokens.isNotEmpty() && queryArtistTokens.all { qToken ->
                candArtistTokens.any { cToken -> cToken.contains(qToken) || qToken.contains(cToken) }
            }

            if (!artistMatchesInArtistField) {
                // If candidate artist is not the original artist, reject
                return false
            }
        }

        // 4. Title match verification
        var cQueryTitle = cleanTitle(queryTitle)
        var cCandTitle = cleanTitle(candidateTitle)

        // If title contains artist prefix ("Alan Walker - Faded"), strip the artist name from both titles
        if (cQueryArtist.isNotBlank()) {
            val strippedQuery = cQueryTitle.replace(cQueryArtist, " ").trim().replace(Regex("\\s+"), " ")
            if (strippedQuery.isNotBlank()) {
                cQueryTitle = strippedQuery
            }
            val strippedCand = cCandTitle.replace(cQueryArtist, " ").trim().replace(Regex("\\s+"), " ")
            if (strippedCand.isNotBlank()) {
                cCandTitle = strippedCand
            }
        }

        if (cQueryTitle.isBlank() || cCandTitle.isBlank()) return false

        // Exact match of core title tokens
        val qTokens = cQueryTitle.split(" ").filter { it.length > 1 }
        val cTokens = cCandTitle.split(" ").filter { it.length > 1 }

        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        // All query tokens must be present in candidate title
        val allQueryInCandidate = qTokens.all { qToken ->
            cTokens.any { cToken -> cToken == qToken }
        }

        // Candidate must not contain extraneous words that change the track identity
        val extraCandidateTokens = cTokens.filter { cToken ->
            qTokens.none { qToken -> qToken == cToken }
        }

        // If candidate contains extra words, reject unless they are negligible connector words
        if (!allQueryInCandidate || extraCandidateTokens.isNotEmpty()) {
            return false
        }

        return true
    }
}
