package app.echoes.flow

import app.echoes.data.Analysis
import app.echoes.data.Outcome
import app.echoes.data.Play
import app.echoes.library.Folder
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.library.primaryArtist
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.tanh
import kotlin.random.Random

/** What the listening history says: which song tends to follow which, and what gets skipped. */
class Taste(plays: List<Play>) {
    private val transitions = HashMap<String, HashMap<String, Float>>()
    private val completions = HashMap<String, Int>()
    private val skips = HashMap<String, Int>()
    val lastPlayed = HashMap<String, Long>()
    val listenedMs = HashMap<String, Long>()

    init {
        for (p in plays) {
            when (p.outcome) {
                Outcome.COMPLETED -> completions.merge(p.path, 1, Int::plus)
                Outcome.SKIPPED -> skips.merge(p.path, 1, Int::plus)
                Outcome.PARTIAL -> {}
            }
            lastPlayed.merge(p.path, p.startedAt, ::maxOf)
            listenedMs.merge(p.path, p.listenedMs, Long::plus)
            val prev = p.prevPath ?: continue
            val w = when (p.outcome) {
                Outcome.COMPLETED -> 1f
                Outcome.PARTIAL -> 0.3f
                Outcome.SKIPPED -> -0.7f
            }
            transitions.getOrPut(prev) { HashMap() }.merge(p.path, w, Float::plus)
        }
    }

    fun transition(from: String, to: String): Float = transitions[from]?.get(to) ?: 0f
    fun plays(path: String) = (completions[path] ?: 0) + (skips[path] ?: 0)
    fun completions(path: String) = completions[path] ?: 0

    fun skipRate(path: String): Float {
        val s = skips[path] ?: 0
        val total = s + (completions[path] ?: 0)
        return if (total < 2) 0f else s.toFloat() / total
    }
}

/** Lists ordered by the last time any of their songs played; never-played lists go last, A–Z. */
fun Library.foldersByRecentPlay(taste: Taste): List<Folder> {
    val last = HashMap<String, Long>()
    for ((path, at) in taste.lastPlayed) {
        val folder = byPath[path]?.folder ?: continue
        if (at > (last[folder] ?: 0)) last[folder] = at
    }
    return folders.sortedWith(compareByDescending<Folder> { last[it.relPath] ?: 0L })
}

class FlowContext(
    val library: Library,
    val analysis: Map<String, Analysis>,
    val taste: Taste,
    val favorites: Set<String>,
    val now: Long = System.currentTimeMillis(),
) {
    fun sonic(t: Track): Analysis? = analysis[t.path]?.takeIf { it.usable && it.bpm > 0 }
}

object FlowEngine {
    private fun tempoSimilarity(a: Float, b: Float): Double {
        val d = listOf(abs(a - b), abs(a * 2 - b), abs(a - b * 2)).min()
        return exp(-(d / 10.0) * (d / 10.0))
    }

    /** How good `c` is as the song after `seed`. Every term is a separate, readable signal. */
    fun score(ctx: FlowContext, seed: Track, c: Track, rnd: Random): Double {
        val taste = ctx.taste
        var s = 0.0
        s += 2.5 * tanh(taste.transition(seed.path, c.path) / 2.0)
        s += 1.0 * tanh(taste.transition(c.path, seed.path) / 2.0)
        if (primaryArtist(seed.artist) == primaryArtist(c.artist)) s += 1.1
        if (seed.folder == c.folder) s += 0.8
        if (seed.album == c.album) s += 0.3
        val a = ctx.sonic(seed)
        val b = ctx.sonic(c)
        if (a != null && b != null) {
            s += 1.0 * tempoSimilarity(a.bpm, b.bpm)
            s += 1.6 * (1 - min(1.0, abs(a.energy - b.energy) * 2.5))
        }
        if (c.path in ctx.favorites) s += 0.6
        s -= 2.5 * taste.skipRate(c.path)
        val last = taste.lastPlayed[c.path]
        if (last != null && ctx.now - last < 3 * 3600_000) s -= 3.0
        return s + rnd.nextDouble() * 1.2
    }

    /** Chains picks so the radio drifts naturally instead of orbiting the first seed. */
    fun continueFrom(ctx: FlowContext, seed: Track, exclude: Set<String>, count: Int, rnd: Random = Random.Default): List<Track> {
        val used = exclude.toMutableSet()
        val out = ArrayList<Track>()
        var current = seed
        val recentArtists = ArrayDeque<String>()
        repeat(count) {
            val next = ctx.library.tracks.asSequence()
                .filter { it.path !in used }
                .maxByOrNull { c ->
                    val artistRepeat = if (primaryArtist(c.artist) in recentArtists) 1.8 else 0.0
                    score(ctx, current, c, rnd) - artistRepeat
                } ?: return out
            out += next
            used += next.path
            recentArtists.addLast(primaryArtist(next.artist))
            if (recentArtists.size > 2) recentArtists.removeFirst()
            current = next
        }
        return out
    }

    /** A sequence whose energy follows a curve (warm-up, wind-down) while staying coherent song to song. */
    fun arc(ctx: FlowContext, from: Float, to: Float, count: Int, pool: List<Track> = ctx.library.tracks, rnd: Random = Random.Default): List<Track> {
        val analysed = pool.filter { ctx.sonic(it) != null }
        if (analysed.size < count) return emptyList()
        val out = ArrayList<Track>()
        val used = HashSet<String>()
        for (i in 0 until count) {
            val target = from + (to - from) * i / (count - 1f)
            val prev = out.lastOrNull()
            val next = analysed.filter { it.path !in used }.maxByOrNull { c ->
                val e = ctx.sonic(c)!!.energy
                val fit = -abs(e - target) * 10
                fit + (if (prev != null) score(ctx, prev, c, rnd) * 0.5 else rnd.nextDouble() * 2)
            } ?: break
            out += next
            used += next.path
        }
        return out
    }
}
