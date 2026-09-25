package com.asif.vmreader

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns any audio file (WhatsApp .opus, .mp3, .m4a …) into 16 kHz mono float samples. */
object AudioDecoder {
    const val TARGET_SR = 16000

    fun decode(path: String): FloatArray {
        // 1) Find the audio track
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "No audio found in this file" }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)

        // 2) Decode compressed audio → raw PCM with Android's built-in decoder
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()

        var sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.get(bytes, 0, info.size)
                    pcm.write(bytes)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            }
        }
        codec.stop(); codec.release(); ex.release()

        // 3) Raw bytes → mono float samples (average the channels)
        val bb = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = bb.remaining() / bytesPerSample / channels
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += if (isFloat) bb.float else bb.short / 32768f
            mono[f] = s / channels
        }

        // 4) Change sample rate to 16 kHz (what Whisper needs)
        return resample(mono, sampleRate, TARGET_SR)
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        if (from % to == 0) {                       // e.g. 48 kHz → 16 kHz: average every 3 samples
            val k = from / to
            val out = FloatArray(x.size / k)
            for (i in out.indices) {
                var s = 0f
                for (j in 0 until k) s += x[i * k + j]
                out[i] = s / k
            }
            return out
        }
        val n = (x.size.toLong() * to / from).toInt()   // other rates: linear interpolation
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * step
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = x[minOf(j, x.size - 1)]
            val b = x[minOf(j + 1, x.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
