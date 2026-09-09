# Reaction Speed

A reaction-time game for Android from 2012. Targets appear on a stone-and-grass
field and you tap them as fast as you can; the app measures each reaction in
milliseconds, keeps a Top 10 of your best results and shows the history. Target
sounds, stone sounds and vibration can be switched off in the options.

Package `org.softosaurus.reactionspeed`, on Google Play since 2012 as
[Reaction Speed](https://play.google.com/store/apps/details?id=org.softosaurus.reactionspeed).
The menu item "Remove ads..." points at the paid edition,
`org.softosaurus.reactionspeedpro`.

## Project layout

| Path | What |
|---|---|
| `app/` | The Gradle module: Java sources and resources |
| `app/src/main/java/.../MySurfaceView.java` | The game itself: render thread, hit testing, sounds |
| `tool/play_upload.mjs` | Uploads an AAB to Play via the Android Publisher API |
| `tool/play_notes/` | Release notes per locale, picked up by the upload script |

Ads are served by AdMob (`play-services-ads`) with a UMP consent dialog where
required. Target SDK 36, min SDK 23.

## Build

Requires JDK 17+ and the Android SDK (platform 36). Point `local.properties`
at the SDK (`sdk.dir=C\:/path/to/Sdk`), then:

```bash
./gradlew :app:assembleDebug
```

## Release

1. **Signing.** Copy `keystore.properties.example` to `keystore.properties`
   (git-ignored) and fill in the upload key. Use forward slashes in
   `storeFile`. It must be the key Play already knows for this package.
2. **Version.** Bump `versionCode` and `versionName` in `app/build.gradle`.
   Play rejects a reused `versionCode`.
3. **Release notes.** Edit `tool/play_notes/<locale>.txt`.
4. **Build and upload:**

```bash
./gradlew :app:bundleRelease
node tool/play_upload.mjs --track production
```

The upload script needs a Play service-account key: pass `--key`, set
`PLAY_SERVICE_ACCOUNT_JSON`, or keep it at
`%USERPROFILE%/.secrets/ohmyfridge-play-publisher.json`. It never lives in the
repo. Use `--status draft` to upload without rolling out.

If Play answers `signed with the wrong key`, the `storeFile` in
`keystore.properties` points at the wrong keystore; nothing is published in
that case.
