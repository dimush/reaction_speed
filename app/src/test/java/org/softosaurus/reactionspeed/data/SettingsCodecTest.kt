package org.softosaurus.reactionspeed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration of the sound switches, which has to survive three different versions of the
 * preferences file. See [SettingsCodec] for the rules being pinned down here.
 */
class SettingsCodecTest {

    @Test
    fun `a fresh install gets everything on`() {
        assertEquals(GameSettings(), SettingsCodec.read(emptyMap<String, Any?>()))
    }

    @Test
    fun `the current keys win over the legacy ones`() {
        val settings = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_SOUND_EFFECTS to false,
                SettingsCodec.KEY_MUSIC to false,
                SettingsCodec.KEY_VOICE to false,
                SettingsCodec.KEY_VIBRATION to false,
                // Stale values from an older version must be ignored, not merged.
                SettingsCodec.KEY_LEGACY_TARGET_SOUNDS to true,
                SettingsCodec.KEY_LEGACY_STONE_SOUNDS to true,
            ),
        )
        assertEquals(GameSettings(false, false, false, false), settings)
    }

    @Test
    fun `effects stay on when only one of the two legacy switches was off`() {
        val targetOnly = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_LEGACY_TARGET_SOUNDS to true,
                SettingsCodec.KEY_LEGACY_STONE_SOUNDS to false,
            ),
        )
        assertTrue(targetOnly.soundEffects)

        val stoneOnly = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_LEGACY_TARGET_SOUNDS to false,
                SettingsCodec.KEY_LEGACY_STONE_SOUNDS to true,
            ),
        )
        assertTrue(stoneOnly.soundEffects)
    }

    @Test
    fun `effects go off only when both legacy switches were off`() {
        val settings = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_LEGACY_TARGET_SOUNDS to false,
                SettingsCodec.KEY_LEGACY_STONE_SOUNDS to false,
            ),
        )
        assertFalse(settings.soundEffects)
        // The two new switches are new: they default on whatever the old file said about sound.
        assertTrue(settings.music)
        assertTrue(settings.voice)
    }

    @Test
    fun `a missing legacy switch counts as its old default`() {
        // 3.x defaulted both to true, so one stored `false` alone still leaves some sound wanted.
        val settings = SettingsCodec.read(mapOf(SettingsCodec.KEY_LEGACY_STONE_SOUNDS to false))
        assertTrue(settings.soundEffects)
    }

    @Test
    fun `the legacy vibration key is honoured under either spelling`() {
        assertFalse(SettingsCodec.read(mapOf(SettingsCodec.KEY_VIBRATION to false)).vibration)
        assertFalse(SettingsCodec.read(mapOf(SettingsCodec.KEY_LEGACY_VIBRATOR to false)).vibration)
        // The current key wins when both are present.
        val both = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_VIBRATION to true,
                SettingsCodec.KEY_LEGACY_VIBRATOR to false,
            ),
        )
        assertTrue(both.vibration)
    }

    @Test
    fun `garbage values fall back to the defaults`() {
        val settings = SettingsCodec.read(
            mapOf(
                SettingsCodec.KEY_SOUND_EFFECTS to "yes please",
                SettingsCodec.KEY_MUSIC to 17,
                SettingsCodec.KEY_VOICE to null,
            ),
        )
        assertEquals(GameSettings(), settings)
    }

    @Test
    fun `booleans stored as strings are understood`() {
        val settings = SettingsCodec.read(mapOf(SettingsCodec.KEY_MUSIC to "false"))
        assertFalse(settings.music)
    }
}
