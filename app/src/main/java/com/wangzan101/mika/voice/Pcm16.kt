package com.wangzan101.mika.voice

import java.io.ByteArrayOutputStream

/** 16-bit little-endian mono PCM helpers shared by the call engine and vendor speech clients. */
object Pcm16 {
    fun toBytes(samples: ShortArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = (s shr 8 and 0xFF).toByte()
        }
        return out
    }

    fun toSamples(bytes: ByteArray, count: Int = bytes.size): ShortArray {
        val n = count / 2
        return ShortArray(n) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** Linear-interpolation resampling; good enough for speech between 8 and 48 kHz. */
    fun resample(samples: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || samples.isEmpty()) return samples
        val outSize = (samples.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        return ShortArray(outSize) { i ->
            val pos = i * step
            val left = pos.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val frac = pos - left
            (samples[left] * (1 - frac) + samples[right] * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Averages interleaved channels down to mono. */
    fun downmix(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        return ShortArray(samples.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += samples[i * channels + c]
            (sum / channels).toShort()
        }
    }

    /** RMS of [samples] scaled to 0..1. */
    fun level(samples: ShortArray, count: Int = samples.size): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return (kotlin.math.sqrt(sum / count) / 32768.0).toFloat()
    }

    fun wav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le32(v: Int) { for (k in 0..3) out.write(v ushr (8 * k) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
        val block = channels * 2
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(sampleRate)
        le32(sampleRate * block); le16(block); le16(16)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** Decoded 16-bit PCM WAV: mono samples and their rate. */
    class Decoded(val samples: ShortArray, val sampleRate: Int)

    /** Reads a 16-bit PCM WAV (any channel count, tolerant of streaming sizes); null when it is not one. */
    fun parseWav(bytes: ByteArray): Decoded? {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
            String(bytes, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        fun u32(at: Int): Long = (0..3).fold(0L) { acc, k -> acc or ((bytes[at + k].toLong() and 0xFF) shl (8 * k)) }
        fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8)
        var offset = 12
        var rate = 0
        var channels = 1
        var bits = 16
        var format = 1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            val length = u32(offset + 4)
            val start = offset + 8
            if (id == "fmt " && start + 16 <= bytes.size) {
                format = u16(start)
                channels = u16(start + 2).coerceAtLeast(1)
                rate = u32(start + 4).toInt()
                bits = u16(start + 14)
            } else if (id == "data") {
                if (rate <= 0 || bits != 16 || (format != 1 && format != 0xFFFE)) return null
                val end = if (length == 0xFFFF_FFFFL || length == 0L || start + length > bytes.size) bytes.size else (start + length).toInt()
                val pcm = toSamples(bytes.copyOfRange(start, end))
                return Decoded(downmix(pcm, channels), rate)
            }
            offset = (start + length + (length and 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return null
    }
}
