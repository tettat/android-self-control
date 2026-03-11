package com.control.app.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Uses libadb-android's built-in AdbMdns to discover local ADB wireless debugging services.
 * Discovers both pairing ports (_adb-tls-pairing._tcp) and connect ports (_adb-tls-connect._tcp).
 */
class AdbMdnsDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "AdbMdnsDiscovery"
    }

    data class DiscoveredService(
        val host: InetAddress,
        val port: Int
    )

    private val _pairingService = MutableStateFlow<DiscoveredService?>(null)
    val pairingService: StateFlow<DiscoveredService?> = _pairingService.asStateFlow()

    private val _connectService = MutableStateFlow<DiscoveredService?>(null)
    val connectService: StateFlow<DiscoveredService?> = _connectService.asStateFlow()

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null

    fun startDiscovery() {
        stopDiscovery()
        Log.d(TAG, "Starting mDNS discovery for ADB services")

        pairingMdns = AdbMdns(
            context,
            AdbMdns.SERVICE_TYPE_TLS_PAIRING
        ) { address, port ->
            Log.d(TAG, "Pairing service discovered: ${address?.hostAddress}:$port")
            val addr = address ?: InetAddress.getByName("127.0.0.1")
            _pairingService.value = DiscoveredService(addr, port)
        }
        pairingMdns?.start()

        connectMdns = AdbMdns(
            context,
            AdbMdns.SERVICE_TYPE_TLS_CONNECT
        ) { address, port ->
            Log.d(TAG, "Connect service discovered: ${address?.hostAddress}:$port")
            val addr = address ?: InetAddress.getByName("127.0.0.1")
            _connectService.value = DiscoveredService(addr, port)
        }
        connectMdns?.start()
    }

    fun stopDiscovery() {
        pairingMdns?.stop()
        connectMdns?.stop()
        pairingMdns = null
        connectMdns = null
        _pairingService.value = null
        _connectService.value = null
        Log.d(TAG, "Stopped mDNS discovery")
    }

    fun isRunning(): Boolean {
        return pairingMdns?.isRunning == true || connectMdns?.isRunning == true
    }
}
