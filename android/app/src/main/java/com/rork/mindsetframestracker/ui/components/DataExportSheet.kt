package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitDataExport
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.ExportKindCount
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.util.HabitShare

/**
 * "Export everything" — the sheet behind the Settings entry point.
 *
 * ## Why this sheet leads with a record-count table
 *
 * The failure this feature exists to end is a report that looks complete but is
 * not: the user asks for their data, receives a file, and never learns that
 * their logs, alarm history and imported activity were left out. A summary line
 * saying "exported successfully" would reproduce exactly that problem.
 *
 * So the first thing shown is the **per-record-type count and its verdict**,
 * computed from the live data before the file is written. The user can see
 * "3 habits · 412 check-ins · 96 alarm records · 28 activity records — nothing
 * omitted" and know what they are about to get, or see the omissions listed and
 * choose how to proceed. Nothing is written until they pick a format.
 *
 * ## Formats
 *
 * JSON is the complete, re-importable record. CSV is the same data flattened for
 * a spreadsheet. The report is the readable one. All three are rendered from the
 * same bundle, so they cannot disagree about what the data is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportSheet(
    data: AppData,
    appVersionName: String,
    appVersionCode: Int,
    onExport: (HabitShare.ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val s = appStrings()
    // Built once per opening: the counts must describe the same snapshot the
    // export will write, so re-computing them on every recomposition would let
    // the table and the file drift apart.
    val bundle = remember(data) {
        HabitDataExport.build(data, appVersionName = appVersionName, appVersionCode = appVersionCode)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                s.shExportAllTitle,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.shExportAllBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ── The completeness verdict ─────────────────────────────────────
            CompletenessVerdict(s, bundle.verification.isComplete, bundle.verification.totalRecords)

            Spacer(Modifier.height(12.dp))

            // ── Per-record-type counts ───────────────────────────────────────
            Text(
                s.shWhatIncluded,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            countRows(s).forEach { (label, count) ->
                CountRow(s, label, bundle.verification.counts.firstOrNull { it.kind == count })
            }

            // ── Omissions, if any (never hidden) ─────────────────────────────
            if (bundle.verification.omissions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Text(
                    s.shOmissionsTitle(bundle.verification.omissions.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.shOmissionsBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                s.shChooseFormat,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            FormatButton(
                icon = Icons.Outlined.Code,
                title = s.shFormatJsonTitle,
                description = s.shFormatJsonDesc,
                onClick = { onExport(HabitShare.ExportFormat.JSON) },
            )
            Spacer(Modifier.height(8.dp))
            FormatButton(
                icon = Icons.Outlined.TableChart,
                title = s.shFormatCsvTitle,
                description = s.shFormatCsvDesc,
                onClick = { onExport(HabitShare.ExportFormat.CSV) },
            )
            Spacer(Modifier.height(8.dp))
            FormatButton(
                icon = Icons.Outlined.Description,
                title = s.shFormatReportTitle,
                description = s.shFormatReportDesc,
                onClick = { onExport(HabitShare.ExportFormat.REPORT) },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                s.shAlarmPerTimeNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.shTokenNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The headline verdict: complete, or exactly how many records are missing. */
@Composable
private fun CompletenessVerdict(s: AppStrings, isComplete: Boolean, totalRecords: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isComplete) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            )
            .padding(14.dp),
    ) {
        Icon(
            imageVector = if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.Code,
            contentDescription = null,
            tint = if (isComplete) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = if (isComplete) s.shVerifiedComplete else s.shSomeOmitted,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isComplete) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = if (isComplete) {
                    s.shAllRecordsWritten(totalRecords)
                } else {
                    s.shFileListsOmitted
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isComplete) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** One "Habits 3 ✓" line. */
@Composable
private fun CountRow(s: AppStrings, label: String, count: ExportKindCount?) {
    if (count == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (count.omitted > 0) s.shCountOmitted(count.inExport, count.omitted)
            else count.inExport.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (count.omitted > 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Human label for each record kind, in the order they are shown. */
private fun countRows(s: AppStrings): List<Pair<String, String>> = listOf(
    s.shKindHabits to "habit",
    s.shKindCheckIns to "checkIn",
    s.shKindLogs to "habitLog",
    s.shKindAlarms to "alarmEvent",
    s.shKindActivity to "activityRecord",
    s.shKindReflections to "reflection",
)

/** One format choice: icon, title, one-line explanation, and a share action. */
@Composable
private fun FormatButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.IosShare,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
