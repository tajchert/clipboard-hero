# CLAUDE.md

Android share-target app: receives `ACTION_SEND image/*`, copies the image to
app-private storage, puts a `ClipData` over its own FileProvider URI on the
clipboard. Kotlin + Compose (Material 3), single module, no DI framework,
minSdk 29. Design specs and implementation plans live in `docs/superpowers/`.

## Commands

```bash
./gradlew :app:assembleDebug                 # build
./gradlew :app:testDebugUnitTest             # all unit tests (Robolectric, no emulator)
./gradlew :app:testDebugUnitTest --tests "pl.tajchert.clipboardhero.ImageTransformerTest"
./gradlew :app:lintDebug                     # lint (warnings OK, errors not)
./gradlew :app:installDebug                  # install; use adb -s <serial> if several devices
```

## Architecture (read in this order)

- `ShareReceiverActivity` — exported entry point. Validates the incoming URI
  (`isSafeSource`), then copies. Hosts the auto-dismissing `ConfirmationSheet`.
- `ImageClipboardRepository` — the core. Streams source bytes to a unique temp
  file, runs `ImageTransformer`, renames to `clips/clip_<epochMillis>.<ext>`,
  prunes per `RetentionPolicy`, sets the clipboard. Also `history()`, `recopy()`,
  `delete()`, `clearAll()`.
- `ImageTransformer` — pure function file→file. Pass-through / decode / downscale
  / encode pipeline with a size guard. Never throws to callers; failure = original
  bytes pass through.
- `settings/` — `CopySettings` (format/quality/maxDimension) + `PrivacySettings`
  (historyEnabled/autoDelete) in one Preferences DataStore. Invalid stored values
  fall back to defaults, never crash.
- `ShareShortcuts` — pushes the dynamic sharing shortcut (direct-share ranking).
- `ui/` — `MainScreen` (settings card + history grid), `ConfirmationSheet`,
  `Thumbnails` (downsampled decode helper).

## Invariants — do not break these

1. **Clipboard writes require window focus on API 29+.** The copy is triggered
   from `onWindowFocusChanged`, never `onCreate`. Moving it breaks Android 10+.
2. **Compression must never break the copy.** Every `ImageTransformer` failure
   path returns the original bytes. Keep `runCatching` + pass-through semantics.
3. **The size guard:** if re-encoding grows the file and nothing was resized,
   the original is kept. Tests rely on this; so does user trust.
4. **GIFs pass through untouched** (re-encoding kills animation).
5. **"Original" format + "Original" size = byte-exact copy.** No decode at all.
6. **History is the filesystem.** `clips/clip_<epochMillis>.<ext>` — name carries
   the timestamp; legacy `clip.<ext>` falls back to mtime. No database; don't add
   one for this.
7. **Retention is lazy** — pruned on every repository read/write. No background
   services, no WorkManager, zero permissions. This is a product feature.
8. **The share activity is exported** — treat `EXTRA_STREAM` as attacker input.
   Only `content://` URIs, never our own FileProvider authority.
9. **`clips/` is excluded from backups** (`backup_rules.xml`,
   `data_extraction_rules.xml`). New sensitive storage must be excluded too.
10. **Default format is JPEG, not WebP** — WebP clipboard paste misbehaves in
    some apps (Telegram). Don't "optimize" this back.

## Testing gotchas (will bite you)

- `app/src/test/resources/robolectric.properties` sets `graphicsMode=NATIVE`.
  Required: legacy shadows fake `Bitmap.compress` bytes and no-op Canvas draws,
  which silently invalidates codec/pixel assertions.
- `FileProvider` caches path strategies statically per authority while Robolectric
  rotates temp `filesDir` per test — tests clear `FileProvider.sCache` via
  reflection in `@Before`. Copy that pattern into new test classes using
  FileProvider.
- Time-dependent tests inject the repository's `clock: () -> Long` constructor
  parameter; never sleep or use real time.
- Lossy-conversion tests need **seeded-noise images**: synthetic gradients are a
  PNG best case, so the size guard (correctly) refuses to convert them and the
  test fails confusingly. `Bitmap.hasAlpha()` is an unreliable decoder hint —
  don't gate alpha handling on it.
- DataStore tests build their own instance via `PreferenceDataStoreFactory` +
  `TemporaryFolder` (file must end `.preferences_pb`); the production singleton
  delegate breaks across Robolectric tests.

## Device/emulator verification

- Drive the real share flow: `adb shell input keyevent 120` (screenshot) → tap
  the HUD share button → pick "Copy to clipboard". **`adb shell am start
  --grant-read-uri-permission` cannot grant MediaStore URIs** — it produces a
  SecurityException and the error card; that's a harness artifact, not a bug.
- Success signals: system clipboard overlay appears; Gboard shows the image chip;
  `adb exec-out run-as pl.tajchert.clipboardhero ls files/clips/` shows the clip.
- Emulator paste targets are limited (Messages rejects attachments without MMS,
  Gmail wants an account) — Gboard offering the image chip is the practical proof.

## Toolchain notes (AGP 9)

- AGP 9 has **built-in Kotlin**: there is no `kotlin-android` plugin in this
  project and `kotlinOptions {}` does not exist — use
  `kotlin { compilerOptions { jvmTarget.set(...) } }` in `app/build.gradle.kts`.
- `material-icons-core` is pinned explicitly (the Compose BOM stopped managing it).
- compileSdk 37 / targetSdk 36 — raising targetSdk opts into new clipboard/share
  runtime behavior; test the full share→paste flow before bumping.
