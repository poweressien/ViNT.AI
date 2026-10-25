package ng.pidgingpt.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConnectivityMonitor {
    val state: StateFlow<NetworkState>
    /** Idempotent. Register in onStart. */
    fun start()
    /** Idempotent. Unregister in onStop so the callback never leaks. */
    fun stop()
}

/**
 * Tracks the DEFAULT network with a NetworkCallback. "Validated" comes from Android's own internet check, so a Wi-Fi network with no internet,
 * a captive portal, or a DNS failure is reported as connected-but-not-validated, and Wi-Fi with mobile data switched off still counts as online.
 * Needs only ACCESS_NETWORK_STATE.
 */
class AndroidConnectivityMonitor(ctx: Context) : ConnectivityMonitor {
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(NetworkState.UNKNOWN)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _state.value = from(caps) }
        override fun onLost(network: Network) { _state.value = NetworkState.NONE }
        override fun onUnavailable() { _state.value = NetworkState.NONE }
    }

    @Synchronized override fun start() {
        if (registered) return
        // Seed with the current state so the first message after launch does not wait for the first callback.
        _state.value = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.let { from(it) } ?: NetworkState.NONE }.getOrDefault(NetworkState.UNKNOWN)
        registered = runCatching { cm.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
    }

    @Synchronized override fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    companion object {
        fun from(c: NetworkCapabilities): NetworkState {
            val transport = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportKind.WIFI
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportKind.CELLULAR
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportKind.ETHERNET
                else -> TransportKind.OTHER
            }
            val hasInternet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = hasInternet && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            return NetworkState(known = true, connected = hasInternet, validated = validated, transport = transport)
        }
    }
}
