package com.kafkasl.phonewhisper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kafkasl.phonewhisper.ui.DictionaryScreen
import com.kafkasl.phonewhisper.ui.DictionaryUiState
import com.kafkasl.phonewhisper.ui.PhoneWhisperTheme

/** Edits the dictionary. Small enough to keep its state here; every change is saved at once. */
class DictionaryActivity : ComponentActivity() {
    private val settings by lazy { AppSettings(this) }
    private var state by mutableStateOf(DictionaryUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        load()
        setContent {
            PhoneWhisperTheme {
                DictionaryScreen(
                    state,
                    onBack = ::finish,
                    onSave = { i, e -> update { list -> if (i < 0) list + e else list.toMutableList().also { it[i] = e } } },
                    onDelete = { i -> update { list -> list.filterIndexed { j, _ -> j != i } } },
                    onSendHints = { settings.sendVocabularyHints = it; load() },
                    onRetryHints = { settings.hintsRejectedHere = false; load() },
                )
            }
        }
    }

    private fun update(change: (List<VocabEntry>) -> List<VocabEntry>) {
        settings.vocabulary = change(settings.vocabulary)
        load()
    }

    private fun load() {
        state = DictionaryUiState(
            entries = settings.vocabulary,
            sendHints = settings.sendVocabularyHints,
            hintsRejectedFor = settings.sttModel.takeIf { settings.hintsRejectedHere },
        )
    }
}
