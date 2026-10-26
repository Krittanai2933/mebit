package com.mebit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mebit.app.navigation.MovieRoute

@Composable
fun ScreenStub(
    route: MovieRoute,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("screen_" + route.path),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = route.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("eyebrow"))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = route.titleTh, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag("title"))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = route.bodyTh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("body"))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenCatalog, modifier = Modifier.fillMaxWidth().testTag("open_catalog")) {
                Text("รายการหน้าทั้งหมด (debug)")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).testTag("back")) { Text("กลับ") }
                }
                Button(onClick = onNext, modifier = Modifier.weight(1f).testTag("next")) {
                    Text("ต่อไป", textAlign = TextAlign.Center)
                }
            }
        }
    }
}
