package com.chandan.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chandan.assistant.core.AssistantService
import com.chandan.assistant.data.Prefs
import com.chandan.assistant.ui.AssistantApp
import com.chandan.assistant.ui.AssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssistantTheme {
                val vm: MainViewModel = viewModel()
                val cmd = remember { intent?.getStringExtra("command") }
                LaunchedEffect(cmd) { if (cmd != null) vm.submit(cmd) }
                AssistantApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val p = Prefs(this)
        if (p.onboarded && p.keepRunning &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            AssistantService.start(this)
        }
    }
}
