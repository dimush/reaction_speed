package org.softosaurus.reactionspeed.ui.result

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.game.Verdict
import org.softosaurus.reactionspeed.ui.common.rememberAnimationsEnabled

/** The mascot for a [Verdict] tier: sloth, turtle, rabbit, cheetah, lightning hero. */
private val MASCOT_BY_TIER = intArrayOf(
    R.drawable.mascot_tier_1_sloth,
    R.drawable.mascot_tier_2_turtle,
    R.drawable.mascot_tier_3_rabbit,
    R.drawable.mascot_tier_4_cheetah,
    R.drawable.mascot_tier_5_hero,
)

/**
 * The verdict mascot, springing in and then quietly bobbing.
 *
 * `contentDescription` is `null` on purpose: the tier is already spelled out, twice, by the verdict
 * headline right beside it and by the score above that. Announcing "a cheetah wearing sunglasses"
 * in between would add nothing a screen-reader user can act on.
 *
 * With "remove animations" on, the mascot is simply there at full size — the entrance is a
 * flourish, not a reveal.
 */
@Composable
fun TierMascot(tier: Int, modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val resId = MASCOT_BY_TIER[(tier - 1).coerceIn(0, MASCOT_BY_TIER.lastIndex)]

    val entrance = remember(resId) { Animatable(if (animate) 0.2f else 1f) }
    LaunchedEffect(resId, animate) {
        if (!animate) {
            entrance.snapTo(1f)
            return@LaunchedEffect
        }
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val transition = rememberInfiniteTransition(label = "tierMascot")
    val bob by if (animate) {
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bob",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier.graphicsLayer {
            val scale = entrance.value
            scaleX = scale
            scaleY = scale
            // The bob only starts mattering once the spring has settled, so the two never fight.
            translationY = -bob * BOB_PX * scale
            rotationZ = bob * TILT_DEGREES * scale
        },
    )
}

private const val BOB_PX = 6f
private const val TILT_DEGREES = 1.8f
