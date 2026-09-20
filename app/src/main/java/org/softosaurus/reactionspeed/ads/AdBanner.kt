package org.softosaurus.reactionspeed.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * A full-width, anchored adaptive banner ad.
 *
 * Only composes (and loads) an ad once [AdsManager.canRequestAds] is true, to respect consent.
 * While [visible] is false the banner is paused and its space collapses (e.g. while a reaction
 * series is running, per store policy against accidental taps near live gameplay controls).
 *
 * Call this composable wherever the app wants a banner slot to appear (e.g. bottom of a screen);
 * it manages its own [AdView] lifecycle (pause/resume/destroy) via the local lifecycle owner.
 */
@Composable
fun AdBanner(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val canRequestAds by AdsManager.canRequestAds.collectAsState()
    if (!canRequestAds) return

    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current

    val adSize = remember(context) { adaptiveBannerAdSize(context) }
    val bannerHeightDp = remember(adSize) { adSize.height.coerceAtLeast(50).dp }

    val adView = remember(activity) {
        AdView(activity).apply {
            setAdSize(adSize)
            adUnitId = AdsManager.bannerAdUnitId
            loadAd(AdRequest.Builder().build())
        }
    }

    // Reinstalled whenever `visible` changes, so the observer always resumes/pauses against the
    // current value rather than one captured on first composition.
    DisposableEffect(lifecycleOwner, adView, visible) {
        if (visible) adView.resume() else adView.pause()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (visible) adView.resume()
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    if (visible) {
        Box(modifier = modifier.fillMaxWidth().height(bannerHeightDp)) {
            AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth())
        }
    }
    // When not visible, nothing is emitted: the slot collapses entirely rather than reserving
    // space, since the game screen hides the banner specifically to reclaim that area during play.
}

private fun adaptiveBannerAdSize(context: Context): AdSize {
    val displayMetrics = context.resources.displayMetrics
    val adWidthPixels = displayMetrics.widthPixels
    val density = displayMetrics.density
    val adWidthDp = (adWidthPixels / density).toInt()
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
