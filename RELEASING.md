# Releasing

One self-managed keystore signs the GitHub Releases APK and serves as the
Google Play **upload key** (Play App Signing holds the actual distribution key,
so an upload-key leak is recoverable via Play Console).

## One-time setup

1. Generate the keystore **outside the repo** (prompts for passwords
   interactively — they never touch shell history):

   ```bash
   mkdir -p ~/.android-keys
   keytool -genkeypair -v \
     -keystore ~/.android-keys/imagetoclipboard.keystore \
     -alias imagetoclipboard -keyalg RSA -keysize 4096 -validity 10950
   ```

2. Back up the keystore file **and** both passwords as a secure attachment in
   your password manager. Losing the upload key = support ticket with Google;
   losing it for GitHub releases = users must uninstall/reinstall.

3. Create `keystore.properties` in the repo root (gitignored — verify with
   `git check-ignore keystore.properties`):

   ```properties
   storeFile=/Users/<you>/.android-keys/imagetoclipboard.keystore
   storePassword=...
   keyAlias=imagetoclipboard
   keyPassword=...
   ```

Without `keystore.properties`, release builds fall back to the debug key —
fine for smoke-testing the minified build, never for publishing.

## Each release

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Build and sanity-check:

   ```bash
   ./gradlew clean :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
   ```

   Install `app/build/outputs/apk/release/app-release.apk` on a device and run
   one share → paste round trip. R8 is enabled; release-only breakage shows up
   here, not in debug builds.

3. GitHub:

   ```bash
   gh release create v<version> app/build/outputs/apk/release/app-release.apk \
     --title "v<version>" --generate-notes
   ```

4. Google Play: upload `app/build/outputs/bundle/release/app-release.aab` in
   Play Console. First time: enroll in Play App Signing (default) — Google
   generates the app signing key, this keystore becomes the upload key.

## Rules

- The keystore and `keystore.properties` are gitignored (`*.keystore`, `*.jks`).
  Never commit them, never paste passwords into terminals/CI logs.
- No CI signing for now. If automated releases become worth it, store the
  keystore base64-encoded as a GitHub Actions secret — don't get creative.
- GitHub-APK and Play installs can't update over each other (different
  signing keys once Play App Signing is involved). Expected; not a bug.
