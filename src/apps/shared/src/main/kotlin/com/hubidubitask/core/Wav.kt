package com.hubidubitask.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM as a WAV file, and back: how a voice recording waits for
 * Whisper on the phone, and how it is kept when it can't be written out. The
 * computer keeps its voice memos the same way.
 */
object Wav {
    class Audio(val samples: ShortArray, val rate: Int)

    fun of(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in samples) b.putShort(s)
        return b.array()
    }

    /** The samples of a 16-bit mono PCM WAV; anything else is refused. */
    fun read(bytes: ByteArray): Audio {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): String = ByteArray(4).also { b.get(it) }.decodeToString()
        require(bytes.size >= 12 && tag() == "RIFF" && b.getInt() >= 0 && tag() == "WAVE") { "not a WAV file" }
        var rate = 0
        while (b.remaining() >= 8) {
            val id = tag()
            val size = b.getInt()
            require(size >= 0 && size <= b.remaining()) { "a broken WAV file" }
            val start = b.position()
            when (id) {
                "fmt " -> {
                    val format = b.getShort().toInt(); val channels = b.getShort().toInt()
                    rate = b.getInt(); b.getInt(); b.getShort(); val bits = b.getShort().toInt()
                    require(format == 1 && channels == 1 && bits == 16) { "not 16-bit mono PCM" }
                }
                "data" -> {
                    require(rate > 0) { "a WAV file without its format" }
                    val samples = ShortArray(size / 2)
                    b.asShortBuffer().get(samples)
                    return Audio(samples, rate)
                }
            }
            b.position(start + size + (size and 1))
        }
        throw IllegalArgumentException("a WAV file without sound")
    }

    /**
     * Whether nothing in it is as loud as speech: no tenth of a second rises
     * above a quiet room. Whisper, given silence, makes words up.
     */
    fun silent(samples: ShortArray, rate: Int): Boolean {
        val window = maxOf(1, rate / 10)
        var i = 0
        while (i < samples.size) {
            val end = minOf(samples.size, i + window)
            var sum = 0.0
            for (j in i until end) sum += samples[j].toDouble() * samples[j]
            if (Math.sqrt(sum / (end - i)) > SPEECH_RMS) return false
            i = end
        }
        return true
    }

    /** The loudness (RMS, of 32768) below which a stretch holds no speech. */
    private const val SPEECH_RMS = 300.0

    /** As Whisper takes them: -1 to 1. */
    fun floats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }
}
