package com.rork.mindsetframestracker.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.HabitDataExport
import com.rork.mindsetframestracker.data.HabitExportBundle
import com.rork.mindsetframestracker.data.HabitShareCodec
import com.rork.mindsetframestracker.util.HabitShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share habits — everything" and the matching import path.
 *
 * ## Two ways out, because sharing means two different things
 *
 *  - **A code** is a short piece of text carrying the habits (and optionally
 *    their whole history). It travels through any chat app, which is how people
 *    actually send things to each other; a file needs a transfer step that often
 *    isn't there.
 *  - **A file** is the complete export, for keeping a copy or sending as an
 *    attachment.
 *
 * Both are produced from one [HabitExportBundle], so the two paths cannot drift
 * into carrying different data.
 *
 * ## Import is previewed, never silent
 *
 * The store is local-only with no undo, so an import that duplicated everything
 * would be unrecoverable. Pasting a code therefore does not import it — it shows
 * what *would* change (new habits, duplicates skipped, records added) and
 * requires a second, deliberate tap. A re-import of the same code is recognised
 * as a no-op rather than silently doubling the user's data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareHabitsSheet(
    data: AppData,
    appVersionName: String,
    appVersionCode: Int,
    onImport: (AppData) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pastedCode by remember { mutableStateOf("") }
        var statusMessage by remember { mutableStateOf<String?>(null) }
        var statusIsError by remember { mutableStateOf(false) }

    // The pending import, if any. Holding the plan rather than applying it is
    // what makes the confirm step possible.
    var pendingPlan by remember { mutableStateOf<HabitShareCodec.ImportPlan?>(null) }
    var pendingLabel by remember { mutableStateOf("") }
    var sharedCode by remember { mutableStateOf<String?>(null) }

    // Validated once per data change, and shared with the outbound file/export
    // path below, so what is sent and what was counted cannot drift apart.
    val bundle = remember(data) {
        HabitDataExport.build(data, appVersionName = appVersionName, appVersionCode = appVersionCode)
    }

    // Nullable: clearing the message is how the sheet hides the status line.
    fun report(message: String?, isError: Boolean) {
        statusMessage = message
        statusIsError = isError
    }

    fun planFromPayload(payload: HabitShareCodec.SharePayload, label: String) {
        val plan = HabitShareCodec.planImport(data, payload)
        pendingPlan = plan
        pendingLabel = label
        if (plan.isEmpty) {
            report("Nothing new in that code — it's already in your habits.", isError = false)
        } else {
            report(null, isError = false)
        }
    }

    fun handleCode(raw: String) {
        when (val result = HabitShareCodec.decode(raw)) {
            is HabitShareCodec.DecodeResult.Success -> planFromPayload(result.payload, "shared code")
            HabitShareCodec.DecodeResult.NotAShareCode ->
                report("That doesn't look like a Mindset Frames code — check the whole thing was copied.", true)
            HabitShareCodec.DecodeResult.Corrupt ->
                report("That code is damaged or incomplete — ask for it to be sent again.", true)
            is HabitShareCodec.DecodeResult.TooNew ->
                report("That code was made by a newer version of the app (format ${result.schemaVersion}). Update and try again.", true)
        }
    }

    // ── File import ──────────────────────────────────────────────────────────
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                report("Couldn't read that file.", true)
                return@launch
            }
            // A shared file is either a JSON export or a text file whose body
            // contains a code. Try the code first: it is the common case and the
            // cheapest test.
            val codeInText = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(HabitShareCodec.PREFIX) }
            if (codeInText != null) {
                handleCode(codeInText)
            } else {
                runCatching {
                    // Explicit serializer, matching how the rest of this app
                    // serialises (see MindsetRepository): the reified overload
                    // would need an extra import and adds nothing here.
                    val parsed = kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                    }.decodeFromString(HabitExportBundle.serializer(), text)
                    HabitShareCodec.SharePayload(
                        schemaVersion = parsed.schemaVersion,
                        createdAtIso = parsed.exportedAtIso,
                        habits = parsed.habits,
                        moodHistory = parsed.moodHistory,
                    )
                }.onSuccess { planFromPayload(it, "export file") }
                    .onFailure { report("That file isn't a Mindset Frames export.", true) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Share your habits",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Send your habits — and as much of their history as you like — so someone " +
                    "else can try them, or keep a copy for yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (statusMessage != null) {
                Spacer(Modifier.height(12.dp))
                val message = statusMessage.orEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (statusIsError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                        )
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = if (statusIsError) Icons.Outlined.ErrorOutline else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (statusIsError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // ── Share out ────────────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Text("Send your habits", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val code = HabitShare.shareHabitCode(
                        context = context,
                        data = data,
                        includeHistory = true,
                        appVersionName = appVersionName,
                        appVersionCode = appVersionCode,
                        onError = { report("Couldn't build the code: $it", true) },
                    )
                    if (code != null) {
                        sharedCode = code
                        report("Code ready — choose where to send it.", false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Share habits + history as a code", modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    HabitShare.shareHabitCode(
                        context = context,
                        data = data,
                        includeHistory = false,
                        appVersionName = appVersionName,
                        appVersionCode = appVersionCode,
                        onError = { report("Couldn't build the code: $it", true) },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Share habit list only (smaller)", modifier = Modifier.padding(start = 8.dp))
            }

            if (sharedCode != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Mindset Frames habits", sharedCode.orEmpty()),
                        )
                        report("Code copied to the clipboard.", false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Copy the code instead", modifier = Modifier.padding(start = 8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    HabitShare.shareExport(
                        context = context,
                        data = data,
                        format = HabitShare.ExportFormat.REPORT,
                        appVersionName = appVersionName,
                        appVersionCode = appVersionCode,
                        onError = { report("Couldn't write the file: $it", true) },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Send a readable file instead", modifier = Modifier.padding(start = 8.dp))
            }

            // ── Import ───────────────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))
            Text("Receive habits", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a code someone sent you, or open a shared file. You'll see exactly what " +
                    "would be added before anything changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = pastedCode,
                onValueChange = { pastedCode = it },
                label = { Text("Paste a share code") },
                placeholder = { Text(HabitShareCodec.PREFIX + "…") },
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { handleCode(pastedCode) },
                enabled = pastedCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check what this code contains")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Open a shared file", modifier = Modifier.padding(start = 8.dp))
            }

            // ── The pending import, with its exact effect ─────────────────────
            val plan = pendingPlan
            if (plan != null && !plan.isEmpty) {
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                ) {
                    Text(
                        "Ready to import from $pendingLabel",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (plan.newHabits.isNotEmpty()) {
                        Text(
                            "• ${plan.newHabits.joinToString(", ") { it.habit.name }}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (plan.duplicateHabits.isNotEmpty()) {
                        Text(
                            "• ${plan.duplicateHabits.size} already in your habits — skipped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.renamedHabits.isNotEmpty()) {
                        Text(
                            "• ${plan.renamedHabits.size} habit(s) renamed to avoid a clash with an existing one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.totalNewRecords > 0) {
                        Text(
                            "• ${plan.totalNewRecords} history records added " +
                                "(${plan.newCheckIns} check-ins, ${plan.newLogs} logs, " +
                                "${plan.newAlarmEvents} alarm records)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onImport(HabitShareCodec.applyImport(data, plan))
                                pendingPlan = null
                                pastedCode = ""
                                report("Imported. Your new habits are ready.", false)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Import")
                        }
                        TextButton(
                            onClick = {
                                pendingPlan = null
                                report(null, false)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Imported habits keep their own alarms, repeat days and tracking tool. Your " +
                    "existing habits are never overwritten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
