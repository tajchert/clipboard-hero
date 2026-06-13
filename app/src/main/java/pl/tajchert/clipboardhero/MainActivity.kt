package pl.tajchert.clipboardhero

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.tajchert.clipboardhero.settings.CopySettings
import pl.tajchert.clipboardhero.settings.PrivacySettings
import pl.tajchert.clipboardhero.settings.SettingsRepository
import pl.tajchert.clipboardhero.ui.HistoryItemUi
import pl.tajchert.clipboardhero.ui.HistoryUi
import pl.tajchert.clipboardhero.ui.MainScreen
import pl.tajchert.clipboardhero.ui.Thumbnails

class MainActivity : ComponentActivity() {

    private val repository by lazy { ImageClipboardRepository(applicationContext) }
    private val settingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private var historyImages by mutableStateOf<List<CopiedImage>>(emptyList())
    private var thumbnails by mutableStateOf<Map<String, Bitmap?>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareShortcuts.publish(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                val settings by settingsRepository.settings.collectAsState(initial = CopySettings())
                val privacy by settingsRepository.privacySettings.collectAsState(initial = PrivacySettings())
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
                    history = HistoryUi(
                        items = historyImages.map {
                            HistoryItemUi(id = it.file.name, thumbnail = thumbnails[it.file.name])
                        },
                        onRecopy = ::recopy,
                        onDelete = ::deleteItem,
                        onClearAll = ::clearAll,
                    ),
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
        val images = withContext(Dispatchers.IO) { repository.history(retention) }
        val thumbs = withContext(Dispatchers.IO) {
            images.associate { it.file.name to Thumbnails.decode(it.file, maxDimension = 256) }
        }
        historyImages = images
        thumbnails = thumbs
    }

    private fun recopy(item: HistoryItemUi) {
        val image = historyImages.firstOrNull { it.file.name == item.id } ?: return
        if (repository.recopy(image)) {
            Toast.makeText(this, R.string.copied_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteItem(item: HistoryItemUi) {
        val image = historyImages.firstOrNull { it.file.name == item.id } ?: return
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
