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
import com.rork.mindsetframestracker.ui.appStrings
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
    // The sheet's text comes from the app's string table, so it follows the
    // language like every other screen. It previously held literals, so with a
    // non-English language selected this sheet did not translate.
    val s = appStrings()

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
            report(s.shNothingNew, isError = false)
        } else {
            report(null, isError = false)
        }
    }

    fun handleCode(raw: String) {
        when (val result = HabitShareCodec.decode(raw)) {
            is HabitShareCodec.DecodeResult.Success -> planFromPayload(result.payload, s.shLabelSharedCode)
            HabitShareCodec.DecodeResult.NotAShareCode ->
                report(s.shNotACode, true)
            HabitShareCodec.DecodeResult.Corrupt ->
                report(s.shBadCode, true)
            is HabitShareCodec.DecodeResult.TooNew ->
                report(s.shCodeTooNew(result.schemaVersion), true)
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
                report(s.shCouldntReadFile, true)
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
                }.onSuccess { planFromPayload(it, s.shLabelExportFile) }
                    .onFailure { report(s.shNotAnExport, true) }
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
                s.shShareTitle,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.shShareBody,
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
            Text(s.shSendHabits, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val code = HabitShare.shareHabitCode(
                        context = context,
                        data = data,
                        includeHistory = true,
                        appVersionName = appVersionName,
                        appVersionCode = appVersionCode,
                        onError = { report(s.shCouldntBuildCode(it), true) },
                    )
                    if (code != null) {
                        sharedCode = code
                        report(s.shCodeReady, false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(s.shShareFull, modifier = Modifier.padding(start = 8.dp))
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
                        onError = { report(s.shCouldntBuildCode(it), true) },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(s.shShareListOnly, modifier = Modifier.padding(start = 8.dp))
            }

            if (sharedCode != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(s.shShareFileLabel, sharedCode.orEmpty()),
                        )
                        report(s.shCodeCopied, false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(s.shCopyCode, modifier = Modifier.padding(start = 8.dp))
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
                        onError = { report(s.shCouldntWriteFile(it), true) },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(s.shShareAsFile, modifier = Modifier.padding(start = 8.dp))
            }

            // ── Import ───────────────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))
            Text(s.shReceiveTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                s.shReceiveBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = pastedCode,
                onValueChange = { pastedCode = it },
                label = { Text(s.shPasteLabel) },
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
                Text(s.shCheckCode)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(s.shOpenFile, modifier = Modifier.padding(start = 8.dp))
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
                        s.shReadyToImport(pendingLabel),
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
                            s.shImportSummaryDuplicates(plan.duplicateHabits.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.renamedHabits.isNotEmpty()) {
                        Text(
                            s.shImportSummaryRenamed(plan.renamedHabits.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.totalNewRecords > 0) {
                        Text(
                            s.shImportSummaryRecords(
                                plan.totalNewRecords,
                                plan.newCheckIns,
                                plan.newLogs,
                                plan.newAlarmEvents,
                            ),
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
                                report(s.shImported, false)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(s.shImport)
                        }
                        TextButton(
                            onClick = {
                                pendingPlan = null
                                report(null, false)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(s.shCancel)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                s.shImportNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
