package com.chandan.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chandan.assistant.MainViewModel

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun open(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Step ${page + 1} of 4", style = MaterialTheme.typography.labelLarge)
            when (page) {
                0 -> {
                    Text("Your phone assistant", style = MaterialTheme.typography.headlineMedium)
                    Text("Talk in Hindi or English. Simple commands run instantly on the phone. Harder ones use Gemini AI and can control any app's screen.")
                    Text("This app is only for you. After this setup, add your Gemini API key in Settings > AI.")
                }
                1 -> {
                    Text("Basic permissions", style = MaterialTheme.typography.headlineMedium)
                    Text("Microphone to hear you, phone to place calls, contacts to find people, location for place triggers, notifications for the status notification.")
                    Button(onClick = { launcher.launch(perms.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
                }
                2 -> {
                    Text("Accessibility service", style = MaterialTheme.typography.headlineMedium)
                    Text("This lets the assistant read the screen and tap, type and scroll. Android 13+ blocks it for sideloaded apps until you allow it:")
                    Text("1. Tap \"App info\" below.\n2. Tap the three dots (top right) > Allow restricted settings.\n3. Tap \"Accessibility settings\" below > Assistant > turn ON.")
                    OutlinedButton(onClick = {
                        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                    }, modifier = Modifier.fillMaxWidth()) { Text("App info") }
                    Button(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Accessibility settings")
                    }
                }
                else -> {
                    Text("Power-ups (optional)", style = MaterialTheme.typography.headlineMedium)
                    Text("Notification access lets me read and reply to notifications. Battery: no restrictions keeps me alive in the background.")
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Notification access")
                    }
                    OutlinedButton(onClick = {
                        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Battery: no restrictions") }
                    Text("Shizuku is optional. Install it, start it with wireless debugging, then tap Fix next to Shizuku in Settings.")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back") } else TextButton(onClick = { vm.finishOnboarding() }) { Text("Skip") }
            if (page < 3) Button(onClick = { page++ }) { Text("Next") } else Button(onClick = { vm.finishOnboarding() }) { Text("Finish") }
        }
    }
}
