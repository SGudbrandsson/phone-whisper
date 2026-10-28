package com.kafkasl.phonewhisper.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    report: String?,
    crashCount: Int,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onTestCrash: () -> Unit,
) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { confirm = "clear" }) { Text("Clear") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                (if (crashCount == 0) "No crashes recorded." else "$crashCount crash report${if (crashCount == 1) "" else "s"}.") +
                    " Copy or share this report to get help. It has no API key or transcript text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCopy, enabled = report != null, modifier = Modifier.weight(1f)) { Text("Copy report") }
                OutlinedButton(onClick = onShare, enabled = report != null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.padding(end = 8.dp))
                    Text("Share")
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(horizontal = 16.dp).weight(1f).fillMaxWidth(),
            ) {
                if (report == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else SelectionContainer {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
            TextButton(onClick = { confirm = "crash" }, modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally)) {
                Text("Test crash reporting")
            }
        }
    }
    when (confirm) {
        "clear" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete crash reports and logs?") },
            confirmButton = { TextButton(onClick = { confirm = null; onClear() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "crash" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Crash the app now?") },
            text = { Text("Checks that crash reports work. Reopen Phone Whisper and you should see the crash banner.") },
            confirmButton = { TextButton(onClick = onTestCrash) { Text("Crash") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
