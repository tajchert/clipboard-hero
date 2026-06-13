package pl.tajchert.clipboardhero.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.tajchert.clipboardhero.R

sealed interface CopyState {
    data object Pending : CopyState
    data class Success(
        val thumbnail: Bitmap?,
        val originalBytes: Long,
        val finalBytes: Long,
    ) : CopyState
    data object Error : CopyState
}

private const val DISMISS_DELAY_MS = 1500L

@Composable
fun ConfirmationSheet(state: CopyState, onDone: () -> Unit) {
    LaunchedEffect(state) {
        if (state !is CopyState.Pending) {
            delay(DISMISS_DELAY_MS)
            onDone()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.action_dismiss),
                onClick = onDone,
            )
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when (state) {
            CopyState.Pending -> Unit
            is CopyState.Success -> ResultCard(
                thumbnail = state.thumbnail,
                message = stringResource(R.string.copied_success),
                subtitle = sizeSubtitle(state.originalBytes, state.finalBytes),
                isError = false,
            )
            CopyState.Error -> ResultCard(
                thumbnail = null,
                message = stringResource(R.string.copied_error),
                subtitle = null,
                isError = true,
            )
        }
    }
}

@Composable
private fun sizeSubtitle(originalBytes: Long, finalBytes: Long): String? {
    if (finalBytes <= 0) return null
    val context = LocalContext.current
    val final = Formatter.formatShortFileSize(context, finalBytes)
    // show the arrow only when compression changed the size meaningfully (>1%)
    return if (originalBytes > 0 && kotlin.math.abs(originalBytes - finalBytes) > originalBytes / 100) {
        "${Formatter.formatShortFileSize(context, originalBytes)} → $final"
    } else {
        final
    }
}

@Composable
private fun ResultCard(thumbnail: Bitmap?, message: String, subtitle: String?, isError: Boolean) {
    Card(
        // The sheet appears asynchronously after the copy completes and auto-
        // dismisses in ~1.5s, so announce it assertively before it disappears.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = message, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(name = "Result — success", showBackground = true)
@Composable
private fun ResultCardSuccessPreview() {
    ClipboardHeroTheme(dynamicColor = false) {
        ResultCard(
            thumbnail = null,
            message = stringResource(R.string.copied_success),
            subtitle = "2.4 MB → 480 kB",
            isError = false,
        )
    }
}

@Preview(name = "Result — error", showBackground = true)
@Composable
private fun ResultCardErrorPreview() {
    ClipboardHeroTheme(dynamicColor = false) {
        ResultCard(
            thumbnail = null,
            message = stringResource(R.string.copied_error),
            subtitle = null,
            isError = true,
        )
    }
}
