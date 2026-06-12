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

data class LastCopiedUi(val thumbnail: Bitmap?, val onCopyAgain: () -> Unit)

@Composable
fun MainScreen(lastCopied: LastCopiedUi?) {
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
