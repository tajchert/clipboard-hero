package pl.tajchert.clipboardhero

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import pl.tajchert.clipboardhero.ui.ClipboardHeroTheme
import pl.tajchert.clipboardhero.ui.ConfirmationSheet

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipboardHeroTheme {
                val state by viewModel.copyState.collectAsStateWithLifecycle()
                ConfirmationSheet(state = state, onDone = ::finish)
            }
        }
    }

    // Android 10+ only lets the focused app write the clipboard, so the copy
    // must wait until this window actually holds focus. ShareViewModel.copy is
    // idempotent, so calling it on every focus gain is safe.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val sourceUri = if (intent?.action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                null
            }
            viewModel.copy(sourceUri, intent?.type)
        }
    }
}
