package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.tajchert.imagetoclipboard.R

sealed interface CopyState {
    data object Pending : CopyState
    data class Success(val thumbnail: Bitmap?) : CopyState
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
                isError = false,
            )
            CopyState.Error -> ResultCard(
                thumbnail = null,
                message = stringResource(R.string.copied_error),
                isError = true,
            )
        }
    }
}

@Composable
private fun ResultCard(thumbnail: Bitmap?, message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
