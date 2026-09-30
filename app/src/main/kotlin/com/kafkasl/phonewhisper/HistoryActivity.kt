package com.kafkasl.phonewhisper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kafkasl.phonewhisper.ui.HistoryScreen
import com.kafkasl.phonewhisper.ui.HistoryViewModel
import com.kafkasl.phonewhisper.ui.PhoneWhisperTheme

/** Past dictations: tap to copy, retry failed ones, long-press for more. */
class HistoryActivity : ComponentActivity() {
    private val vm: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneWhisperTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                HistoryScreen(
                    state,
                    onBack = ::finish,
                    onTap = vm::tap,
                    onCopy = vm::copy,
                    onRetry = vm::retry,
                    onDelete = { vm.delete(it) },
                    onClearAll = { vm.clearAll() },
                    onMessageShown = vm::messageShown,
                )
            }
        }
    }
}
