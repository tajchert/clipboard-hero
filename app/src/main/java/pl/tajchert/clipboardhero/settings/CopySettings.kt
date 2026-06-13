package pl.tajchert.clipboardhero.settings

enum class OutputFormat { ORIGINAL, WEBP, JPEG }

enum class MaxDimension(val px: Int?) {
    ORIGINAL(null),
    P2048(2048),
    P1080(1080),
}

data class CopySettings(
    // JPEG over WebP: some paste targets (e.g. Telegram) mishandle WebP clipboard images
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 90,
    val maxDimension: MaxDimension = MaxDimension.ORIGINAL,
)
