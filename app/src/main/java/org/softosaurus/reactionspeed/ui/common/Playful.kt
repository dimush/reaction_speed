package org.softosaurus.reactionspeed.ui.common

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.softosaurus.reactionspeed.ReactionSpeedApp
import org.softosaurus.reactionspeed.audio.AppAudio
import org.softosaurus.reactionspeed.audio.Sound

/** The process-wide sound layer, created in [ReactionSpeedApp]. */
@Composable
fun rememberAppAudio(): AppAudio {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as ReactionSpeedApp).audio }
}

/**
 * A click sound for primary buttons, ready to drop in front of the real action:
 * `onClick = { click(); onStart() }`.
 *
 * The returned lambda is remembered, so putting it on a button costs nothing per recomposition, and
 * it obeys the "Sound effects" switch through [org.softosaurus.reactionspeed.audio.SoundBank]
 * itself — no screen has to check a preference to make a button click.
 */
@Composable
fun rememberClickSound(): () -> Unit {
    val audio = rememberAppAudio()
    return remember(audio) { { audio.sounds.play(Sound.UI_CLICK) } }
}

/**
 * False when the system's "remove animations" accessibility setting is on
 * (`ANIMATOR_DURATION_SCALE == 0`).
 *
 * Decorative motion — the bobbing mascots, the confetti, the entrance springs — is skipped when
 * this is false, and the affected composables draw their final, resting state instead. Nothing that
 * carries information is ever skipped, and in particular the playfield's target is not decoration:
 * it appears exactly as before whatever this setting says.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        } catch (e: Exception) {
            // A device that will not answer gets the animations; they are the default experience.
            true
        }
    }
}

/** The playful corner radius shared by the cards and hero art of the redesigned screens. */
val PlayfulCornerShape = RoundedCornerShape(24.dp)
