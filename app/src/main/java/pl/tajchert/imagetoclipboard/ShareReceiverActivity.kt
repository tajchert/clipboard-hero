package pl.tajchert.imagetoclipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.tajchert.imagetoclipboard.settings.SettingsRepository
import pl.tajchert.imagetoclipboard.ui.ConfirmationSheet
import pl.tajchert.imagetoclipboard.ui.CopyState
import pl.tajchert.imagetoclipboard.ui.Thumbnails

class ShareReceiverActivity : ComponentActivity() {

    private var copyState by mutableStateOf<CopyState>(CopyState.Pending)
    private var copyStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfirmationSheet(state = copyState, onDone = ::finish)
            }
        }
    }

    // Android 10+ only lets the focused app write the clipboard, so the copy
    // must wait until this window actually holds focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !copyStarted) {
            copyStarted = true
            copySharedImage()
        }
    }

    private fun copySharedImage() {
        val sourceUri = if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (sourceUri == null) {
            copyState = CopyState.Error
            return
        }
        lifecycleScope.launch {
            val repository = ImageClipboardRepository(applicationContext)
            copyState = withContext(Dispatchers.IO) {
                val settings = SettingsRepository.create(applicationContext).settings.first()
                repository.copyToClipboard(sourceUri, intent.type, settings)
                    .map { CopyState.Success(Thumbnails.decode(it.file)) }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
            }
        }
    }

    private companion object {
        const val TAG = "ShareReceiver"
    }
}
