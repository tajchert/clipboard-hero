package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Thumbnails {
    /** Decodes [file] downsampled so the longest edge is roughly [maxDimension] px. */
    fun decode(file: File, maxDimension: Int = 512): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}
