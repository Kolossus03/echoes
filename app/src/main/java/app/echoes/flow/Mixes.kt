package app.echoes.flow

import app.echoes.library.Track
import kotlin.random.Random

class Mix(val id: String, val title: String, val subtitle: String, val tracks: List<Track>)

private class MixSpec(val id: String, val title: String, val subtitle: String, val build: (FlowContext, Random) -> List<Track>)

private const val DAY = 24 * 3600_000L

private val specs = listOf(
    MixSpec("warmup", "Warm up", "La energía sube canción a canción") { ctx, rnd ->
        FlowEngine.arc(ctx, 0.2f, 0.9f, 20, rnd = rnd)
    },
    MixSpec("winddown", "Wind down", "De arriba a abajo, para cerrar el día") { ctx, rnd ->
        FlowEngine.arc(ctx, 0.85f, 0.15f, 20, rnd = rnd)
    },
    MixSpec("onrepeat", "On repeat", "Lo que más has escuchado este mes") { ctx, _ ->
        ctx.library.tracks
            .filter { (ctx.taste.lastPlayed[it.path] ?: 0) > ctx.now - 30 * DAY && ctx.taste.completions(it.path) > 0 }
            .sortedByDescending { ctx.taste.listenedMs[it.path] ?: 0 }
            .take(30)
    },
    MixSpec("gems", "Joyas olvidadas", "Te gustaban y hace tiempo que no suenan") { ctx, rnd ->
        ctx.library.tracks
            .filter { ctx.taste.completions(it.path) >= 2 && (ctx.taste.lastPlayed[it.path] ?: 0) < ctx.now - 21 * DAY }
            .shuffled(rnd).take(25)
    },
    MixSpec("unplayed", "Nunca escuchadas", "Canciones que tienes y nunca has puesto") { ctx, rnd ->
        ctx.library.tracks.filter { ctx.taste.plays(it.path) == 0 }.shuffled(rnd).take(30)
    },
    MixSpec("fresh", "Recién llegadas", "Lo último que has añadido") { ctx, _ ->
        ctx.library.tracks.sortedByDescending { it.dateAdded }.take(30)
    },
)

/** Seeded per day so mixes stay stable while you browse but change tomorrow. */
fun buildMixes(ctx: FlowContext): List<Mix> {
    val daySeed = ctx.now / DAY
    return specs.mapNotNull { spec ->
        val tracks = spec.build(ctx, Random(daySeed * 31 + spec.id.hashCode()))
        if (tracks.size < 5) null else Mix(spec.id, spec.title, spec.subtitle, tracks)
    }
}
