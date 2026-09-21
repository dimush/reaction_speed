package org.softosaurus.reactionspeed.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.audio.AppAudio
import org.softosaurus.reactionspeed.audio.Sound
import org.softosaurus.reactionspeed.ui.common.rememberAnimationsEnabled

/**
 * The home dinosaur: breathing, bobbing, tilting, and delighted to be poked.
 *
 * The idle motion is three oscillations of deliberately unrelated periods — a bob, a tilt and a
 * rare little hop — so the loop never visibly repeats. Tapping the mascot springs it into a bounce
 * and gets a noise out of it; that is the whole easter egg, and it is the one place in the app
 * allowed to play `vox_pop_hello_*`, which `tool/audio/README.md` bars from the playfield because
 * its onset is too soft to time against.
 *
 * When the system's "remove animations" setting is on, the mascot simply stands still — including
 * on a tap, which still makes its sound. It is decoration; it is never information.
 */
@Composable
fun HomeMascot(audio: AppAudio, modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val scope = rememberCoroutineScope()
    val pokeScale = remember { Animatable(1f) }
    val helloAlternates = remember { intArrayOf(0) }

    val transition = rememberInfiniteTransition(label = "mascot")
    val bob by transition.animateFloatOrStill(
        animate = animate,
        initialValue = -1f,
        targetValue = 1f,
        spec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )
    val tilt by transition.animateFloatOrStill(
        animate = animate,
        initialValue = -1f,
        targetValue = 1f,
        spec = infiniteRepeatable(
            animation = tween(durationMillis = 3_700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tilt",
    )
    // Flat for most of its five seconds, then one quick hop — the "occasionally" of the brief.
    val hop by transition.animateFloatOrStill(
        animate = animate,
        initialValue = 0f,
        targetValue = 0f,
        spec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5_000
                0f at 0
                0f at 4_100
                1f at 4_320
                0f at 4_560
                0.35f at 4_700
                0f at 4_840
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "hop",
    )

    val description = stringResource(R.string.home_mascot_description)
    Image(
        painter = painterResource(R.drawable.mascot_home_dino),
        contentDescription = description,
        modifier = modifier
            .graphicsLayer {
                val lift = bob * BOB_PX + hop * HOP_PX
                translationY = -lift
                rotationZ = tilt * TILT_DEGREES
                // Squashing very slightly at the bottom of the bob sells the weight of the thing.
                scaleX = pokeScale.value * (1f + bob * 0.012f)
                scaleY = pokeScale.value * (1f - bob * 0.012f)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = description,
            ) {
                // Alternate the two greetings so a child hammering on the dino gets variety.
                val greeting = if (helloAlternates[0]++ % 3 == 2) {
                    Sound.VOX_YAY
                } else {
                    Sound.HELLOS[helloAlternates[0] % Sound.HELLOS.size]
                }
                audio.sounds.play(greeting)
                if (!animate) return@clickable
                scope.launch {
                    pokeScale.snapTo(0.86f)
                    pokeScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioHighBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                }
            },
    )
}

/**
 * [androidx.compose.animation.core.InfiniteTransition.animateFloat], or a constant when decorative
 * animation is switched off — the transition is then never started at all, so nothing recomposes on
 * a frame clock the user asked us not to use.
 */
@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatOrStill(
    animate: Boolean,
    initialValue: Float,
    targetValue: Float,
    spec: InfiniteRepeatableSpec<Float>,
    label: String,
): androidx.compose.runtime.State<Float> =
    if (animate) {
        animateFloat(initialValue, targetValue, spec, label)
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

/** Vertical travel of the idle bob, px. */
private const val BOB_PX = 7f

/** Extra lift of the occasional hop, px. */
private const val HOP_PX = 26f

private const val TILT_DEGREES = 2.6f
