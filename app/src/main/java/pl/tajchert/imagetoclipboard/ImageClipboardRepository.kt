package pl.tajchert.imagetoclipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
)

class ImageClipboardRepository(private val context: Context) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(sourceUri: Uri, fallbackMimeType: String?): Result<CopiedImage> = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri)?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: fallbackMimeType?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: GENERIC_IMAGE_MIME

        clipsDir.mkdirs()
        clipsDir.listFiles()?.forEach { it.delete() }
        val target = File(clipsDir, "clip.${extensionFor(mimeType)}")

        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open input stream for $sourceUri")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }

        val copied = target.toCopiedImage(mimeType)
        setClip(copied)
        copied
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun latestImage(): CopiedImage? {
        val file = clipsDir.listFiles()?.firstOrNull() ?: return null
        return file.toCopiedImage(mimeTypeFor(file.extension))
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun File.toCopiedImage(mimeType: String): CopiedImage {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, this)
        return CopiedImage(file = this, providerUri = uri, mimeType = mimeType)
    }

    private fun extensionFor(mimeType: String): String = MIME_TO_EXTENSION[mimeType] ?: "bin"

    private fun mimeTypeFor(extension: String): String =
        MIME_TO_EXTENSION.entries.firstOrNull { it.value == extension }?.key ?: GENERIC_IMAGE_MIME

    companion object {
        const val AUTHORITY = "pl.tajchert.imagetoclipboard.fileprovider"
        private const val GENERIC_IMAGE_MIME = "image/*"
        private val MIME_TO_EXTENSION = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/bmp" to "bmp",
            "image/*" to "img",
        )
    }
}
