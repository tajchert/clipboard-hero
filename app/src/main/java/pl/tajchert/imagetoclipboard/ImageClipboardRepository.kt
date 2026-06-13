package pl.tajchert.imagetoclipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.PrivacySettings
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
    val timestamp: Long,
)

data class RetentionPolicy(val maxItems: Int, val ttlMillis: Long?) {
    companion object {
        const val HISTORY_SIZE = 10

        fun from(privacy: PrivacySettings) = RetentionPolicy(
            maxItems = if (privacy.historyEnabled) HISTORY_SIZE else 1,
            ttlMillis = privacy.autoDelete.hours?.let { it * 3_600_000L },
        )
    }
}

class ImageClipboardRepository(
    private val context: Context,
    private val transformer: ImageTransformer = ImageTransformer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(
        sourceUri: Uri,
        fallbackMimeType: String?,
        settings: CopySettings,
        retention: RetentionPolicy,
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

        val extension = extensionFor(transformed.mimeType)
        var timestamp = clock()
        while (File(clipsDir, "clip_$timestamp.$extension").exists()) timestamp++
        val target = File(clipsDir, "clip_$timestamp.$extension")
        if (!transformed.file.renameTo(target)) throw IOException("Cannot move clip into place")
        incoming.delete() // no-op when pass-through already renamed it

        prune(retention)

        val copied = target.toCopiedImage(
            mimeType = transformed.mimeType,
            originalBytes = transformed.originalBytes,
            finalBytes = transformed.finalBytes,
        )
        setClip(copied)
        copied
    }

    fun history(retention: RetentionPolicy): List<CopiedImage> {
        prune(retention)
        return clipFiles()
            .sortedByDescending { timestampOf(it) }
            .map { it.toCopiedImage(mimeTypeFor(it.extension), it.length(), it.length()) }
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun delete(image: CopiedImage) {
        runCatching { image.file.delete() }
    }

    fun clearAll() {
        clipFiles().forEach { runCatching { it.delete() } }
        runCatching {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }

    private fun clipFiles(): List<File> =
        clipsDir.listFiles()?.filter { it.isFile && it.name.startsWith("clip") } ?: emptyList()

    /** clip_<epochMillis>.<ext>; legacy clip.<ext> falls back to file mtime. */
    private fun timestampOf(file: File): Long =
        file.name.removePrefix("clip_").substringBefore('.').toLongOrNull() ?: file.lastModified()

    private fun prune(retention: RetentionPolicy) {
        val cutoff = retention.ttlMillis?.let { clock() - it }
        clipFiles()
            .sortedByDescending { timestampOf(it) }
            .forEachIndexed { index, file ->
                val overLimit = index >= retention.maxItems
                val expired = cutoff != null && timestampOf(file) < cutoff
                if (overLimit || expired) runCatching { file.delete() }
            }
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun File.toCopiedImage(mimeType: String, originalBytes: Long, finalBytes: Long): CopiedImage =
        CopiedImage(
            file = this,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, this),
            mimeType = mimeType,
            originalBytes = originalBytes,
            finalBytes = finalBytes,
            timestamp = timestampOf(this),
        )

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
