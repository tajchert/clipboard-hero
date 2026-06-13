package pl.mtajchert.clipboardhero

import androidx.compose.runtime.Immutable
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.PrivacySettings
import pl.mtajchert.clipboardhero.ui.HistoryItemUi

/**
 * Everything [MainScreen] needs in one snapshot. A single state object (rather
 * than three separate flows) means one recomposition per change and no
 * intermediate, inconsistent frames.
 */
@Immutable
data class MainUiState(
    val settings: CopySettings = CopySettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val history: List<HistoryItemUi> = emptyList(),
)
