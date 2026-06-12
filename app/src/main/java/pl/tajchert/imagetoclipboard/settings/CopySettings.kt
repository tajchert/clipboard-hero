package pl.tajchert.imagetoclipboard.settings

enum class OutputFormat { ORIGINAL, WEBP, JPEG }

enum class MaxDimension(val px: Int?) {
    ORIGINAL(null),
    P2048(2048),
    P1080(1080),
}

data class CopySettings(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 90,
    val maxDimension: MaxDimension = MaxDimension.ORIGINAL,
)
