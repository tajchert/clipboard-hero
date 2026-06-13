package pl.tajchert.clipboardhero.settings

enum class AutoDelete(val hours: Int?) {
    OFF(null),
    H1(1),
    H24(24),
    D7(168),
}

data class PrivacySettings(
    val historyEnabled: Boolean = true,
    val autoDelete: AutoDelete = AutoDelete.OFF,
)
