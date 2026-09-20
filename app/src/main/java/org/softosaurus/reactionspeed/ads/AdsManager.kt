package org.softosaurus.reactionspeed.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.softosaurus.reactionspeed.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GDPR / US-states consent (Google User Messaging Platform) followed by a one-time
 * Mobile Ads SDK initialization.
 *
 * Call [start] from the app's entry point Activity, from both `onCreate` and `onStart`. It is safe
 * to call repeatedly - the underlying consent/init work only happens once per process. A flow that
 * genuinely failed (offline at launch, an activity that died before the form could be shown)
 * re-arms itself, so the next call retries instead of leaving the process adless forever.
 *
 * Ad-serving surfaces (e.g. [AdBanner]) must observe [canRequestAds] and must not attempt
 * to load an ad before it becomes true - doing so would violate consent requirements and
 * risk requesting ads before the Mobile Ads SDK is initialized.
 */
object AdsManager {

    private const val TAG = "AdsManager"

    /** Test banner unit id supplied by Google for debug builds - always fills, never billed. */
    private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"

    /** Production banner ad unit id. */
    private const val RELEASE_BANNER_AD_UNIT_ID = "ca-app-pub-1665272374483034/3757059300"

    /** Ad unit id to use for the banner ad, matching the current build type. */
    val bannerAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_BANNER_AD_UNIT_ID else RELEASE_BANNER_AD_UNIT_ID

    private val mobileAdsInitialized = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _canRequestAds = MutableStateFlow(false)
    /** True once consent has been resolved (or wasn't required) and ads may be requested. */
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    /** True when the user must be able to re-open the privacy options form (e.g. a settings entry). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var consentInformation: ConsentInformation? = null
    private var appContext: Context? = null
    private val started = AtomicBoolean(false)

    /**
     * Runs the UMP consent flow (requesting an update, then showing a consent form if one is
     * required) and initializes the Mobile Ads SDK once ads may be requested. Idempotent: safe
     * to call multiple times (e.g. from onCreate and onResume) - subsequent calls are no-ops
     * once the flow has been started for this process.
     */
    fun start(activity: Activity) {
        if (!started.compareAndSet(false, true)) return
        appContext = activity.applicationContext

        // For local testing against a specific consent geography, add a
        // .setConsentDebugSettings(com.google.android.ump.ConsentDebugSettings.Builder(activity)...)
        // here - omitted by default so production behavior is geography-accurate.
        val params = ConsentRequestParameters.Builder().build()

        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = consentInfo

        // Already resolved from a previous session: don't wait for the network round trip
        // before allowing ads / initializing the SDK.
        if (consentInfo.canRequestAds()) {
            onConsentResolved(consentInfo)
        }

        // The callbacks below can arrive long after this activity is gone (a rotation, a back
        // press, a process that stayed warm). Showing a form on a dead activity throws, so the
        // reference is weak and re-checked at use.
        val host = WeakReference(activity)

        runCatching {
            consentInfo.requestConsentInfoUpdate(
                activity,
                params,
                {
                    val current = host.liveActivity()
                    if (current == null) {
                        // Nothing to show a form on. Publish whatever state we have and let the
                        // next Activity run the flow again.
                        finishAttempt(consentInfo)
                    } else {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(current) { error ->
                            // Form dismissed (or none was required) - check the latest state.
                            if (error != null) Log.w(TAG, "consent form failed: ${error.message}")
                            finishAttempt(consentInfo)
                        }
                    }
                },
                { error ->
                    // Consent info unavailable (e.g. offline). If ads were already allowed from a
                    // prior session this still lets the app proceed; otherwise ads stay disabled
                    // and the next start() retries.
                    Log.w(TAG, "consent info update failed: ${error.message}")
                    finishAttempt(consentInfo)
                }
            )
        }.onFailure {
            Log.w(TAG, "requestConsentInfoUpdate threw", it)
            started.set(false)
        }
    }

    /**
     * Publishes the resolved consent state and, when ads are *still* not allowed, re-arms [started]
     * so that the next [start] call gets another attempt.
     *
     * Without this the one-shot flag burned on the first failure and a process that lost the very
     * first consent round trip (offline at launch, say) would never show an ad again, however long
     * it lived. The flag is only re-armed on genuine failure: for a user whose consent is already
     * settled it stays set, so `onStart` does not re-run the flow on every foregrounding.
     */
    private fun finishAttempt(consentInfo: ConsentInformation) {
        onConsentResolved(consentInfo)
        if (!runCatching { consentInfo.canRequestAds() }.getOrDefault(false)) {
            started.set(false)
        }
    }

    private fun WeakReference<Activity>.liveActivity(): Activity? =
        get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Shows the "privacy options" form (required once consent has been given under GDPR/US-states). */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            consentInformation?.let { onConsentResolved(it) }
            onDone()
        }
    }

    private fun onConsentResolved(consentInfo: ConsentInformation) {
        _privacyOptionsRequired.value =
            consentInfo.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        val canRequest = consentInfo.canRequestAds()
        _canRequestAds.value = canRequest
        if (canRequest) initializeMobileAds()
    }

    /**
     * Initializes the Mobile Ads SDK exactly once, on a background thread, as recommended by
     * Google (the SDK does its own heavy lifting synchronously on the calling thread).
     */
    private fun initializeMobileAds() {
        if (!mobileAdsInitialized.compareAndSet(false, true)) return
        val context = appContext ?: return
        scope.launch {
            MobileAds.initialize(context) { }
        }
    }
}
