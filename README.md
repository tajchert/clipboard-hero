# Clipboard Hero

**Share any image → it's on your clipboard → paste it anywhere.**

A tiny Android app that lives in the share menu. Take a screenshot (or find any
image), hit *Share*, pick **Copy to clipboard** — and paste the image straight
into Messenger, Instagram, Telegram, or anything else, right from your keyboard
(Gboard and most modern keyboards support image paste). No saving to gallery,
no hunting for the file later.

You'll probably never open the app itself. That's the point.

## Features

- **Share-menu copy** — registered for `image/*` shares only; appears in the
  share sheet's direct-share row after a few uses.
- **Quick confirmation** — a translucent card flashes the copied thumbnail and
  size, then gets out of your way (~1.5 s).
- **Smart compression** — by default images are re-encoded to JPEG at 90%
  quality (visually lossless, much smaller, pastes everywhere). Configurable:
  Original (byte-exact copy), WebP, or JPEG; quality 50–100; optional downscale
  to 2048/1080 px. A size guard keeps the original whenever "compression" would
  make the file bigger, and GIFs always pass through untouched.
- **Copy history** — the last 10 copies live on the main screen. Tap to copy
  again (the clipboard is volatile — something always overwrites it), long-press
  to delete.
- **Privacy controls** — clear history *and* the system clipboard with one tap;
  optional auto-delete (1 h / 24 h / 7 d); history can be turned off entirely.
  Everything is local: no network, no analytics, **zero permissions**, and the
  image history is excluded from device backups.

## Why not just use the share sheet's built-in copy?

Some Android builds offer a "Copy" action in the share sheet, but it's
inconsistent across OEMs and apps. More importantly, most implementations put
the *sender's* temporary URI on the clipboard — which expires moments later, so
the paste silently fails. This app copies the actual bytes into its own storage
and serves them from its own FileProvider, so the paste works minutes later,
after the source app is gone, every time.

## How it works (architecture)

```
share sheet
    │  ACTION_SEND image/*
    ▼
ShareReceiverActivity        translucent; waits for window focus
    │                        (Android 10+ clipboard rule), validates the URI
    ▼
ImageClipboardRepository     streams bytes → clips/clip_<timestamp>.<ext>
    │      │                 prunes history per RetentionPolicy (count + TTL)
    │      ▼
    │  ImageTransformer      optional re-encode: sampled decode, EXIF rotation,
    │                        downscale, WebP/JPEG encode, size guard,
    │                        pass-through fallback on any failure
    ▼
ClipboardManager.setPrimaryClip(ClipData over our FileProvider URI)
```

Key design decisions:

- **Own FileProvider, own bytes.** The clipboard entry references a file this
  app controls, scoped to the `clips/` directory only — the paste target's read
  grant comes from the clipboard service and never expires under it.
- **Filesystem as database.** History is just timestamped files; order, age and
  identity derive from filenames. No SQLite for 10 items.
- **Lazy retention.** Auto-delete is enforced whenever the app or share target
  runs — no background services, no WorkManager, no battery cost.
- **Compression never breaks the copy.** Every transformer failure (corrupt
  input, OOM, codec issues) degrades to copying the original bytes.

Stack: Kotlin, Jetpack Compose (Material 3), Preferences DataStore,
single module, minSdk 29. Settings UI lives on the one main screen.

## Building

```bash
./gradlew :app:assembleDebug     # build
./gradlew :app:testDebugUnitTest # run the test suite (Robolectric)
./gradlew :app:installDebug      # install on a connected device
```

Requires JDK 17+ and an Android SDK (`local.properties` → `sdk.dir`, or
`ANDROID_HOME`). The test suite covers the copy pipeline end-to-end — byte
fidelity, format conversion, retention/expiry, clipboard contents — and runs
without an emulator.

## Acknowledgements

Inspired by [share_to_clipboard](https://github.com/tengusw/share_to_clipboard)
by tengusw — a great idea whose implementation deserved a modern successor.
This app shares no code with it.

## License

[MIT](LICENSE)
