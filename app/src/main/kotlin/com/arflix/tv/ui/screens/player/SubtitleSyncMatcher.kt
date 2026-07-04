package com.arflix.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/**
 * "Find best match": scores candidate subtitle tracks by how well their on-screen cue at a given
 * moment matches what the AI hearing transcribed being spoken at that same moment. A well-synced
 * subtitle shows the right words at the right time; an out-of-sync one does not. The comparison
 * is language-agnostic word overlap (normalization additionally strips Hebrew niqqud and bidi
 * marks, which is a no-op for other languages); callers pass candidates in the user's preferred
 * subtitle language.
 */
object SubtitleSyncMatcher {

    private const val TAG = "SubMatch"

    data class TimedCue(val startMs: Long, val endMs: Long, val text: String)

    /** One collected AI-hearing sample: the spoken line and the media position when it arrived. */
    data class SpokenSample(val text: String, val positionMs: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun loadCues(url: String): List<TimedCue> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body ?: return@use emptyList()
                val raw = body.bytes()
                val text = if (looksGzipped(url, raw)) {
                    GZIPInputStream(raw.inputStream()).bufferedReader(Charsets.UTF_8).readText()
                } else {
                    String(raw, Charsets.UTF_8)
                }
                parseCues(text)
            }
        }.onFailure { error ->
            Log.w(TAG, "loadCues failed url=$url err=${error.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Score a candidate's cues against the collected spoken samples. For each sample we look at
     * the audio's media time (arrival position minus the AI latency), find cues within a tolerance
     * window, and count a hit when the best word-overlap clears [minSimilarity]. Returns the hit
     * ratio in 0..1.
     */
    fun score(
        cues: List<TimedCue>,
        samples: List<SpokenSample>,
        latencyMs: Long,
        toleranceMs: Long,
        minSimilarity: Double
    ): Double {
        if (cues.isEmpty() || samples.isEmpty()) return 0.0
        var hits = 0
        for (sample in samples) {
            val audioTime = sample.positionMs - latencyMs
            val best = cues.asSequence()
                .filter { it.endMs >= audioTime - toleranceMs && it.startMs <= audioTime + toleranceMs }
                .maxOfOrNull { similarity(sample.text, it.text) } ?: 0.0
            if (best >= minSimilarity) hits++
        }
        return hits.toDouble() / samples.size
    }

    /**
     * Score a candidate against a synced reference (a built-in subtitle's cue-visible intervals)
     * purely by timing. For each reference interval we measure how much of it is covered by the
     * union of the candidate's cues, and average the fractions. A synced candidate has cues at
     * the same times → high coverage; an offset candidate → low. Union (not best-single-cue)
     * because realtime-collected reference intervals merge back-to-back cues into long windows
     * that span several candidate cues. Language-agnostic (no translation needed).
     */
    fun scoreByTiming(cues: List<TimedCue>, referenceIntervals: List<Pair<Long, Long>>): Double {
        if (cues.isEmpty() || referenceIntervals.isEmpty()) return 0.0
        val sorted = cues.sortedBy { it.startMs }
        var sum = 0.0
        for ((rs, re) in referenceIntervals) {
            val refDur = (re - rs).coerceAtLeast(1L)
            var covered = 0L
            var cursor = rs
            for (c in sorted) {
                if (c.endMs <= cursor) continue
                if (c.startMs >= re) break
                val s = maxOf(c.startMs, cursor)
                val e = minOf(c.endMs, re)
                if (e > s) {
                    covered += e - s
                    cursor = e
                }
            }
            sum += (covered.toDouble() / refDur).coerceIn(0.0, 1.0)
        }
        return sum / referenceIntervals.size
    }

    // ── Similarity ────────────────────────────────────────────────────────────

    /** Overlap coefficient of normalized word sets — tolerant of different wording/length. */
    fun similarity(a: String, b: String): Double {
        val setA = tokens(a)
        val setB = tokens(b)
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.count { it in setB }
        return intersection.toDouble() / minOf(setA.size, setB.size)
    }

    private fun tokens(text: String): Set<String> =
        normalize(text).split(' ').filter { it.length >= 2 }.toSet()

    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                // Strip Hebrew niqqud/cantillation and bidi control marks.
                ch in '֑'..'ׇ' -> {}
                ch == '‎' || ch == '‏' || ch == '‪' || ch == '‫' ||
                    ch == '‬' || ch == '⁦' || ch == '⁧' || ch == '⁩' -> {}
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun cueTextAt(cues: List<TimedCue>, timeMs: Long): String? =
        cues.firstOrNull { timeMs in it.startMs..it.endMs }?.text

    // ── Parsing (SRT + WEBVTT) ──────────────────────────────────────────────────

    private fun looksGzipped(url: String, bytes: ByteArray): Boolean =
        url.substringBefore('?').endsWith(".gz", ignoreCase = true) ||
            (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())

    private val TIME_LINE = Regex(
        """(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})"""
    )
    private val TAG_STRIP = Regex("<[^>]*>")

    fun parseCues(content: String): List<TimedCue> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = ArrayList<TimedCue>()
        for (block in blocks) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeLineIndex = lines.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeLineIndex < 0) continue
            val match = TIME_LINE.find(lines[timeLineIndex]) ?: continue
            val start = parseTimestamp(match.groupValues[1]) ?: continue
            val end = parseTimestamp(match.groupValues[2]) ?: continue
            val text = lines.drop(timeLineIndex + 1)
                .joinToString(" ")
                .replace(TAG_STRIP, "")
                .trim()
            if (text.isNotEmpty() && end > start) cues.add(TimedCue(start, end, text))
        }
        return cues
    }

    private fun parseTimestamp(value: String): Long? {
        val cleaned = value.replace(',', '.')
        val parts = cleaned.split(':')
        return runCatching {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    (h * 3600 + m * 60) * 1000 + (s * 1000).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    (m * 60) * 1000 + (s * 1000).toLong()
                }
                else -> null
            }
        }.getOrNull()
    }
}
