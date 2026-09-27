package app.echoes.flow

import app.echoes.data.Analysis
import app.echoes.data.Outcome
import app.echoes.data.Play
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.library.primaryArtist
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class Ranked<T>(val item: T, val ms: Long, val plays: Int)

class Insights(plays: List<Play>, library: Library, analysis: Map<String, Analysis>, today: LocalDate = LocalDate.now()) {
    private val zone = ZoneId.systemDefault()
    private val since = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
    private val recent = plays.filter { it.startedAt >= since }
    private fun day(p: Play) = Instant.ofEpochMilli(p.startedAt).atZone(zone).toLocalDate()

    val totalMs: Long = recent.sumOf { it.listenedMs }
    val playCount: Int = recent.count { it.outcome == Outcome.COMPLETED }

    /** Minutes per day for the last 14 days, oldest first. */
    val daily: List<Pair<LocalDate, Long>> = run {
        val byDay = recent.groupBy(::day).mapValues { (_, v) -> v.sumOf { it.listenedMs } }
        (13 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0L) }
    }

    val hours: LongArray = LongArray(24).also { arr ->
        recent.forEach { arr[Instant.ofEpochMilli(it.startedAt).atZone(zone).hour] += it.listenedMs }
    }

    val streak: Int = run {
        val days = plays.map(::day).toSet()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        n
    }

    val topTracks: List<Ranked<Track>> = recent.groupBy { it.path }.mapNotNull { (path, ps) ->
        library.byPath[path]?.let { Ranked(it, ps.sumOf { p -> p.listenedMs }, ps.count { p -> p.outcome == Outcome.COMPLETED }) }
    }.sortedByDescending { it.ms }.take(10)

    val topArtists: List<Ranked<String>> = recent.mapNotNull { p -> library.byPath[p.path]?.let { primaryArtist(it.artist) to p } }
        .groupBy({ it.first }, { it.second })
        .map { (a, ps) -> Ranked(a, ps.sumOf { it.listenedMs }, ps.count { it.outcome == Outcome.COMPLETED }) }
        .sortedByDescending { it.ms }.take(8)

    /** Songs you keep skipping: candidates to hide or delete. */
    val alwaysSkipped: List<Ranked<Track>> = plays.groupBy { it.path }.mapNotNull { (path, ps) ->
        val skips = ps.count { it.outcome == Outcome.SKIPPED }
        if (ps.size >= 3 && skips >= ps.size * 0.6) library.byPath[path]?.let { Ranked(it, 0, skips) } else null
    }.sortedByDescending { it.plays }.take(10)

    /** Listening-weighted averages; what your ears actually go for. */
    val avgBpm: Float?
    val avgEnergy: Float?

    init {
        var w = 0.0; var b = 0.0; var e = 0.0
        for (p in recent) {
            val a = analysis[p.path]?.takeIf { it.usable && it.bpm > 0 } ?: continue
            w += p.listenedMs; b += a.bpm * p.listenedMs; e += a.energy * p.listenedMs
        }
        avgBpm = if (w > 0) (b / w).toFloat() else null
        avgEnergy = if (w > 0) (e / w).toFloat() else null
    }
}
