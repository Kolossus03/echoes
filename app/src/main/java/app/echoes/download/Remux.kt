package app.echoes.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * YouTube serves m4a as fragmented MP4 (DASH): ~10 s fragments that make every seek decode
 * from the fragment start. Rewriting it as a plain MP4 (same AAC data, no re-encoding) makes
 * seeking instant for playback and for the analyzer.
 */
object Remux {
    fun toMp4(src: File, dst: File) {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true }
            ex.selectTrack(idx)
            val track = muxer.addTrack(ex.getTrackFormat(idx))
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
            ex.release()
        }
    }
}
