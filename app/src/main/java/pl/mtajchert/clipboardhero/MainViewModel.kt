package pl.mtajchert.clipboardhero

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mtajchert.clipboardhero.di.AppDispatcher
import pl.mtajchert.clipboardhero.di.Dispatcher
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.PrivacySettings
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import pl.mtajchert.clipboardhero.ui.HistoryItemUi
import pl.mtajchert.clipboardhero.ui.Thumbnails
import javax.inject.Inject

/**
 * Owns the home screen: copy settings, privacy settings, and the on-disk clip
 * history. Settings come from reactive DataStore flows; history is the
 * filesystem (see [ImageClipboardRepository]), so it is refreshed explicitly
 * on screen resume and after any mutation.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ImageClipboardRepository,
    private val settingsRepository: SettingsRepository,
    @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val history = MutableStateFlow(HistoryData())

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepository.settings,
        settingsRepository.privacySettings,
        history,
    ) { settings, privacy, historyData ->
        MainUiState(
            settings = settings,
            privacy = privacy,
            history = historyData.images.map {
                HistoryItemUi(id = it.file.name, thumbnail = historyData.thumbnails[it.file.name])
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    /** One-shot user-facing messages (string resource ids) for transient toasts. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    init {
        refreshHistory()
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
            history.value = withContext(ioDispatcher) {
                val images = repository.history(retention)
                HistoryData(
                    images = images,
                    thumbnails = images.associate { it.file.name to Thumbnails.decode(it.file, maxDimension = 256) },
                )
            }
        }
    }

    fun updateSettings(settings: CopySettings) {
        viewModelScope.launch { settingsRepository.update(settings) }
    }

    fun updatePrivacy(privacy: PrivacySettings) {
        viewModelScope.launch {
            settingsRepository.updatePrivacy(privacy)
            refreshHistory()
        }
    }

    fun recopy(id: String) {
        val image = history.value.images.firstOrNull { it.file.name == id } ?: return
        viewModelScope.launch {
            if (withContext(ioDispatcher) { repository.recopy(image) }) {
                _messages.tryEmit(R.string.copied_success)
            }
        }
    }

    fun delete(id: String) {
        val image = history.value.images.firstOrNull { it.file.name == id } ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.delete(image) }
            refreshHistory()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.clearAll() }
            refreshHistory()
            _messages.tryEmit(R.string.history_cleared)
        }
    }

    /** Clips paired with their decoded thumbnails, swapped atomically per refresh. */
    private data class HistoryData(
        val images: List<CopiedImage> = emptyList(),
        val thumbnails: Map<String, Bitmap?> = emptyMap(),
    )
}
