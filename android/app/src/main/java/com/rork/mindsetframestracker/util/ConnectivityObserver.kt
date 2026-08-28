package com.rork.mindsetframestracker.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** True when the given context currently has an internet-capable network. */
private fun isCurrentlyOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Observes live connectivity as Compose state. Emits immediately with the
 * current status, then updates in real time as networks come and go.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = isCurrentlyOnline(context), context) {
        val flow = callbackFlow {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            if (cm == null) {
                trySend(true)
                awaitClose { }
                return@callbackFlow
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(true)
                }

                override fun onLost(network: Network) {
                    trySend(isCurrentlyOnline(context))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    trySend(
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        ),
                    )
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            trySend(isCurrentlyOnline(context))
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }
        flow.collect { value = it }
    }
}
