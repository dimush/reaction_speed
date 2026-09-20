# Reaction Speed

An Android reflex game by [Softosaurus](https://play.google.com/store/apps/details?id=org.softosaurus.reactionspeed).
Ten red targets appear on a grass field, each after a random 0.5–3.5 s pause; you tap each one as
fast as you can. The score of a series is the mean of the ten reactions with the outliers dropped
(the same standard-deviation filter the game has used since 1.0). Results are kept on the device
and, once Play Games Services is configured, on two world leaderboards.

Package `org.softosaurus.reactionspeed` · minSdk 24 · targetSdk 36.

## Building

Requires JDK 21 and the Android SDK (platform 36).

```
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM unit tests (engine, scorer, repository, migration)
./gradlew lintDebug              # must stay error-free
./gradlew bundleRelease          # signed AAB for Play
```

Release signing reads `keystore.properties` from the repo root (gitignored; see
`keystore.properties.example`). Without that file the release build still compiles, unsigned.

## Releasing

```
node tool/play_upload.mjs --track internal
```

The script uploads `app/build/outputs/bundle/release/app-release.aab` together with the release
notes in `tool/play_notes/*.txt`. Bump `versionCode` in `app/build.gradle.kts` first — Play rejects
a code that has already been used.

Before shipping, walk through `docs/STORE_CHECKLIST.md` (data safety, content rating, ads and
privacy policy).

## Play Games Services

`app/src/main/res/values/games-ids.xml` holds the leaderboard and achievement ids. It ships with
**empty placeholders**, which the app treats as "not configured": the online section of the home
screen is hidden, no Play Games client is ever created and nothing is submitted. Replace the whole
file with the export from Play Console (Play Games Services → Setup and management →
Configuration → *Get resources*); the resource names already match.

Setting up the Play Console side is described in `docs/PLAY_GAMES_SETUP.md`.

## Layout

```
app/src/main/java/org/softosaurus/reactionspeed/
  game/      GameEngine (pure Kotlin state machine), GameView (SurfaceView + render thread),
             renderer, audio, haptics
  data/      ResultsRepository (SharedPreferences) with migration of the legacy 3.x preferences
  games/     PlayGamesManager — sign-in, score submission, achievements, native screens
  ads/       UMP consent, Mobile Ads init, anchored adaptive banner
  ui/        Compose/Material 3 shell: Home, Game, Result, Stats, Settings
```

Screenshots of the current build are in `docs/screenshots/`. The roadmap and its status live in
`ROADMAP.md`.
