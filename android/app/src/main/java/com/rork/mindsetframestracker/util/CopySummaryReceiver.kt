package com.rork.mindsetframestracker.util

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * Receives the "Copy summary" chooser action from the share sheet and copies
 * the progress text summary to the clipboard.
 */
class CopySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_SUMMARY_TEXT) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mindset Frames summary", text))
        // Android 13+ shows its own system "copied" confirmation overlay
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SUMMARY_TEXT = "com.rork.mindsetframestracker.EXTRA_SUMMARY_TEXT"
        const val ACTION_COPY_SUMMARY = "com.rork.mindsetframestracker.COPY_SUMMARY"
    }
}
