package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.tajchert.imagetoclipboard.R
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.MaxDimension
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import kotlin.math.roundToInt

data class LastCopiedUi(val thumbnail: Bitmap?, val onCopyAgain: () -> Unit)

@Composable
fun MainScreen(
    settings: CopySettings,
    onSettingsChange: (CopySettings) -> Unit,
    lastCopied: LastCopiedUi?,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.howto_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            HowToSteps()
            SettingsCard(settings = settings, onSettingsChange = onSettingsChange)
            if (lastCopied != null) {
                LastCopiedCard(lastCopied)
            }
        }
    }
}

@Composable
private fun HowToSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Step(number = 1, text = stringResource(R.string.howto_step_1))
        Step(number = 2, text = stringResource(R.string.howto_step_2))
        Step(number = 3, text = stringResource(R.string.howto_step_3))
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun SettingsCard(settings: CopySettings, onSettingsChange: (CopySettings) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            LabeledSegmentedRow(
                label = stringResource(R.string.format_label),
                entries = listOf(
                    OutputFormat.ORIGINAL to stringResource(R.string.format_original),
                    OutputFormat.WEBP to stringResource(R.string.format_webp),
                    OutputFormat.JPEG to stringResource(R.string.format_jpeg),
                ),
                selected = settings.format,
                onSelect = { onSettingsChange(settings.copy(format = it)) },
            )

            if (settings.format != OutputFormat.ORIGINAL) {
                Column {
                    Text(
                        text = "${stringResource(R.string.quality_label)}: ${settings.quality}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.quality.toFloat(),
                        onValueChange = {
                            onSettingsChange(settings.copy(quality = (it / 5).roundToInt() * 5))
                        },
                        valueRange = 50f..100f,
                        steps = 9,
                    )
                }
            }

            LabeledSegmentedRow(
                label = stringResource(R.string.max_size_label),
                entries = listOf(
                    MaxDimension.ORIGINAL to stringResource(R.string.max_size_original),
                    MaxDimension.P2048 to stringResource(R.string.max_size_2048),
                    MaxDimension.P1080 to stringResource(R.string.max_size_1080),
                ),
                selected = settings.maxDimension,
                onSelect = { onSettingsChange(settings.copy(maxDimension = it)) },
            )
        }
    }
}

@Composable
private fun <T> LabeledSegmentedRow(
    label: String,
    entries: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, (value, title) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                ) {
                    Text(text = title)
                }
            }
        }
    }
}

@Composable
private fun LastCopiedCard(lastCopied: LastCopiedUi) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.last_copied_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (lastCopied.thumbnail != null) {
                Image(
                    bitmap = lastCopied.thumbnail.asImageBitmap(),
                    contentDescription = stringResource(R.string.last_copied_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Button(onClick = lastCopied.onCopyAgain, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.copy_again))
            }
        }
    }
}
