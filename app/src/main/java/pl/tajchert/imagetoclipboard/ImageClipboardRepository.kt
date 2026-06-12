package pl.tajchert.imagetoclipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import pl.tajchert.imagetoclipboard.settings.CopySettings
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
)

class ImageClipboardRepository(
    private val context: Context,
    private val transformer: ImageTransformer = ImageTransformer(),
) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(
        sourceUri: Uri,
        fallbackMimeType: String?,
        settings: CopySettings,
    ): Result<CopiedImage> = runCatching {
        val resolver = context.contentResolver
        val sourceMime = resolver.getType(sourceUri)?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: fallbackMimeType?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: GENERIC_IMAGE_MIME

        clipsDir.mkdirs()
        val incoming = File(clipsDir, "incoming.tmp")
        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open input stream for $sourceUri")
        input.use { source ->
            incoming.outputStream().use { sink -> source.copyTo(sink) }
        }

        val transformed = transformer.transform(incoming, sourceMime, settings)

        val target = File(clipsDir, "clip.${extensionFor(transformed.mimeType)}")
        clipsDir.listFiles()?.filter { it != transformed.file }?.forEach { it.delete() }
        if (!transformed.file.renameTo(target)) throw IOException("Cannot move clip into place")

        val copied = CopiedImage(
            file = target,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, target),
            mimeType = transformed.mimeType,
            originalBytes = transformed.originalBytes,
            finalBytes = transformed.finalBytes,
        )
        setClip(copied)
        copied
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun latestImage(): CopiedImage? {
        // a leftover incoming.tmp from a crashed copy must never count as the latest image
        val file = clipsDir.listFiles()?.firstOrNull { it.name.startsWith("clip.") } ?: return null
        return CopiedImage(
            file = file,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, file),
            mimeType = mimeTypeFor(file.extension),
            originalBytes = file.length(),
            finalBytes = file.length(),
        )
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
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
