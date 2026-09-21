package org.softosaurus.reactionspeed.audio

import java.io.InputStream

/**
 * Header of a PCM WAV file.
 *
 * @param sampleRate frames per second
 * @param channels 1 (mono) or 2 (stereo)
 * @param bitsPerSample 8 or 16
 * @param dataBytes length of the `data` chunk in bytes
 */
data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataBytes: Int,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)

    val frameCount: Int get() = if (bytesPerFrame == 0) 0 else dataBytes / bytesPerFrame

    /** Playing time in milliseconds, rounded up so a scheduler never cuts a line short. */
    val durationMs: Long
        get() = if (sampleRate <= 0) 0L else (frameCount.toLong() * 1000L + sampleRate - 1) / sampleRate
}

/**
 * Minimal RIFF/WAVE reader for the assets in `res/raw`.
 *
 * Everything the generators in `tool/audio` produce is 16-bit PCM, but the chunk layout is *not*
 * assumed to be the classic 44-byte header: the reader walks the chunk list, so an extra `LIST` or
 * `fact` chunk (which a future generator or a hand-edited file may well carry) is skipped instead
 * of being read as audio. Nothing here hardcodes a duration or an offset.
 *
 * Both entry points consume the stream from its current position and never close it.
 */
object Wav {

    private const val RIFF = 0x52494646 // "RIFF", big-endian read
    private const val WAVE = 0x57415645 // "WAVE"
    private const val FMT = 0x666D7420 // "fmt "
    private const val DATA = 0x64617461 // "data"

    /** Format tag 1 = uncompressed PCM; anything else is rejected. */
    private const val PCM_FORMAT_TAG = 1

    /**
     * Reads just the header, stopping at the start of the sample data.
     *
     * @return the format, or `null` if the stream is not a PCM WAV
     */
    fun readFormat(input: InputStream): WavFormat? = parse(input, readData = false)?.first

    /**
     * Reads the header **and** the whole `data` chunk.
     *
     * @return the format and the raw little-endian PCM bytes, or `null` if the stream is not a PCM
     *   WAV or was truncated
     */
    fun readPcm(input: InputStream): Pair<WavFormat, ByteArray>? {
        val (format, data) = parse(input, readData = true) ?: return null
        return format to (data ?: return null)
    }

    private fun parse(input: InputStream, readData: Boolean): Pair<WavFormat, ByteArray?>? {
        if (readBigEndianInt(input) != RIFF) return null
        readLittleEndianInt(input) // RIFF size; unreliable in streamed files, deliberately ignored
        if (readBigEndianInt(input) != WAVE) return null

        var sampleRate = 0
        var channels = 0
        var bits = 0

        while (true) {
            val id = readBigEndianIntOrNull(input) ?: return null
            val size = readLittleEndianIntOrNull(input) ?: return null
            if (size < 0) return null
            when (id) {
                FMT -> {
                    if (size < 16) return null
                    if (readLittleEndianShort(input) != PCM_FORMAT_TAG) return null
                    channels = readLittleEndianShort(input)
                    sampleRate = readLittleEndianInt(input)
                    readLittleEndianInt(input) // byte rate, derivable
                    readLittleEndianShort(input) // block align, derivable
                    bits = readLittleEndianShort(input)
                    skipFully(input, (size - 16).toLong() + (size and 1))
                }

                DATA -> {
                    if (sampleRate <= 0 || channels <= 0 || bits <= 0) return null
                    val format = WavFormat(sampleRate, channels, bits, size)
                    if (!readData) return format to null
                    val bytes = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = input.read(bytes, read, size - read)
                        if (n < 0) return null
                        read += n
                    }
                    return format to bytes
                }

                // Odd-sized chunks are padded to an even boundary by the RIFF spec.
                else -> skipFully(input, size.toLong() + (size and 1))
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to reading the bytes away.
                if (input.read() < 0) return
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    private fun readBigEndianIntOrNull(input: InputStream): Int? {
        val a = input.read()
        if (a < 0) return null
        val b = input.read()
        val c = input.read()
        val d = input.read()
        if (b < 0 || c < 0 || d < 0) return null
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun readBigEndianInt(input: InputStream): Int = readBigEndianIntOrNull(input) ?: 0

    private fun readLittleEndianIntOrNull(input: InputStream): Int? {
        val a = input.read()
        if (a < 0) return null
        val b = input.read()
        val c = input.read()
        val d = input.read()
        if (b < 0 || c < 0 || d < 0) return null
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }

    private fun readLittleEndianInt(input: InputStream): Int = readLittleEndianIntOrNull(input) ?: 0

    private fun readLittleEndianShort(input: InputStream): Int {
        val a = input.read()
        val b = input.read()
        if (a < 0 || b < 0) return 0
        return (b shl 8) or a
    }
}
