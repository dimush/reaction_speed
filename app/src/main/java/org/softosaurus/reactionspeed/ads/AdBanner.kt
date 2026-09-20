package org.softosaurus.reactionspeed.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.delay

/**
 * How long a banner slot stays empty after the screen carrying it first composes.
 *
 * The result screen appears the instant the tenth target is tapped, and the banner sits in the
 * bottom strip — plausibly right under the finger that just made the last tap. A tap that is still
 * in flight, or a reflexive second tap, must not be able to land on a freshly drawn ad. The space is
 * reserved from the start (see [AdBanner]) so nothing jumps when the ad finally appears.
 */
private const val ARMING_DELAY_MS = 1_000L

/** Lower bound for an adaptive banner; the SDK itself never goes below this. */
private const val MIN_BANNER_HEIGHT_DP = 50

/** Used when the SDK cannot size a banner at all — deliberately generous. */
private const val FALLBACK_BANNER_HEIGHT_DP = 60

/**
 * The single [AdView] of one activity.
 *
 * One banner is created and loaded once per activity and then moved between screens, instead of
 * every screen minting an `AdView` and firing its own ad request. Besides the wasted requests
 * (and the impressions they burn), a per-screen view made the banner flicker on every navigation.
 *
 * The host is *not* responsible for consent: [AdBanner] only asks for [view] once
 * [AdsManager.canRequestAds] is true, so no request can precede the UMP flow.
 */
class AdBannerHost(activity: Activity) {

    private var activityRef: Activity? = activity
    private var adView: AdView? = null

    /** Height the banner will occupy, in dp. Known before any ad exists, so space can be reserved. */
    val heightDp: Int = adaptiveBannerHeightDp(activity)

    /**
     * The banner, created and loaded on first use. Returns `null` once [destroy] has run, so a
     * composable that is torn down late cannot resurrect a dead view.
     */
    fun view(): AdView? {
        val activity = activityRef ?: return null
        adView?.let { return it }
        return AdView(activity).apply {
            setAdSize(adaptiveBannerAdSize(activity))
            adUnitId = AdsManager.bannerAdUnitId
            loadAd(AdRequest.Builder().build())
            adView = this
        }
    }

    fun resume() {
        adView?.resume()
    }

    fun pause() {
        adView?.pause()
    }

    /** Detaches the banner from whatever screen last showed it and releases it for good. */
    fun destroy() {
        adView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        adView = null
        activityRef = null
    }
}

/**
 * The [AdBannerHost] of the current activity. Defaults to `null`, which makes [AdBanner] a no-op —
 * previews and tests need no ads, and nothing crashes for want of a provider.
 */
val LocalAdBannerHost = compositionLocalOf<AdBannerHost?> { null }

/**
 * Creates the one [AdBannerHost] for this activity and destroys it when the host composition goes
 * away. Call once, as high as the activity's content (see `ReactionSpeedNavHost`).
 */
@Composable
fun rememberAdBannerHost(): AdBannerHost? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return null
    val host = remember(activity) { AdBannerHost(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> host.resume()
                Lifecycle.Event.ON_PAUSE -> host.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            host.destroy()
        }
    }
    return host
}

/**
 * The anchored adaptive banner slot.
 *
 * Emits nothing at all until [AdsManager.canRequestAds] is true, so no ad is ever requested before
 * consent is resolved. Once it is, the slot **reserves its full height immediately** and stays
 * empty for [ARMING_DELAY_MS]: the layout never jumps, and a tap that was on its way to the screen
 * underneath cannot hit an ad that materialised under it (see [ARMING_DELAY_MS]).
 *
 * The [AdView] itself belongs to [LocalAdBannerHost] and is shared by every screen; this composable
 * only borrows it, detaching it from the previous screen on the way in.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val canRequestAds by AdsManager.canRequestAds.collectAsState()
    val host = LocalAdBannerHost.current ?: return
    if (!canRequestAds) return

    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(host) {
        delay(ARMING_DELAY_MS)
        armed = true
    }

    Box(modifier = modifier.fillMaxWidth().height(host.heightDp.dp)) {
        if (armed) {
            val adView = host.view() ?: return@Box
            AndroidView(
                factory = {
                    // Two screens overlap during a navigation transition, and both want the one
                    // banner. Whoever composes last takes it; without this the second attach would
                    // throw for a view that still has a parent.
                    (adView.parent as? ViewGroup)?.removeView(adView)
                    adView
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Height in dp of the anchored adaptive banner for the current window width.
 *
 * Also the width of the band at the bottom of the playfield that the game screen declares unsafe,
 * so that the no-spawn band and the reserved slot are the same number and can never drift apart.
 */
fun adaptiveBannerHeightDp(context: Context): Int =
    runCatching { adaptiveBannerAdSize(context).height }
        .getOrDefault(0)
        .takeIf { it >= MIN_BANNER_HEIGHT_DP }
        ?: FALLBACK_BANNER_HEIGHT_DP

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
