package pl.tajchert.clipboardhero

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.tajchert.clipboardhero.settings.CopySettings
import pl.tajchert.clipboardhero.settings.PrivacySettings
import pl.tajchert.clipboardhero.settings.SettingsRepository
import pl.tajchert.clipboardhero.ui.ClipboardHeroTheme
import pl.tajchert.clipboardhero.ui.HistoryItemUi
import pl.tajchert.clipboardhero.ui.HistoryUi
import pl.tajchert.clipboardhero.ui.MainScreen
import pl.tajchert.clipboardhero.ui.Thumbnails

class MainActivity : ComponentActivity() {

    private val repository by lazy { ImageClipboardRepository(applicationContext) }
    private val settingsRepository by lazy { SettingsRepository.create(applicationContext) }

    // One snapshot write per refresh: images + their decoded thumbnails change
    // together, so holding them in a single state object avoids the extra
    // recomposition two separate `mutableStateOf` fields would cause.
    private var history by mutableStateOf(HistorySnapshot())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareShortcuts.publish(applicationContext)
        setContent {
            ClipboardHeroTheme {
                val settings by settingsRepository.settings.collectAsStateWithLifecycle(CopySettings())
                val privacy by settingsRepository.privacySettings.collectAsStateWithLifecycle(PrivacySettings())
                // Derive the UI list only when the underlying snapshot changes,
                // not on every recomposition triggered by settings edits.
                val snapshot = history
                val historyUi = remember(snapshot) {
                    HistoryUi(
                        items = snapshot.images.map {
                            HistoryItemUi(id = it.file.name, thumbnail = snapshot.thumbnails[it.file.name])
                        },
                        onRecopy = ::recopy,
                        onDelete = ::deleteItem,
                        onClearAll = ::clearAll,
                    )
                }
                MainScreen(
                    settings = settings,
                    onSettingsChange = { updated ->
                        lifecycleScope.launch { settingsRepository.update(updated) }
                    },
                    privacy = privacy,
                    onPrivacyChange = { updated ->
                        lifecycleScope.launch {
                            settingsRepository.updatePrivacy(updated)
                            refreshHistory()
                        }
                    },
                    history = historyUi,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshHistory() }
    }

    private suspend fun refreshHistory() {
        val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
        val snapshot = withContext(Dispatchers.IO) {
            val images = repository.history(retention)
            HistorySnapshot(
                images = images,
                thumbnails = images.associate { it.file.name to Thumbnails.decode(it.file, maxDimension = 256) },
            )
        }
        history = snapshot
    }

    private fun recopy(item: HistoryItemUi) {
        val image = history.images.firstOrNull { it.file.name == item.id } ?: return
        if (repository.recopy(image)) {
            Toast.makeText(this, R.string.copied_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteItem(item: HistoryItemUi) {
        val image = history.images.firstOrNull { it.file.name == item.id } ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.delete(image) }
            refreshHistory()
        }
    }

    private fun clearAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.clearAll() }
            refreshHistory()
            Toast.makeText(this@MainActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
    }
}

/** History clips paired with their decoded thumbnails, updated atomically. */
private data class HistorySnapshot(
    val images: List<CopiedImage> = emptyList(),
    val thumbnails: Map<String, Bitmap?> = emptyMap(),
)
