package org.softosaurus.reactionspeed.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.softosaurus.reactionspeed.ReactionSpeedApp
import org.softosaurus.reactionspeed.data.ResultsRepository

/** The process-wide results store, created in [ReactionSpeedApp]. */
@Composable
fun rememberResults(): ResultsRepository {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as ReactionSpeedApp).results }
}

/** The hosting activity, needed by Play Games and by the UMP privacy form. */
@Composable
fun rememberActivity(): Activity? {
    val context = LocalContext.current
    return remember(context) { context.findActivity() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
