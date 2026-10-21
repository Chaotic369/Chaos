package com.chandan.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chandan.assistant.MainViewModel
import com.chandan.assistant.data.Trigger
import com.google.android.gms.location.LocationServices

private fun describeTrigger(t: Trigger): String = when (t.type) {
    "time" -> "At ${t.time}" + if (t.days.isBlank()) " every day" else " on days ${t.days}"
    "location" -> "When I ${t.event} (${"%.4f".format(t.lat)}, ${"%.4f".format(t.lng)}) r=${t.radius.toInt()}m"
    else -> "Notification" + (if (t.notifApp.isNotBlank()) " from ${t.notifApp}" else "") +
        (if (t.notifText.isNotBlank()) " containing \"${t.notifText}\"" else "")
}

@Composable
fun TriggersScreen(vm: MainViewModel) {
    val triggers by vm.triggers.collectAsState()
    var add by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (triggers.isEmpty()) {
            EmptyState(
                Icons.Filled.Schedule, "No triggers yet",
                "Run a command at a time, when you arrive or leave a place, or when a notification arrives."
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(triggers, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(describeTrigger(t), style = MaterialTheme.typography.bodySmall)
                            Text("Runs: ${t.command}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = t.enabled, onCheckedChange = { vm.saveTrigger(t.copy(enabled = it)) })
                        IconButton(onClick = { vm.deleteTrigger(t) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { add = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add trigger") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (add) AddTriggerDialog(onDismiss = { add = false }) {
        vm.saveTrigger(it)
        add = false
    }
}

@Composable
private fun AddTriggerDialog(onDismiss: () -> Unit, onSave: (Trigger) -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf("time") }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:00") }
    var days by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("150") }
    var event by remember { mutableStateOf("arrive") }
    var app by remember { mutableStateOf("") }
    var contains by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add trigger") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "time", { type = "time" }, label = { Text("Time") })
                    FilterChip(type == "location", { type = "location" }, label = { Text("Place") })
                    FilterChip(type == "notification", { type = "notification" }, label = { Text("Notification") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                when (type) {
                    "time" -> {
                        OutlinedTextField(time, { time = it }, label = { Text("Time (24h HH:mm)") }, singleLine = true)
                        OutlinedTextField(days, { days = it }, label = { Text("Days (blank = daily; 1=Sun..7=Sat, e.g. 2,3,4,5,6)") })
                    }
                    "location" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(event == "arrive", { event = "arrive" }, label = { Text("Arrive") })
                            FilterChip(event == "leave", { event = "leave" }, label = { Text("Leave") })
                        }
                        OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true)
                        OutlinedTextField(lng, { lng = it }, label = { Text("Longitude") }, singleLine = true)
                        OutlinedTextField(radius, { radius = it }, label = { Text("Radius (meters)") }, singleLine = true)
                        OutlinedButton(onClick = {
                            try {
                                LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { l ->
                                    if (l != null) {
                                        lat = l.latitude.toString()
                                        lng = l.longitude.toString()
                                    } else {
                                        err = "No recent location. Open Maps once, then try again."
                                    }
                                }
                            } catch (e: SecurityException) {
                                err = "Allow Location in Settings first."
                            }
                        }) { Text("Use my current location") }
                    }
                    else -> {
                        OutlinedTextField(app, { app = it }, label = { Text("App name contains (blank = any)") }, singleLine = true)
                        OutlinedTextField(contains, { contains = it }, label = { Text("Text contains (blank = any)") }, singleLine = true)
                    }
                }
                OutlinedTextField(
                    command, { command = it },
                    label = { Text("Command to run (e.g. turn on wifi, open chrome)") }
                )
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = when {
                    command.isBlank() -> "Enter a command"
                    type == "time" && !Regex("^\\d{1,2}:\\d{2}$").matches(time.trim()) -> "Time must look like 07:30"
                    type == "location" && (lat.toDoubleOrNull() == null || lng.toDoubleOrNull() == null) -> "Enter latitude and longitude"
                    else -> ""
                }
                if (err.isEmpty()) {
                    onSave(
                        Trigger(
                            name = name.ifBlank { command.take(24) }, type = type, time = time.trim(), days = days.trim(),
                            lat = lat.toDoubleOrNull() ?: 0.0, lng = lng.toDoubleOrNull() ?: 0.0,
                            radius = radius.toFloatOrNull() ?: 150f, event = event,
                            notifApp = app.trim(), notifText = contains.trim(), command = command.trim()
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
