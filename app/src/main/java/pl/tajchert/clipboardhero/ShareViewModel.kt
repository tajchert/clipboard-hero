package pl.tajchert.clipboardhero

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.tajchert.clipboardhero.di.AppDispatcher
import pl.tajchert.clipboardhero.di.Dispatcher
import pl.tajchert.clipboardhero.settings.SettingsRepository
import pl.tajchert.clipboardhero.ui.CopyState
import pl.tajchert.clipboardhero.ui.Thumbnails
import javax.inject.Inject

/**
 * Drives the single copy performed by [ShareReceiverActivity]. The activity
 * calls [copy] from `onWindowFocusChanged` (Android 10+ only lets the focused
 * app write the clipboard); the [started] guard makes that idempotent across
 * focus changes and config recreation.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val repository: ImageClipboardRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _copyState = MutableStateFlow<CopyState>(CopyState.Pending)
    val copyState: StateFlow<CopyState> = _copyState.asStateFlow()

    private var started = false

    fun copy(sourceUri: Uri?, mimeType: String?) {
        if (started) return
        started = true
        if (sourceUri == null || !sourceUri.isSafeSource()) {
            _copyState.value = CopyState.Error
            return
        }
        viewModelScope.launch {
            _copyState.value = withContext(ioDispatcher) {
                val settings = settingsRepository.settings.first()
                val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
                repository.copyToClipboard(sourceUri, mimeType, settings, retention)
                    .onSuccess { ShareShortcuts.publish(context) }
                    .map { CopyState.Success(Thumbnails.decode(it.file), it.originalBytes, it.finalBytes) }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
            }
        }
    }

    // This entry point is exported, so the incoming URI is attacker-controlled:
    // only accept content URIs, and never our own FileProvider (an app could
    // otherwise trick us into re-publishing our private files onto the clipboard).
    private fun Uri.isSafeSource(): Boolean =
        scheme == "content" && authority != ImageClipboardRepository.AUTHORITY

    private companion object {
        const val TAG = "ShareViewModel"
    }
}
