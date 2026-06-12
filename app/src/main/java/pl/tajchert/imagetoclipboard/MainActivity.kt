package pl.tajchert.imagetoclipboard

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.tajchert.imagetoclipboard.ui.LastCopiedUi
import pl.tajchert.imagetoclipboard.ui.MainScreen
import pl.tajchert.imagetoclipboard.ui.Thumbnails

class MainActivity : ComponentActivity() {

    private val repository by lazy { ImageClipboardRepository(applicationContext) }
    private var lastCopied by mutableStateOf<LastCopiedUi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                MainScreen(lastCopied = lastCopied)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLastCopied()
    }

    private fun refreshLastCopied() {
        val latest = repository.latestImage()
        lastCopied = latest?.let { image ->
            LastCopiedUi(
                thumbnail = Thumbnails.decode(image.file),
                onCopyAgain = {
                    if (repository.recopy(image)) {
                        Toast.makeText(this, R.string.copied_success, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}
