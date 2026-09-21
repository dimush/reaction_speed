package org.softosaurus.reactionspeed.audio

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WAV header reader.
 *
 * This is worth testing carefully because **its failure mode is silent**. A duration of 0 does not
 * crash, log or sound wrong on its own: it makes
 * [SoundBank.playVoice] set `voiceBusyUntil = now`, which lets a second spoken line start on top of
 * the first, and it collapses every wait in [AppAudio.playResultFanfare] to nothing. Neither can be
 * noticed by reading the code or by looking at a screenshot, so it is pinned here instead.
 */
class WavTest {

    @Test
    fun `reads a plain 16-bit mono header`() {
        val bytes = wav(sampleRate = 22_050, channels = 1, bits = 16, frames = 22_050)
        val format = Wav.readFormat(ByteArrayInputStream(bytes))

        assertNotNull(format)
        assertEquals(22_050, format!!.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(2, format.bytesPerFrame)
        assertEquals(22_050, format.frameCount)
        assertEquals(1_000L, format.durationMs)
    }

    @Test
    fun `stereo frames are counted once, not twice`() {
        val format = Wav.readFormat(
            ByteArrayInputStream(wav(sampleRate = 44_100, channels = 2, bits = 16, frames = 44_100)),
        )
        assertEquals(4, format!!.bytesPerFrame)
        assertEquals(44_100, format.frameCount)
        assertEquals(1_000L, format.durationMs)
    }

    @Test
    fun `an extra chunk before data is skipped, not read as audio`() {
        // The reason this reader walks the chunk list instead of skipping a fixed 44 bytes.
        val bytes = wav(
            sampleRate = 22_050,
            channels = 1,
            bits = 16,
            frames = 11_025,
            extraChunks = listOf("LIST" to ByteArray(30) { 0x41 }),
        )
        val format = Wav.readFormat(ByteArrayInputStream(bytes))
        assertEquals(11_025, format!!.frameCount)
        assertEquals(500L, format.durationMs)
    }

    @Test
    fun `an odd-sized chunk is padded to an even boundary`() {
        // RIFF pads odd chunks with a trailing byte that is not part of the chunk's size.
        val bytes = wav(
            sampleRate = 22_050,
            channels = 1,
            bits = 16,
            frames = 2_205,
            extraChunks = listOf("fact" to ByteArray(7) { 0x2A }),
        )
        val format = Wav.readFormat(ByteArrayInputStream(bytes))
        assertEquals(2_205, format!!.frameCount)
        assertEquals(100L, format.durationMs)
    }

    @Test
    fun `readPcm returns the sample bytes verbatim`() {
        val payload = ByteArray(200) { (it * 7).toByte() }
        val bytes = wav(22_050, 1, 16, frames = 100, data = payload)

        val (format, pcm) = Wav.readPcm(ByteArrayInputStream(bytes))!!
        assertEquals(100, format.frameCount)
        assertArrayEquals(payload, pcm)
    }

    @Test
    fun `a truncated data chunk is rejected rather than half-read`() {
        val bytes = wav(22_050, 1, 16, frames = 100)
        val truncated = bytes.copyOf(bytes.size - 50)
        assertNull(Wav.readPcm(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `non-RIFF and non-PCM input is rejected`() {
        assertNull(Wav.readFormat(ByteArrayInputStream("not a wav at all".toByteArray())))
        assertNull(Wav.readFormat(ByteArrayInputStream(ByteArray(0))))
        // Format tag 3 is IEEE float, which SoundPool would take but this reader deliberately will
        // not: the AudioTrack path assumes 16-bit integer PCM.
        assertNull(Wav.readFormat(ByteArrayInputStream(wav(22_050, 1, 16, 100, formatTag = 3))))
    }

    /**
     * Pins a real shipped asset.
     *
     * `tool/audio/README.md` documents `music_menu` as 12 bars at 100 BPM, i.e. exactly 28.8 s, and
     * the loop is written into a buffer of exactly that many samples so that
     * `AudioTrack.setLoopPoints(0, frameCount, -1)` wraps without a click. If a regeneration ever
     * changes the sample rate, the channel count or the chunk layout, the music would loop at the
     * wrong point or not load at all — and nobody would find out by looking.
     */
    @Test
    fun `the shipped menu loop parses to its documented length`() {
        val file = resource("music_menu.wav")
        val format = file.inputStream().buffered().use { Wav.readFormat(it) }

        assertNotNull("could not parse ${file.path}", format)
        assertEquals(22_050, format!!.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(28_800L, format.durationMs)
    }

    @Test
    fun `every shipped voice line has a usable duration`() {
        // A zero here is the silent failure the whole class exists to catch.
        for (name in VOICE_FILES) {
            val file = resource(name)
            val format = file.inputStream().buffered().use { Wav.readFormat(it) }
            assertNotNull("could not parse $name", format)
            assertTrue("$name reports ${format!!.durationMs} ms", format.durationMs in 200L..5_000L)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun resource(name: String): File {
        // The unit test's working directory is the module or the project root, depending on how it
        // is invoked; try both rather than pinning one and breaking in the other.
        val candidates = listOf(
            File("src/main/res/raw/$name"),
            File("app/src/main/res/raw/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$name not found; looked in ${candidates.map { it.absolutePath }}")
    }

    /** Builds a RIFF/WAVE byte stream, optionally with extra chunks before `data`. */
    private fun wav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        frames: Int,
        data: ByteArray? = null,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
        formatTag: Int = 1,
    ): ByteArray {
        val bytesPerFrame = channels * (bits / 8)
        val payload = data ?: ByteArray(frames * bytesPerFrame)

        val body = ArrayList<Byte>()
        fun ascii(s: String) = s.forEach { body.add(it.code.toByte()) }
        fun le32(v: Int) = (0..3).forEach { body.add(((v shr (it * 8)) and 0xFF).toByte()) }
        fun le16(v: Int) = (0..1).forEach { body.add(((v shr (it * 8)) and 0xFF).toByte()) }

        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(formatTag)
        le16(channels)
        le32(sampleRate)
        le32(sampleRate * bytesPerFrame)
        le16(bytesPerFrame)
        le16(bits)
        for ((id, content) in extraChunks) {
            ascii(id)
            le32(content.size)
            content.forEach { body.add(it) }
            if (content.size % 2 == 1) body.add(0)
        }
        ascii("data")
        le32(payload.size)
        payload.forEach { body.add(it) }

        val header = ArrayList<Byte>()
        "RIFF".forEach { header.add(it.code.toByte()) }
        (0..3).forEach { header.add(((body.size shr (it * 8)) and 0xFF).toByte()) }
        return (header + body).toByteArray()
    }

    private companion object {
        val VOICE_FILES = listOf(
            "voice_ready.wav",
            "voice_too_early.wav",
            "voice_tier_1.wav",
            "voice_tier_2.wav",
            "voice_tier_3.wav",
            "voice_tier_4.wav",
            "voice_tier_5.wav",
            "voice_new_record.wav",
        )
    }
}
