package com.example.constructiontracker

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.time.format.DateTimeFormatter

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
private fun Field(label: String, v: String, hint: String = "", password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        v, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        placeholder = { if (hint.isNotEmpty()) Text(hint) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: CostViewModel, current: Project?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SmbPrefs(ctx) }
    var cfg by remember { mutableStateOf(prefs.load()) }
    LaunchedEffect(cfg) { prefs.save(cfg) }
    var auto by remember { mutableStateOf(prefs.autoOn()) }
    var sched by remember { mutableStateOf(prefs.schedule()) }
    var pickSchedTime by remember { mutableStateOf(false) }
    val lastAuto = remember { prefs.lastAuto() }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun setSched(n: Schedule) {
        sched = n; prefs.setSchedule(n)
        if (auto) BackupScheduler.schedule(ctx, n)
    }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var exportAll by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> String) {
        scope.launch {
            busy = true; status = "$label…"
            status = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "$label failed: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    /** Builds the workbook and opens the Android share sheet (WhatsApp, email, Drive, ...). */
    fun shareExport(all: Boolean) {
        run("Export") {
            val bytes = vm.exportXlsx(if (all) null else current?.id)
            val base = if (all) "ConstructionTracker_all_projects" else (current?.name ?: "project")
            val name = base.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".xlsx"
            val dir = File(ctx.cacheDir, "exports").also { it.mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = XLSX_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) { ctx.startActivity(Intent.createChooser(send, "Share Excel file")) }
            "Choose an app to send $name"
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run("Import") {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file")
            vm.importXlsx(bytes)
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri: Uri? ->
        if (uri != null) run("Export") {
            val bytes = vm.exportXlsx(if (exportAll) null else current?.id)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't open the file")
            "Excel file saved"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)

        AppearanceCard()
        CurrencyCard(vm, current)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share as Excel file", style = MaterialTheme.typography.titleMedium)
                Text("One sheet per project with all entries. Opens the share sheet so you can send it on WhatsApp or email, or save it to Drive.",
                    style = MaterialTheme.typography.bodySmall)
                ExportOptions()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ exportAll = false; shareExport(false) },
                        Modifier.weight(1f), enabled = current != null && !busy) { Text("This project") }
                    Button({ exportAll = true; shareExport(true) },
                        Modifier.weight(1f), enabled = !busy) { Text("All projects") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import from Excel", style = MaterialTheme.typography.titleMedium)
                Text("Choose a file exported from this app. Each sheet becomes a project, matched by name or created if new. Entries already in the app are skipped, and nothing is deleted.",
                    style = MaterialTheme.typography.bodySmall)
                Button({ importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Choose Excel file") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup to SMB share", style = MaterialTheme.typography.titleMedium)
                Text("Saves all projects to a network folder. Your phone must be on the same network as the share. The password is stored encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall)
                Field("Server", cfg.host, "192.168.1.10") { cfg = cfg.copy(host = it) }
                Field("Share name", cfg.share, "Backups") { cfg = cfg.copy(share = it) }
                Field("Folder (optional)", cfg.folder, "construction") { cfg = cfg.copy(folder = it) }
                Field("Username (blank for guest)", cfg.user) { cfg = cfg.copy(user = it) }
                Field("Password", cfg.pass, password = true) { cfg = cfg.copy(pass = it) }
                Field("Domain (optional)", cfg.domain) { cfg = cfg.copy(domain = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        prefs.save(cfg); run("Connection test") { SmbBackup.test(cfg); "Connected to the share" }
                    }, Modifier.weight(1f), enabled = !busy) { Text("Test") }
                    Button({
                        prefs.save(cfg)
                        askNotif()
                        run("Backup") {
                            val name = SmbBackup.newName()
                            SmbBackup.upload(cfg, name, vm.backupBytes())
                            Notifier.show(ctx, "Backup complete", "Saved $name to the share")
                            "Backed up as $name"
                        }
                    }, Modifier.weight(1f), enabled = !busy) { Text("Back up now") }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scheduled backup", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (auto) "Next: " + BackupScheduler.nextRun(sched).format(DateTimeFormatter.ofPattern("EEE dd MMM, h:mm a"))
                            else "Off",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(auto, { on ->
                        if (on && (cfg.host.isBlank() || cfg.share.isBlank())) {
                            status = "Enter the server and share name first"
                        } else {
                            auto = on; prefs.save(cfg); prefs.setAuto(on)
                            if (on) { BackupScheduler.schedule(ctx, sched); askNotif() } else BackupScheduler.cancel(ctx)
                            status = if (on) "Scheduled backup is on" else "Scheduled backup is off"
                        }
                    })
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Daily", "Weekly").forEachIndexed { i, l ->
                        SegmentedButton(sched.weekly == (i == 1), { setSched(sched.copy(weekly = i == 1)) },
                            SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sched.weekly) {
                        Box(Modifier.weight(1f)) {
                            var dayOpen by remember { mutableStateOf(false) }
                            OutlinedButton({ dayOpen = true }, Modifier.fillMaxWidth()) {
                                Text(DayOfWeek.of(sched.day).getDisplayName(TextStyle.FULL, Locale.getDefault()))
                            }
                            DropdownMenu(dayOpen, { dayOpen = false }) {
                                (1..7).forEach { d ->
                                    DropdownMenuItem(
                                        { Text(DayOfWeek.of(d).getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                                        { setSched(sched.copy(day = d)); dayOpen = false })
                                }
                            }
                        }
                    }
                    OutlinedButton({ pickSchedTime = true }, Modifier.weight(1f)) {
                        Text(LocalTime.of(sched.hour, sched.minute).format(DateTimeFormatter.ofPattern("h:mm a")))
                    }
                }
                if (lastAuto.isNotEmpty()) Text("Last scheduled backup: $lastAuto", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ prefs.save(cfg); confirmRestore = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                    Text("Restore latest backup")
                }
            }
        }
    }

    if (pickSchedTime) {
        val st = rememberTimePickerState(sched.hour, sched.minute, false)
        AlertDialog(
            onDismissRequest = { pickSchedTime = false },
            text = { TimePicker(st) },
            confirmButton = {
                TextButton({ setSched(sched.copy(hour = st.hour, minute = st.minute)); pickSchedTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickSchedTime = false }) { Text("Cancel") } })
    }

    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore latest backup?") },
        text = { Text("This replaces all data on this phone with the newest backup on the share.") },
        confirmButton = {
            TextButton({
                confirmRestore = false
                run("Restore") {
                    val (name, bytes) = SmbBackup.downloadLatest(cfg)
                    vm.restore(bytes)
                    "Restored from $name"
                }
            }) { Text("Restore") }
        },
        dismissButton = { TextButton({ confirmRestore = false }) { Text("Cancel") } })
}
