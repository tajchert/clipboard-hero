package pl.tajchert.clipboardhero

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import pl.tajchert.clipboardhero.ui.ClipboardHeroTheme
import pl.tajchert.clipboardhero.ui.HistoryUi
import pl.tajchert.clipboardhero.ui.MainScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareShortcuts.publish(applicationContext)

        // Transient toasts (recopy / cleared) — collected lifecycle-aware.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { messageRes ->
                    Toast.makeText(this@MainActivity, messageRes, Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContent {
            ClipboardHeroTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val historyUi = remember(uiState.history) {
                    HistoryUi(
                        items = uiState.history,
                        onRecopy = { viewModel.recopy(it.id) },
                        onDelete = { viewModel.delete(it.id) },
                        onClearAll = viewModel::clearAll,
                    )
                }
                MainScreen(
                    settings = uiState.settings,
                    onSettingsChange = viewModel::updateSettings,
                    privacy = uiState.privacy,
                    onPrivacyChange = viewModel::updatePrivacy,
                    history = historyUi,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHistory()
    }
}
