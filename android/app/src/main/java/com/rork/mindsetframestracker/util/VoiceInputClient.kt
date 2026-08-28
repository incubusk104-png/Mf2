package com.rork.mindsetframestracker.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Free, on-device (or Google-app-backed) speech-to-text using Android's
 * built-in SpeechRecognizer — no API key, no per-call cost.
 *
 * CAVEAT: on Huawei devices with no Google app installed, SpeechRecognizer
 * may report unavailable (isRecognitionAvailable() == false). That's fine —
 * check availability and hide the mic button rather than crash. A Huawei
 * ML Kit ASR fallback can be added later for those devices without
 * changing this class's call site.
 */
object VoiceInputClient {

    private const val TAG = "VoiceInput"

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening once and returns the best-guess transcript via
     * [onResult], or an empty string via [onError] on failure/no speech.
     * Caller owns the returned SpeechRecognizer's lifecycle — call
     * destroy() when done (e.g. in onDispose of a Composable effect).
     */
    fun startListening(
        context: Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ): SpeechRecognizer? {
        if (!isAvailable(context)) {
            onError("Voice input isn't available on this device.")
            return null
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onResult(text) else onError("Didn't catch that — try again.")
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Speech recognition error code: $error")
                onError("Didn't catch that — try again.")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        runCatching { recognizer.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening failed: ${it.message}")
                onError("Couldn't start voice input.")
            }
        return recognizer
    }
}
