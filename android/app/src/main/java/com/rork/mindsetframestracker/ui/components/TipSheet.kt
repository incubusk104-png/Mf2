package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipSheet(
    onDismiss: () -> Unit,
    onSendTip: (String) -> Unit, // Passes the selected Product ID ("tip_small", "tip_medium", "tip_large")
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Support App Development",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "If you enjoy tracking your mindset frames, consider leaving a small tip to help keep updates rolling!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            // Tip Tier Buttons mapped to your Huawei IAP Product IDs
            Button(
                onClick = { onSendTip("tip_small") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("☕ Small Tip ($1.00)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onSendTip("tip_medium") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("🚀 Medium Tip ($3.00)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onSendTip("tip_large") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("👑 Large Tip ($5.00)")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Maybe later")
            }
        }
    }
}
