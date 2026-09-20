# Play Console store listing / data safety checklist (Phase 5 - Ads)

Reference notes for filling in the Play Console once AdMob (play-services-ads 25.5.0 + UMP
4.0.0, `com.google.android.gms.ads.APPLICATION_ID` = `ca-app-pub-1665272374483034~3896660100`)
and Play Games Services v2 are wired in. This is not submitted automatically - the account
owner (Softosaurus) must transcribe these answers into the Play Console "App content" ->
"Data safety" form and the "Advertising ID" declaration for this app.

## Note on closed testing

Per the account owner's own notes: the Softosaurus Google Play developer account predates
November 2023, so the 12-testers/14-days closed-testing requirement for new personal accounts
does not apply here. Releases for this app can go straight to open testing or production.

## Data safety form - data types to declare

AdMob and Play Games Services v2 each cause specific data types to be collected/shared. Answer
per Google's published AdMob data disclosure and the Play Games Services v2 data mapping:

| Category | Data type | Collected | Shared | Purpose |
|---|---|---|---|---|
| Device or other IDs | Advertising ID (and other device identifiers) | Yes | Yes (with ad partners) | Advertising or marketing, Analytics |
| Location | Approximate location | Yes (if coarse location signals are available on device; AdMob may use IP-derived approximate location even without a location permission) | Yes (with ad partners) | Advertising or marketing |
| App activity | App interactions | Yes | Yes (with ad partners) | Advertising or marketing, Analytics |
| App activity | In-app search history | No (not applicable to this app) | No | - |
| App info and performance | Diagnostics (crash logs, performance data) | Yes | Yes (with ad partners / Google) | Analytics, App functionality |
| Personal info | User IDs (Play Games player ID, if Play Games Services v2 sign-in is used) | Yes | Yes (with Google Play Games) | App functionality, Account management |

Notes:
- "Collected" = the SDK reads it on-device. "Shared" = it leaves the device to a third party
  (Google/AdMob ad partners). Both AdMob data points above should be marked Yes/Yes.
- Mark data collection as required for the app to function is **No** for the ad-related rows
  (ads are supportive, not core function) and encryption in transit as **Yes** (AdMob/UMP/Play
  Games traffic is HTTPS). Data deletion request: not applicable for ad identifiers (user can
  reset/delete their Advertising ID via device OS settings) - note this in the form's optional
  text field if prompted.
- Re-verify the exact wording against the current Play Console form and AdMob's own "Data
  disclosure for Google Play's Data safety section" page before submitting, as Google
  periodically updates the required fields.

## Advertising ID (AD_ID) permission declaration

The Play Console will ask, separately from Data safety, whether the app uses advertising ID
(`com.google.android.gms.permission.AD_ID`, declared in the manifest - see below).

- **Does your app use advertising ID?** Yes.
- **Purpose:** Advertising or marketing (AdMob banner ads).
- Confirm this matches the "Contains ads" declaration and the Data safety answers above -
  Play Console flags mismatches between these three surfaces.

## "Contains ads" declaration

- **Does your app contain ads?** Yes.

## Target audience / Families policy

- Set the target audience to an age group that does **not** include "Under 13" as a primary
  audience (i.e. do not opt into the Families program) - this app uses AdMob with UMP consent
  and standard (not child-directed) ad requests, which is incompatible with Families policy
  requirements. Confirm the target age selection in Play Console "Target audience and content"
  reflects "not designed for children" so the Ads SDK is not required to run in a
  child-directed/COPPA-restricted mode.

## Content rating

- Reminder: (re-)complete or review the IARC content rating questionnaire in Play Console after
  this release, since adding ads/UMP can affect some regional content-rating questionnaires
  (e.g. questions about ads/user-generated content/data collection). Don't skip this step even
  if the app itself is unchanged gameplay-wise.

## Privacy policy URL

The in-app "Privacy policy" link (`privacy_policy_url` in `strings.xml`) points at
`https://sites.google.com/view/softosaurus/privacy-policy`, which has been verified live and
matches the URL set in the Play listing.

- Remaining check for the account owner: confirm the policy *text* explicitly mentions:
  - use of AdMob / Google ads and the advertising identifier,
  - use of Play Games Services (player ID / sign-in), and
  - a link to Google's own privacy/ads policies (e.g. https://policies.google.com/technologies/ads)
    if the current policy text doesn't already cover third-party ad partners.

## Manifest permissions actually shipped

**Do not read this list off `app/src/main/AndroidManifest.xml`.** What Play sees — and what the
"App permissions" section of the store listing shows users — is the *merged* manifest, which
includes everything the AdMob, UMP and Play Games libraries contribute. Regenerate the list
after any dependency bump:

```
./gradlew.bat bundleRelease
grep -o 'uses-permission[^>]*android:name="[^"]*"' \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
```

As of versionCode 11 the merged release manifest declares:

| Permission | Comes from | Notes |
|---|---|---|
| `android.permission.INTERNET` | this app's manifest | Ads, Play Games |
| `android.permission.ACCESS_NETWORK_STATE` | this app's manifest | Ads, Play Games |
| `android.permission.VIBRATE` | this app's manifest | Haptic target cue; user-switchable in Settings |
| `com.google.android.gms.permission.AD_ID` | this app's manifest | Advertising ID; declared in Play Console (see above) |
| `android.permission.WAKE_LOCK` | **Ads SDK** (play-services-ads) | Not requested by app code |
| `android.permission.FOREGROUND_SERVICE` | **Ads SDK** (play-services-ads) | Not requested by app code |
| `android.permission.ACCESS_ADSERVICES_AD_ID` | **Ads SDK** (Privacy Sandbox) | Normal permission, no runtime prompt |
| `android.permission.ACCESS_ADSERVICES_ATTRIBUTION` | **Ads SDK** (Privacy Sandbox) | Normal permission, no runtime prompt |
| `android.permission.ACCESS_ADSERVICES_TOPICS` | **Ads SDK** (Privacy Sandbox) | Normal permission, no runtime prompt |
| `org.softosaurus.reactionspeed.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX core | Signature-level, internal to the app |

### What the SDK-contributed permissions mean for Data safety

None of these four/seven are runtime (dangerous) permissions, so none of them produces a consent
dialog and none of them appears in the Play Store's user-facing permission list as a sensitive
item. They still matter for the form:

- **`WAKE_LOCK` and `FOREGROUND_SERVICE`** are used internally by the Ads SDK for ad loading and
  media playback. They do **not** add a new data type to declare. They are, however, a common
  source of Play Console review questions ("why does a reaction-time game need a foreground
  service?") — the answer is "it does not; the Google Mobile Ads SDK declares them", and no app
  code starts a service or takes a wake lock.
- **The three `ACCESS_ADSERVICES_*` permissions** are the Android Privacy Sandbox APIs (Topics,
  Attribution Reporting, Ad ID). They are the mechanism behind the **Advertising ID** and
  **App activity** rows already declared in the table at the top of this document — they do not
  introduce a data type beyond those, but they are the concrete reason why "Device or other IDs →
  Advertising ID → Collected: Yes, Shared: Yes" must be answered Yes even though this app's own
  code never reads an advertising identifier.
- Practical consequence: **do not** answer "No" to the Advertising ID question on the grounds
  that the app's own source does not touch it. Play Console cross-checks the declaration against
  the merged manifest and will reject the mismatch.

### Init providers in the merged manifest

The merged release manifest also carries `MobileAdsInitProvider`, `PlayGamesInitProvider` and
AndroidX's `InitializationProvider`. `PlayGamesInitProvider` is present **even though
`game_services_project_id` is still blank** in `games-ids.xml`; it is a no-op in that state (see
the release smoke test notes), so shipping without the Play Games export is safe — Play Games
features simply stay off.
