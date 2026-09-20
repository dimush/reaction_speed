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

- Reminder for the account owner: verify the privacy policy URL already set in the Play
  Console store listing is still live (not 404/parked) and that its text explicitly mentions:
  - use of AdMob / Google ads and the advertising identifier,
  - use of Play Games Services (player ID / sign-in), and
  - a link to Google's own privacy/ads policies (e.g. https://policies.google.com/technologies/ads)
    if the current policy text doesn't already cover third-party ad partners.

## Manifest permissions expected in the final app

After this phase, `AndroidManifest.xml` (owned by the manifest workstream, not touched here)
should declare:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.VIBRATE`
- `com.google.android.gms.permission.AD_ID`

(All four are already present in the manifest as of this writing.)
