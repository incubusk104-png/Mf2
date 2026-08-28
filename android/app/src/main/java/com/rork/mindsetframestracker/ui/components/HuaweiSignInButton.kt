package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.R

/**
 * Official "Sign in with HUAWEI ID" button following Huawei Account Kit
 * brand guidelines — the SDK's red container with the official white
 * HUAWEI lockup (`ic_huawei_id_logo`, the exact 24dp head asset bundled
 * with Account Kit's own HuaweiIdAuthButton) and white title text.
 * Shows a white spinner while the credential exchange is running.
 */
@Composable
fun HuaweiSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    label: String = "Sign in with HUAWEI ID",
) {
    // hwid_auth_button_color_red from the Account Kit SDK resources.
    val huaweiRed = Color(0xFFEF484B)

    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = huaweiRed,
            contentColor = Color.White,
            disabledContainerColor = huaweiRed.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.65f),
        ),
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_huawei_id_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .alpha(if (enabled) 1f else 0.55f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
