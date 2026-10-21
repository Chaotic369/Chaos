package com.chandan.assistant.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chandan.assistant.MainViewModel
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ShizukuShell
import com.chandan.assistant.data.Alias
import com.chandan.assistant.data.Nickname

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val apiKey by vm.apiKey.collectAsState()
    val model by vm.model.collectAsState()
    val lang by vm.lang.collectAsState()
    val aliases by vm.aliases.collectAsState()
    val nicknames by vm.nicknames.collectAsState()

    var keyField by remember(apiKey) { mutableStateOf(apiKey) }
    var modelField by remember(model) { mutableStateOf(model) }
    var langField by remember(lang) { mutableStateOf(lang) }
    var showAlias by remember { mutableStateOf(false) }
    var showNick by remember { mutableStateOf(false) }

    val perms = buildList {
        add("Microphone" to Manifest.permission.RECORD_AUDIO)
        add("Phone calls" to Manifest.permission.CALL_PHONE)
        add("Contacts" to Manifest.permission.READ_CONTACTS)
        add("Location (triggers)" to Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) add("Background location (triggers)" to Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add("Notifications" to Manifest.permission.POST_NOTIFICATIONS)
    }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val granted = remember(tick) {
        perms.associate { it.second to (ContextCompat.checkSelfPermission(ctx, it.second) == 0) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Section("AI") {
            OutlinedTextField(
                keyField, { keyField = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                modelField, { modelField = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Model name") }
            )
            OutlinedTextField(
                langField, { langField = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Speech language (en-IN or hi-IN)") }
            )
            Button(onClick = { vm.saveSettings(keyField, modelField, langField) }) { Text("Save") }
            Text(
                "The key is stored encrypted on this phone. When AI is used, commands and screen content are sent to Google's Gemini API.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section("Permissions") {
            perms.forEach { (label, perm) ->
                val ok = granted[perm] == true
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        Text(
                            if (ok) "Granted" else "Needed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!ok) OutlinedButton(onClick = { launcher.launch(arrayOf(perm)) }) { Text("Fix") }
                }
            }
            val a11yOn = remember(tick) { AssistantAccessibilityService.instance != null }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Accessibility service")
                    Text(
                        if (a11yOn) "On" else "Off. On Android 13+ first allow restricted settings (see README)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a11yOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (!a11yOn) OutlinedButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Fix") }
            }
            val notifOn = remember(tick) {
                androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            }
            StatusRow("Notification access", notifOn, "Needed to read/reply to notifications and for notification triggers") {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            val shizukuOn = remember(tick) { ShizukuShell.available() }
            StatusRow("Shizuku (optional)", shizukuOn, "Off. Faster Wi-Fi, Bluetooth and force-stop") {
                if (!ShizukuShell.running()) {
                    android.widget.Toast.makeText(ctx, "Start Shizuku first (wireless debugging), then tap Fix again", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    ShizukuShell.requestPermission()
                }
            }
            val battOn = remember(tick) {
                (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(ctx.packageName)
            }
            StatusRow("Battery: no restrictions", battOn, "Recommended so the service stays alive") {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + ctx.packageName)
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val exactOn = remember(tick) {
                    (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
                }
                StatusRow("Exact alarms", exactOn, "Needed for on-time triggers") {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + ctx.packageName)
                        )
                    )
                }
            }
        }

        Section("Contacts and aliases") {
            if (aliases.isEmpty()) Text("No aliases yet. Add one so \"call shekher bhaiya\" works.", style = MaterialTheme.typography.bodySmall)
            aliases.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name)
                        Text(a.phone.ifBlank { "no phone" }, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.deleteAlias(a) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
            OutlinedButton(onClick = { showAlias = true }) { Text("Add alias") }
        }

        Section("Nicknames") {
            if (nicknames.isEmpty()) Text("Nickname to URL or app, e.g. cmp -> https://example.com", style = MaterialTheme.typography.bodySmall)
            nicknames.forEach { n ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name)
                        Text(n.target, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.deleteNickname(n) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
            OutlinedButton(onClick = { showNick = true }) { Text("Add nickname") }
        }

        Section("Assistant service") {
            val keep by vm.keepRunning.collectAsState()
            val vstop by vm.voiceStop.collectAsState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep running with a notification", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = keep, onCheckedChange = { vm.setKeepRunning(it) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Say \"stop\" to cancel tasks (uses the mic)", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = vstop, onCheckedChange = { vm.setVoiceStop(it) })
            }
        }

        Section("About") {
            Text("Assistant 0.5.0 · Phase 5: system control, notifications, triggers, onboarding.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAlias) AliasDialog({ showAlias = false }) { vm.saveAlias(it); showAlias = false }
    if (showNick) NicknameDialog({ showNick = false }) { vm.saveNickname(it); showNick = false }
}

@Composable
private fun AliasDialog(onDismiss: () -> Unit, onSave: (Alias) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var tg by remember { mutableStateOf("") }
    var ig by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (shekhar bhaiya)") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(tg, { tg = it }, label = { Text("Telegram username") }, singleLine = true)
                OutlinedTextField(ig, { ig = it }, label = { Text("Instagram handle") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(Alias(name = name.trim(), phone = phone.trim(), email = email.trim(), telegram = tg.trim(), instagram = ig.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NicknameDialog(onDismiss: () -> Unit, onSave: (Nickname) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add nickname") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nickname (cmp)") }, singleLine = true)
                OutlinedTextField(target, { target = it }, label = { Text("URL or app name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && target.isNotBlank()) onSave(Nickname(name = name.trim(), target = target.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatusRow(title: String, on: Boolean, note: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                if (on) "On" else note,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        if (!on) OutlinedButton(onClick = onFix) { Text("Fix") }
    }
}
