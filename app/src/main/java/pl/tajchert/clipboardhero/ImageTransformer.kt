package pl.tajchert.clipboardhero

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import pl.tajchert.clipboardhero.settings.CopySettings
import pl.tajchert.clipboardhero.settings.OutputFormat
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class TransformResult(
    val file: File,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
)

class ImageTransformer {

    /**
     * Applies the configured compression to [source]. On any failure, or when the
     * settings ask for nothing, returns [source] untouched — compression must never
     * break the copy.
     */
    fun transform(source: File, sourceMime: String, settings: CopySettings): TransformResult {
        val originalBytes = source.length()
        val passThrough = TransformResult(source, sourceMime, originalBytes, originalBytes)

        val wantsFormatChange = settings.format != OutputFormat.ORIGINAL
        val targetPx = settings.maxDimension.px
        if (!wantsFormatChange && targetPx == null) return passThrough
        if (sourceMime == "image/gif") return passThrough // re-encoding destroys animation

        return runCatching {
            val decoded = decode(source, targetPx) ?: return passThrough
            val scaled = downscale(decoded.bitmap, targetPx)
            val wasResized = decoded.sampled || scaled !== decoded.bitmap

            val encoding = encodingFor(settings.format, sourceMime)
            val prepared = if (encoding.mimeType == "image/jpeg") flattenAlpha(scaled) else scaled
            // unique name so concurrent transforms in the same directory can't collide
            val output = File.createTempFile("transformed", ".${encoding.extension}", source.parentFile)
            output.outputStream().use { sink ->
                if (!prepared.compress(encoding.format, settings.quality, sink)) {
                    error("Bitmap.compress returned false for ${encoding.mimeType}")
                }
            }

            if (!wasResized && output.length() >= originalBytes) {
                output.delete() // compression must never make things worse
                passThrough
            } else {
                TransformResult(output, encoding.mimeType, originalBytes, output.length())
            }
        }.getOrElse { failure ->
            Log.w(TAG, "Transform failed, copying original", failure)
            passThrough
        }
    }

    private class Decoded(val bitmap: Bitmap, val sampled: Boolean)
    private class Encoding(val format: Bitmap.CompressFormat, val mimeType: String, val extension: String)

    private fun decode(source: File, targetPx: Int?): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        if (targetPx != null) {
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetPx) sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        return Decoded(applyExifRotation(source, bitmap), sampled = sampleSize > 1)
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap = runCatching {
        val orientation = ExifInterface(source)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)

    private fun downscale(bitmap: Bitmap, targetPx: Int?): Bitmap {
        if (targetPx == null) return bitmap
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= targetPx) return bitmap
        val scale = targetPx.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun encodingFor(format: OutputFormat, sourceMime: String): Encoding = when (format) {
        OutputFormat.WEBP -> Encoding(webpCompressFormat(), "image/webp", "webp")
        OutputFormat.JPEG -> Encoding(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
        // ORIGINAL + downscale: keep the format family — PNG stays lossless, the rest becomes JPEG
        OutputFormat.ORIGINAL ->
            if (sourceMime == "image/png") Encoding(Bitmap.CompressFormat.PNG, "image/png", "png")
            else Encoding(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
    }

    private fun webpCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        // hasAlpha() is an unreliable hint (decoders may flag transparent bitmaps
        // opaque), so flatten whenever the pixel format can carry alpha at all.
        if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private companion object {
        const val TAG = "ImageTransformer"
    }
}
