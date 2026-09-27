package app.echoes.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

class AnalysisResult(
    val loudnessLufs: Float,
    val peakDb: Float,
    val introMs: Long,
    val outroMs: Long,
    val energy: Float,
    val bpm: Float,
    val envelope: ByteArray,
)

/**
 * Measures a song without decoding all of it. On Android every compressed frame is a binder
 * round trip to the codec process, which caps decoding near 10x realtime; so we decode the
 * head and tail (silence), five body windows (loudness, tempo, energy) and short probes
 * spread over the whole song (waveform). About a quarter of the audio, same answers.
 */
object AudioAnalyzer {
    const val ENVELOPE_POINTS = 64
    private const val HEAD_US = 12_000_000L
    private const val TAIL_US = 15_000_000L
    private const val WINDOW_US = 6_000_000L
    private val windowAt = floatArrayOf(0.14f, 0.31f, 0.5f, 0.67f, 0.84f)

    fun analyze(context: Context, uri: Uri): AnalysisResult {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        try {
            val trackIdx = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            ex.selectTrack(trackIdx)
            val format = ex.getTrackFormat(trackIdx)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(MediaFormat(format).apply { setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) }, null, null, 0)
            codec.start()
            try {
                return measure(Decoder(ex, codec, format), format.getLong(MediaFormat.KEY_DURATION))
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            ex.release()
        }
    }

    private fun measure(dec: Decoder, durUs: Long): AnalysisResult {
        val sr = dec.sampleRate
        val head = Meter(sr, dec.stereo)
        dec.read(0, min(HEAD_US, durUs), head::push)
        val tail = Meter(sr, dec.stereo)
        dec.read(max(0, durUs - TAIL_US), Long.MAX_VALUE, tail::push)

        val body = Meter(sr, dec.stereo)
        if (durUs > HEAD_US + TAIL_US + WINDOW_US) {
            for (f in windowAt) {
                body.newRegion()
                val start = (durUs * f).toLong() - WINDOW_US / 2
                dec.read(start, start + WINDOW_US, body::push)
            }
        } else {
            dec.read(0, Long.MAX_VALUE, body::push)
        }

        val envelope = ByteArray(ENVELOPE_POINTS) { i ->
            val db = dec.probeDb(durUs * (2L * i + 1) / (2L * ENVELOPE_POINTS))
            (((db + 48) / 48).coerceIn(0.0, 1.0) * 255).toInt().toByte()
        }

        val lufs = Meter.integratedLoudness(listOf(head, body, tail))
        val (bpm, clarity) = body.tempo()
        val loudTerm = ((lufs + 30) / 24).coerceIn(0.0, 1.0)
        val brightTerm = ((log10(max(body.brightness(), 1e-6)) + 2.3) / 1.5).coerceIn(0.0, 1.0)
        val onsetTerm = (body.onsetRate() / 5.0).coerceIn(0.0, 1.0)
        val energy = 0.4 * loudTerm + 0.25 * brightTerm + 0.2 * onsetTerm + 0.15 * clarity
        val peak = maxOf(head.peak, body.peak, tail.peak)
        return AnalysisResult(
            loudnessLufs = lufs.toFloat(),
            peakDb = (20 * log10(max(peak, 1e-6f).toDouble())).toFloat(),
            introMs = head.leadingSilenceMs(),
            outroMs = tail.trailingSilenceMs(),
            energy = energy.toFloat().coerceIn(0f, 1f),
            bpm = bpm.toFloat(),
            envelope = envelope,
        )
    }

    private class Decoder(private val ex: MediaExtractor, private val codec: MediaCodec, format: MediaFormat) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val stereo get() = channels > 1
        private var float = false
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)
        private val info = MediaCodec.BufferInfo()
        private var fresh = true
        private val deadline = System.nanoTime() + 40_000_000_000L

        private fun seek(us: Long) {
            if (!fresh) codec.flush()
            fresh = false
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        /** Feeds [startUs, endUs) to `sink` as (left, right) frames; stops early at end of stream. */
        fun read(startUs: Long, endUs: Long, sink: (Float, Float) -> Unit) {
            seek(startUs)
            decode(startUs, endUs) { n, frames ->
                if (float) {
                    for (i in 0 until frames) { val l = floats[i * n]; sink(l, if (n > 1) floats[i * n + 1] else l) }
                } else {
                    val k = 1f / 32768f
                    for (i in 0 until frames) { val l = shorts[i * n] * k; sink(l, if (n > 1) shorts[i * n + 1] * k else l) }
                }
                true
            }
        }

        /** Loudness (dBFS RMS) of ~50 ms at `us`, skipping the first frame after the seek. */
        fun probeDb(us: Long): Double {
            seek(us)
            var sum = 0.0
            var count = 0
            var buffers = 0
            decode(us, Long.MAX_VALUE) { n, frames ->
                if (buffers++ > 0) {
                    for (i in 0 until frames) {
                        val v = if (float) floats[i * n] else shorts[i * n] / 32768f
                        sum += v * v
                    }
                    count += frames
                }
                buffers < 3
            }
            return if (count == 0) -96.0 else 10 * log10(max(sum / count, 1e-10))
        }

        private inline fun decode(startUs: Long, endUs: Long, onPcm: (channels: Int, frames: Int) -> Boolean) {
            var inputDone = false
            var idle = 0
            val stopFeedingAt = if (endUs == Long.MAX_VALUE) Long.MAX_VALUE else endUs + 500_000
            while (true) {
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val n = ex.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                    if (n < 0 || ex.sampleTime > stopFeedingAt) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                if (System.nanoTime() > deadline) throw java.util.concurrent.TimeoutException("analysis took over 40 s")
                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (outIdx < 0) {
                    if (++idle > 2500) return
                    continue
                }
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                var more = true
                if (info.size > 0 && pts >= startUs && pts < endUs) {
                    val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames: Int
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        val n = fb.remaining()
                        if (floats.size < n) floats = FloatArray(n)
                        fb.get(floats, 0, n)
                        frames = n / channels
                    } else {
                        val sb = buf.asShortBuffer()
                        val n = sb.remaining()
                        if (shorts.size < n) shorts = ShortArray(n)
                        sb.get(shorts, 0, n)
                        frames = n / channels
                    }
                    more = onPcm(channels, frames)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (eos || !more || pts >= endUs) return
            }
        }
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** BS.1770 pre-filter (high shelf) and RLB high-pass, derived for any sample rate. */
    private fun kWeighting(fs: Int): Pair<Biquad, Biquad> {
        var k = tan(PI * 1681.974450955533 / fs)
        val q1 = 0.7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q1 + k * k
        val shelf = Biquad(
            (vh + vb * k / q1 + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q1 + k * k) / a0,
            2 * (k * k - 1) / a0, (1 - k / q1 + k * k) / a0,
        )
        k = tan(PI * 38.13547087602444 / fs)
        val q2 = 0.5003270373238773
        a0 = 1 + k / q2 + k * k
        return shelf to Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q2 + k * k) / a0)
    }

    /** Measurements over one or more contiguous regions of a song. */
    private class Meter(val sampleRate: Int, val stereo: Boolean) {
        private class Region {
            val kBlocks = FloatList()
            val rawBlocks = FloatList()
            val hopAll = FloatList()
            val hopHi = FloatList()
        }

        private val regions = mutableListOf(Region())
        private var kL = kWeighting(sampleRate)
        private var kR = kWeighting(sampleRate)
        private val blockLen = sampleRate / 10
        private val hopLen = sampleRate / 100
        private var blockK = 0.0
        private var blockRaw = 0.0
        private var blockN = 0
        private var hopAll = 0.0
        private var hopHi = 0.0
        private var hopN = 0
        private var prevMono = 0f
        var peak = 0f; private set

        fun newRegion() {
            if (regions.last().hopAll.size == 0) return
            regions += Region()
            kL = kWeighting(sampleRate); kR = kWeighting(sampleRate)
            blockK = 0.0; blockRaw = 0.0; blockN = 0; hopAll = 0.0; hopHi = 0.0; hopN = 0
        }

        fun push(l: Float, r: Float) {
            val a = abs(l); if (a > peak) peak = a
            val b = abs(r); if (b > peak) peak = b
            val kl = kL.second.step(kL.first.step(l.toDouble()))
            blockK += if (stereo) {
                val kr = kR.second.step(kR.first.step(r.toDouble()))
                kl * kl + kr * kr
            } else kl * kl
            val mono = (l + r) * 0.5f
            blockRaw += mono * mono
            val reg = regions.last()
            if (++blockN == blockLen) {
                reg.kBlocks.add((blockK / blockN).toFloat())
                reg.rawBlocks.add((blockRaw / blockN).toFloat())
                blockK = 0.0; blockRaw = 0.0; blockN = 0
            }
            val hi = mono - prevMono
            prevMono = mono
            hopAll += mono * mono
            hopHi += hi * hi
            if (++hopN == hopLen) {
                reg.hopAll.add((hopAll / hopN).toFloat())
                reg.hopHi.add((hopHi / hopN).toFloat())
                hopAll = 0.0; hopHi = 0.0; hopN = 0
            }
        }

        private val silent = 10.0.pow(-50.0 / 10)

        fun leadingSilenceMs(): Long {
            val b = regions.first().rawBlocks
            val first = (0 until b.size).firstOrNull { b[it] > silent } ?: return 0
            return ((first - 1) * 100L).coerceAtLeast(0).takeIf { it >= 400 } ?: 0
        }

        fun trailingSilenceMs(): Long {
            val b = regions.last().rawBlocks
            val last = (b.size - 1 downTo 0).firstOrNull { b[it] > silent } ?: return 0
            return ((b.size - 2 - last) * 100L).coerceAtLeast(0).takeIf { it >= 400 } ?: 0
        }

        fun brightness(): Double = regions.sumOf { it.hopHi.sum() } / max(regions.sumOf { it.hopAll.sum() }, 1e-12)

        /** Positive jumps in high-frequency energy, minus their 0.5 s running mean. */
        private fun onset(r: Region): FloatArray {
            val n = r.hopHi.size
            val d = FloatArray(n)
            var prev = 0f
            for (i in 0 until n) {
                val e = ln(1 + 1000 * r.hopHi[i])
                d[i] = if (i == 0) 0f else max(0f, e - prev)
                prev = e
            }
            val w = 50
            var acc = 0.0
            return FloatArray(n) { i ->
                acc += d[i]
                if (i >= w) acc -= d[i - w]
                max(0f, d[i] - (acc / min(i + 1, w)).toFloat())
            }
        }

        fun onsetRate(): Double {
            var peaks = 0
            var seconds = 0.0
            for (r in regions) {
                val o = onset(r)
                if (o.isEmpty()) continue
                seconds += o.size / 100.0
                val m = o.average()
                val sd = sqrt(o.fold(0.0) { s, v -> s + (v - m) * (v - m) } / o.size)
                var i = 1
                while (i < o.size - 1) {
                    if (o[i] > m + sd && o[i] >= o[i - 1] && o[i] >= o[i + 1]) { peaks++; i += 8 } else i++
                }
            }
            return if (seconds == 0.0) 0.0 else peaks / seconds
        }

        /** Summed per-region autocorrelation of the onset curve, with a log-normal prior at 120 BPM. */
        fun tempo(): Pair<Double, Double> {
            val minLag = 30
            val maxLag = 100
            val ac = DoubleArray(maxLag + 2)
            var zero = 0.0
            for (r in regions) {
                val o = onset(r)
                if (o.size < 300) continue
                for (v in o) zero += v.toDouble() * v / o.size
                for (lag in minLag - 1..maxLag + 1) {
                    var s = 0.0
                    for (i in 0 until o.size - lag) s += o[i] * o[i + lag]
                    ac[lag] += s / (o.size - lag)
                }
            }
            if (zero <= 0) return 0.0 to 0.0
            var best = minLag
            var bestScore = -1.0
            for (lag in minLag..maxLag) {
                val prior = exp(-0.5 * (log2(6000.0 / lag / 120) / 0.9).pow(2))
                if (ac[lag] * prior > bestScore) { bestScore = ac[lag] * prior; best = lag }
            }
            val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
            val denom = y0 - 2 * y1 + y2
            val shift = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
            var bpm = 6000.0 / (best + shift)
            while (bpm < 70) bpm *= 2
            while (bpm > 180) bpm /= 2
            return bpm to (ac[best] / zero).coerceIn(0.0, 1.0)
        }

        companion object {
            /** Gated integrated loudness over 400 ms windows that never straddle two regions. */
            fun integratedLoudness(meters: List<Meter>): Double {
                val windows = ArrayList<Double>()
                for (m in meters) for (r in m.regions) {
                    val b = r.kBlocks
                    for (i in 0 until b.size - 3) windows += (b[i] + b[i + 1] + b[i + 2] + b[i + 3]) / 4.0
                }
                fun lk(z: Double) = -0.691 + 10 * log10(max(z, 1e-12))
                val abs = windows.filter { lk(it) > -70 }
                if (abs.isEmpty()) return -70.0
                val gate = lk(abs.average()) - 10
                return lk(abs.filter { lk(it) > gate }.average())
            }
        }
    }

    private class FloatList {
        private var data = FloatArray(256)
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        operator fun get(i: Int) = data[i]
        fun sum(): Double { var s = 0.0; for (i in 0 until size) s += data[i]; return s }
    }
}
