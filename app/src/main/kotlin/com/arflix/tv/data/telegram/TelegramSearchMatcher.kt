package com.arflix.tv.data.telegram

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramSearchMatcher @Inject constructor() {

    companion object {
        // sep: 0–2 separator chars between tokens (matches space, dot, dash, underscore, etc.)
        private const val SEP = """[\s._\-]{0,2}"""
        private const val SEP_MID = """[\s._\-]{0,4}"""
        private val EPISODE_PATTERN = Regex(
            // Latin: S01E01 or Season 1 Episode 1
            """[Ss](?:eason)?$SEP(\d{1,2})${SEP_MID}[Ee](?:pisode)?$SEP(\d{1,4})""" +
            // Hebrew: עונה 5 פרק 2  or  ע5פ2  or  ע5 פ2
            """|ע(?:ונה)?$SEP(\d{1,2})${SEP_MID}פ(?:רק)?$SEP(\d{1,4})""",
            RegexOption.IGNORE_CASE
        )
        private val YEAR_PATTERN = Regex("""\b(19|20)\d{2}\b""")
        private val NOISE = Regex("""[._\-\[\]()'",!?:]""")
        private val MULTI_SPACE = Regex("""\s+""")
        private val SIZE_SUFFIX = Regex("""\.(mkv|mp4|avi|mov|wmv|m4v|ts|m2ts)$""", RegexOption.IGNORE_CASE)
    }

    /**
     * Score how well a Telegram file name / caption matches the given title.
     * Returns 0–100. Threshold for inclusion: 55.
     * Returns 0 immediately for definite wrong-episode matches (hard exclude).
     */
    fun score(
        fileName: String,
        caption: String,
        title: String,
        hebrewTitle: String? = null,
        year: Int?,
        season: Int?,
        episode: Int?
    ): Int {
        val combined = "$fileName $caption"
        val normalizedFile = normalize(combined)
        val normalizedTitle = normalize(title)
        val normalizedHebrew = hebrewTitle?.let { normalize(it) }

        // Title must be present (English or Hebrew)
        val titleMatches = normalizedFile.contains(normalizedTitle) ||
            (normalizedHebrew != null && normalizedHebrew.isNotBlank() && normalizedFile.contains(normalizedHebrew))
        if (!titleMatches) return 0

        var score = 60  // base score for title match

        // Year match bonus
        if (year != null) {
            val fileYears = YEAR_PATTERN.findAll(combined).map { it.value.toInt() }.toList()
            score += when {
                fileYears.contains(year) -> 20
                fileYears.any { kotlin.math.abs(it - year) == 1 } -> 5
                fileYears.isEmpty() -> 5  // no year in filename — slight bonus (generic upload)
                else -> -10
            }
        }

        // Episode match for series — search both raw and normalized (handles underscores)
        if (season != null && episode != null) {
            val match = EPISODE_PATTERN.find(combined) ?: EPISODE_PATTERN.find(normalizedFile)
            if (match != null) {
                val fileSeason = (match.groupValues[1].toIntOrNull()
                    ?: match.groupValues[3].toIntOrNull()) ?: -1
                val fileEp = (match.groupValues[2].toIntOrNull()
                    ?: match.groupValues[4].toIntOrNull()) ?: -1
                when {
                    fileSeason == season && fileEp == episode -> score += 20
                    fileSeason == season && fileEp == -1 -> score -= 5  // season matched, episode unreadable
                    else -> return 0  // wrong episode or wrong season — hard exclude
                }
            } else {
                score -= 10  // series content but no episode tag — likely not what we want
            }
        } else if (season == null) {
            // Movie: penalize if file looks like a series episode
            if (EPISODE_PATTERN.containsMatchIn(combined) || EPISODE_PATTERN.containsMatchIn(normalizedFile)) score -= 20
        }

        return score.coerceIn(0, 100)
    }

    /** Build search queries for a movie (title + optional year, plus Hebrew variants). */
    fun buildMovieQueries(title: String, year: Int?, hebrewTitle: String? = null): List<String> {
        val base = cleanTitle(title)
        val hebrewBase = hebrewTitle?.let { cleanTitle(it) }
        val queries = mutableListOf<String>()
        if (year != null) queries.add("$base $year") else queries.add(base)
        if (year != null) queries.add(base)
        if (hebrewBase != null) {
            if (year != null) queries.add("$hebrewBase $year")
            queries.add(hebrewBase)
        }
        return queries.distinct()
    }

    /** Build multiple episode-specific queries for a series — mirrors Stremiogram's approach. */
    fun buildSeriesQueries(title: String, season: Int, episode: Int, hebrewTitle: String? = null): List<String> {
        val base = cleanTitle(title)
        val hebrewBase = hebrewTitle?.let { cleanTitle(it) }
        val s = season.toString()
        val e = episode.toString()
        val s2 = season.toString().padStart(2, '0')
        val e2 = episode.toString().padStart(2, '0')
        val queries = mutableListOf(
            "$base ע$s פ$e",
            "$base ע${s}פ${e}",
            "$base עונה $s פרק $e",
            "$base s${s}e${e}",
            "$base s${s2}e${e2}"
        )
        if (hebrewBase != null) {
            queries.addAll(listOf(
                "$hebrewBase ע$s פ$e",
                "$hebrewBase ע${s}פ${e}",
                "$hebrewBase עונה $s פרק $e",
                "$hebrewBase s${s}e${e}",
                "$hebrewBase s${s2}e${e2}"
            ))
        }
        return queries.map { it.lowercase() }.distinct()
    }

    private fun cleanTitle(title: String) =
        title.replace(":", "").replace("  ", " ").trim()

    private fun normalize(text: String): String =
        text.replace(SIZE_SUFFIX, "")
            .replace(NOISE, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
            .lowercase()
}
