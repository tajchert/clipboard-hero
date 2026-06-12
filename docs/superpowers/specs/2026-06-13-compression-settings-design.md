# Compression & Format Settings — Design

**Date:** 2026-06-13
**Status:** Approved
**Builds on:** `2026-06-12-image-to-clipboard-design.md` (MVP)

## Purpose

Let the user control whether shared images are re-encoded before landing on the
clipboard. Smaller clipboard images paste faster and survive messenger size limits;
WebP at high quality is visually lossless for screenshots at a fraction of the size.
Defaults favor compression (WebP, quality 90, no downscale); users who need exact
original bytes can switch format to Original.

## Decisions

| Decision | Choice |
|---|---|
| Settings exposed | Output format (Original / WebP / JPEG), quality slider (50–100), max dimension (Original / 2048 px / 1080 px) |
| Defaults | JPEG, quality 90, original dimensions (was WebP; changed 2026-06-13 — some paste targets, e.g. Telegram, mishandle WebP clipboard images) |
| Persistence | Preferences DataStore (`androidx.datastore:datastore-preferences`) |
| Settings UI | "Copy settings" card on the main screen (no separate screen) |
| Size feedback | Confirmation card subtitle: `4.2 MB → 780 KB` when size changed, plain `780 KB` otherwise |

## Architecture

Two new units; the repository gains one step.

### 1. `SettingsRepository`

- Wraps Preferences DataStore (file name `settings`).
- `data class CopySettings(format: OutputFormat, quality: Int, maxDimension: MaxDimension)`
  - `enum OutputFormat { ORIGINAL, WEBP, JPEG }`
  - `enum MaxDimension(val px: Int?) { ORIGINAL(null), P2048(2048), P1080(1080) }`
  - Defaults: `WEBP`, `90`, `ORIGINAL`.
- API: `val settings: Flow<CopySettings>`, `suspend fun update(settings: CopySettings)`.
- Invalid stored values (e.g. unknown enum name after downgrade) fall back to defaults.

### 2. `ImageTransformer`

`transform(source: File, sourceMime: String, settings: CopySettings): TransformResult`

`TransformResult(file: File, mimeType: String, originalBytes: Long, finalBytes: Long)`

Rules, applied in order:

1. **Pass-through** (no decode, file returned as-is) when:
   - `format == ORIGINAL` and `maxDimension == ORIGINAL`, or
   - `sourceMime == "image/gif"` (re-encoding destroys animation).
2. **Decode** with `inSampleSize` chosen so the decoded bitmap lands at or just above
   the target dimension (never decode a 50 MP bitmap to produce a 1080 px one).
   Apply EXIF orientation for JPEG sources (`androidx.exifinterface`) so transcoded
   photos don't paste rotated.
3. **Downscale** so the long edge ≤ `maxDimension.px` (when set and exceeded;
   never upscale).
4. **Encode**:
   - `format == WEBP` → `WEBP_LOSSY` on API 30+, legacy `Bitmap.CompressFormat.WEBP`
     on API 29; mime `image/webp`, extension `webp`.
   - `format == JPEG` → flatten alpha onto a white canvas first (JPEG has no
     transparency); mime `image/jpeg`, extension `jpg`.
   - `format == ORIGINAL` with a downscale → re-encode in a format matching the
     source: PNG stays PNG (lossless), everything else becomes JPEG at the
     configured quality.
   - `quality` applies to all lossy encodes.
5. **Size guard:** if the encoded output is larger than the source bytes AND no
   downscale happened, discard it and pass the original through. Compression must
   never make things worse.
6. **Failure fallback:** decode returns null, encode throws, or OOM → log a warning
   and pass the original through. Compression must never break the core copy.

### 3. `ImageClipboardRepository` changes

`copyToClipboard(sourceUri, fallbackMimeType, settings)`:

1. Stream source bytes to `clips/incoming.tmp` (unchanged streaming, no decode).
2. `ImageTransformer.transform(tmp, mime, settings)`.
3. Move/rename result to `clips/clip.<ext>` (still: only one file kept), delete tmp.
4. Build ClipData with the **final** mime type, `setPrimaryClip` (unchanged).
5. Return `CopiedImage` extended with `originalBytes` and `finalBytes`.

`ShareReceiverActivity` reads settings once (`settings.first()`) inside the existing
IO coroutine before calling the repository.

## UI

### Main screen — "Copy settings" card

Below the how-to steps, above "Last copied":

- **Format:** `SegmentedButton` row — Original / WebP / JPEG.
- **Quality:** slider 50–100 (step 5), with current value label; only visible when
  format ≠ Original.
- **Max size:** `SegmentedButton` row — Original / 2048 px / 1080 px.
- Every change persists immediately via `SettingsRepository.update`.
- State collected from `SettingsRepository.settings` with `collectAsState`.

### Confirmation card

Subtitle line under "Copied to clipboard":

- Size changed (>1% difference): `4.2 MB → 780 KB`
- Otherwise: `780 KB`
- Sizes formatted with `android.text.format.Formatter.formatShortFileSize`.

## Error handling

- All transformer failures degrade to pass-through (rule 6); the user still gets
  their copy, with original size shown.
- DataStore IO errors surface as defaults (standard `catch { emit(emptyPreferences()) }`).

## Out of scope

Per-app format overrides, animated WebP re-encoding, lossless WebP mode,
"ask every time" mode.

## Testing

Robolectric:

- `ImageTransformerTest`: pass-through when settings are Original/Original; gif
  pass-through; WebP output smaller than a PNG screenshot source; JPEG flattens
  alpha (corner pixel white, not black); downscale bounds long edge; no upscale;
  size guard keeps original; corrupt input falls back to original.
- `SettingsRepositoryTest`: defaults emitted on first read; update→read round-trip;
  invalid stored enum falls back to default.
- `ImageClipboardRepositoryTest` additions: ORIGINAL settings keep byte-exact copy
  (existing tests updated to pass ORIGINAL settings); WEBP settings produce
  `clip.webp` with `image/webp` ClipData mime; result carries original/final sizes.
