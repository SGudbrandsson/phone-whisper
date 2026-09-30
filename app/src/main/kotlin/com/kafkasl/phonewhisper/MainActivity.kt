package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kafkasl.phonewhisper.ui.PhoneWhisperTheme
import com.kafkasl.phonewhisper.ui.SettingsEvent
import com.kafkasl.phonewhisper.ui.SettingsScreen
import com.kafkasl.phonewhisper.ui.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneWhisperTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, ::onEvent)
            }
        }
        if (savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        HistoryCleanupWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun onEvent(e: SettingsEvent) {
        when (e) {
            SettingsEvent.GrantMic -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            SettingsEvent.OpenAccessibility -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SettingsEvent.OpenHistory -> startActivity(Intent(this, HistoryActivity::class.java))
            SettingsEvent.OpenDiagnostics -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            SettingsEvent.ViewCrash -> { vm.onEvent(e); startActivity(Intent(this, DiagnosticsActivity::class.java)) }
            else -> vm.onEvent(e)
        }
    }
}
