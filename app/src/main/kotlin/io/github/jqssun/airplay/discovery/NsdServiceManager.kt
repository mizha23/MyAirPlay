package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    enum class MdnsStatus {
        /** Nothing registered (yet). */
        IDLE,
        /** First registration attempt in flight. */
        REGISTERING,
        /** Every requested service is registered and discoverable. */
        REGISTERED,
        /** At least one service failed and a retry is scheduled. */
        RETRYING,
        /** Some services registered, at least one permanently failed. */
        DEGRADED,
        /** No service registered; at least one exhausted its retries. */
        FAILED,
    }

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val pendingRetries = mutableMapOf<String, Runnable>()
    private val attempts = mutableMapOf<String, Int>()
    private val registeredTypes = mutableSetOf<String>()
    private val failedTypes = mutableSetOf<String>()
    private var expectedTypes = setOf<String>()

    private val _status = MutableStateFlow(MdnsStatus.IDLE)
    val status: StateFlow<MdnsStatus> = _status.asStateFlow()

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun register(services: List<MdnsService>) {
        expectedTypes = services.map { it.type }.toSet()
        registeredTypes.clear()
        failedTypes.clear()
        attempts.clear()
        _status.value = MdnsStatus.REGISTERING
        services.forEach(::_register)
    }

    private fun _register(s: MdnsService) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            port = s.port
            s.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
                registeredTypes += s.type
                failedTypes -= s.type
                _refreshStatus()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code")
                registrations.remove(this)
                _scheduleRetry(s)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} unregistered")
                registeredTypes -= s.type
                _refreshStatus()
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
    }

    private fun _scheduleRetry(s: MdnsService) {
        val attempt = (attempts[s.type] ?: 0) + 1
        attempts[s.type] = attempt
        if (attempt > MAX_ATTEMPTS) {
            Log.e(TAG, "${s.type} registration failed after $MAX_ATTEMPTS attempts; giving up")
            failedTypes += s.type
            _refreshStatus()
            return
        }
        val delayMs = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        Log.i(TAG, "${s.type} registration retry $attempt/$MAX_ATTEMPTS in ${delayMs}ms")
        _status.value = MdnsStatus.RETRYING
        val retry = Runnable {
            pendingRetries.remove(s.type)
            _register(s)
        }
        pendingRetries[s.type] = retry
        handler.postDelayed(retry, delayMs)
    }

    private fun _refreshStatus() {
        // Order matters: a retry actually scheduled beats the terminal
        // states; once no retry is pending, exhausted services decide
        // between DEGRADED (some still registered) and FAILED (none).
        _status.value = when {
            expectedTypes.isEmpty() -> MdnsStatus.IDLE
            registeredTypes.containsAll(expectedTypes) -> MdnsStatus.REGISTERED
            pendingRetries.isNotEmpty() -> MdnsStatus.RETRYING
            failedTypes.isNotEmpty() && registeredTypes.isNotEmpty() -> MdnsStatus.DEGRADED
            failedTypes.isNotEmpty() -> MdnsStatus.FAILED
            else -> MdnsStatus.REGISTERING
        }
    }

    fun release() {
        pendingRetries.values.forEach { handler.removeCallbacks(it) }
        pendingRetries.clear()
        registrations.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrations.clear()
        registeredTypes.clear()
        failedTypes.clear()
        attempts.clear()
        expectedTypes = emptySet()
        _status.value = MdnsStatus.IDLE
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val MAX_ATTEMPTS = 12
        // 2s, 5s, 10s, 15s, then 30s (12 attempts span ~4.5 minutes)
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 15_000, 30_000)
    }
}
