# Clipboard Hero — MVP Design

**Date:** 2026-06-12
**Status:** Approved

## Purpose

A minimal Android app that lives in the share menu. Sharing an image to it from any
app puts that image — full resolution, original format — on the system clipboard, so
it can be pasted via the keyboard (Gboard image paste) into Messenger, Instagram,
Telegram, etc. The app is almost never opened directly.

Inspired by [tengusw/share_to_clipboard](https://github.com/tengusw/share_to_clipboard),
which is unmaintained (targetSdk 30, Java, last commit 2022). This app focuses on
images only and fixes that app's core defects:

1. **Expiring clipboard URIs.** The old app puts the *sender's* content URI on the
   clipboard via `ClipData.newUri()`. The sender's `FLAG_GRANT_READ_URI_PERMISSION`
   dies when the share activity finishes, so paste targets often can't read the image.
   We copy the bytes to our own storage and serve them from our own FileProvider.
2. **Android 10+ clipboard restrictions.** Only the focused app may write the
   clipboard on API 29+. The old app writes in `onCreate` of a translucent activity,
   which is racy. We write only after the window gains focus.
3. **Share-menu pollution.** The old app registers `text/*`, `video/*`, `audio/*` it
   handles poorly. We register `image/*` only.

## Decisions

| Decision | Choice |
|---|---|
| Share UX | Translucent confirmation sheet: thumbnail + "Copied to clipboard ✓", auto-dismiss ~1.5 s |
| Main screen | Minimal info screen: how-to steps + last-copied preview with "Copy again" |
| Min SDK | 29 (Android 10); targetSdk 35 |
| Stack | Kotlin, Jetpack Compose, Material 3, single module, version catalog |
| Package | `pl.tajchert.clipboardhero` |

## Architecture

Three units:

### 1. `ShareReceiverActivity`

- Exported; intent filter: `ACTION_SEND`, mime `image/*` only.
- Translucent theme, `excludeFromRecents="true"`, `noHistory` behavior via finish-after-dismiss.
- Waits for window focus (`onWindowFocusChanged`) before triggering the copy —
  required for clipboard writes on API 29+.
- Hosts the Compose confirmation sheet; finishes after auto-dismiss or tap-outside.

### 2. `ImageClipboardRepository`

Single class, no DI framework. Responsibilities:

- `copyToClipboard(uri: Uri): Result<CopiedImage>`
  - Resolve mime type: `ContentResolver.getType(uri)` → fallback to intent type →
    map to extension (`png`, `jpg`, `webp`, `gif`; unknown image subtype → `img.bin`
    with the reported mime kept on the ClipData).
  - Stream bytes `openInputStream(uri)` → `filesDir/clips/clip.<ext>`. Raw byte
    copy: no decode, no re-encode, full resolution preserved.
  - Delete any previous file in `clips/` (keep only the latest).
  - Build `FileProvider` URI (authority `pl.tajchert.clipboardhero.fileprovider`),
    `ClipData.newUri(resolver, "Image", providerUri)`, `setPrimaryClip`.
- `latestImage(): CopiedImage?` — for the main screen's "last copied" card and
  re-copy button.
- `CopiedImage` = file + provider URI + mime type.

### 3. UI (Compose, Material 3)

- **Confirmation sheet** (in `ShareReceiverActivity`): small bottom card, downsampled
  thumbnail, "Copied to clipboard ✓"; error variant "Couldn't read image" on failure.
  Auto-dismiss ~1.5 s, then `finish()`.
- **`MainActivity`**: branding, 3-step how-to (Share → pick this app → paste from
  keyboard), "Last copied" card (thumbnail + Copy again) shown only when a clip exists.

## Error handling

- Missing `EXTRA_STREAM`, unreadable URI, `SecurityException`, I/O failure → error
  card, activity finishes cleanly, never crashes.
- Thumbnails decoded with downsampling; the full image is never loaded into memory.

## Out of scope (future)

`ACTION_SEND_MULTIPLE`, copy history, text fallback, quick-settings tile.

## Testing

- Robolectric unit tests for `ImageClipboardRepository`: byte fidelity (input bytes ==
  stored bytes), mime→extension mapping, latest-only cleanup, ClipData URI + mime
  correctness, error cases (missing/unreadable URI).
- Manifest assertions: share activity exported with correct filter, FileProvider present.
- Manual emulator verification of the end-to-end share → paste flow.
