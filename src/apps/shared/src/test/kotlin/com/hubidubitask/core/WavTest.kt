package com.hubidubitask.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavTest {
    private val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)

    @Test fun `a recording comes back as it went in`() {
        val wav = Wav.of(samples, 16000)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        val back = Wav.read(wav)
        assertEquals(16000, back.rate)
        assertContentEquals(samples, back.samples)
    }

    @Test fun `other chunks before the sound are stepped over`() {
        val plain = Wav.of(samples, 16000)
        val list = ByteBuffer.allocate(8 + 5 + 1).order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray()).putInt(5).put("abcde".toByteArray()).put(0).array()
        // RIFF header and fmt, then a LIST chunk of odd size, then data.
        val wav = plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)
        assertContentEquals(samples, Wav.read(wav).samples)
    }

    @Test fun `stereo and other files are refused`() {
        val stereo = Wav.of(samples, 16000).also { it[22] = 2 }
        assertFailsWith<IllegalArgumentException> { Wav.read(stereo) }
        assertFailsWith<IllegalArgumentException> { Wav.read("hello world!".toByteArray()) }
    }

    @Test fun `a quiet room is silent, a word in it is not`() {
        val rate = 16000
        val room = ShortArray(rate * 3) { ((it * 7919) % 61 - 30).toShort() }
        kotlin.test.assertTrue(Wav.silent(room, rate))
        val word = room.copyOf().also { for (i in rate until rate + rate / 5) it[i] = (3000 * Math.sin(i / 8.0)).toInt().toShort() }
        kotlin.test.assertFalse(Wav.silent(word, rate))
        kotlin.test.assertTrue(Wav.silent(ShortArray(0), rate))
    }

    @Test fun `samples become floats from -1 to 1`() {
        val f = Wav.floats(shortArrayOf(0, Short.MIN_VALUE, 16384))
        assertEquals(listOf(0f, -1f, 0.5f), f.toList())
    }
}
