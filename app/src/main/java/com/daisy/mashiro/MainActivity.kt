package com.daisy.mashiro

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.daisy.mashiro.data.ConnectionState
import com.daisy.mashiro.data.TrafficStatsManager
import com.daisy.mashiro.data.TrafficSummary
import com.daisy.mashiro.logger.AppLogger
import com.daisy.mashiro.service.TunnelVpnService
import com.daisy.mashiro.ui.screens.HomeScreen
import com.daisy.mashiro.ui.theme.SlowDnsTheme

class MainActivity : ComponentActivity() {

    private var pendingIntent: Intent? = null
    private lateinit var statsManager: TrafficStatsManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingIntent?.let {
                startForegroundService(it)
                AppLogger.log("[App] Izin VPN disetujui, memulai koneksi")
            }
        } else {
            Toast.makeText(this, "Izin VPN ditolak!", Toast.LENGTH_SHORT).show()
            AppLogger.log("[App] Error: Izin VPN ditolak pengguna")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statsManager = TrafficStatsManager(this)

        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkMode by remember { mutableStateOf(systemDark) }

            val liveStatsManager = TunnelVpnService.activeStatsManager ?: statsManager
            val trafficSummary by liveStatsManager.summaryFlow.collectAsState(initial = TrafficSummary())
            val connectionState by TunnelVpnService.connectionState.collectAsState()

            SlowDnsTheme(darkTheme = isDarkMode) {
                Surface {
                    HomeScreen(
                        connectionState = connectionState,
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { isDarkMode = !isDarkMode },
                        trafficSummary = trafficSummary,
                        onConnectToggle = { mode, useSlowDns, domain, pubkey, resolver, recordType,
                                            sshHost, sshPort, sshUser, sshPass, sshEnhanced, sshPayload, sshSni,
                                            v2rayRawLink, v2rayUuid, wakeLock, kcpInterval, customDns, engine ->
                            handleConnectToggle(
                                connectionState,
                                mode, useSlowDns, domain, pubkey, resolver, recordType,
                                sshHost, sshPort, sshUser, sshPass, sshEnhanced, sshPayload, sshSni,
                                v2rayRawLink, v2rayUuid, wakeLock, kcpInterval, customDns, engine
                            )
                        },
                        context = this
                    )
                }
            }
        }
    }

    private fun handleConnectToggle(
        connectionState: ConnectionState,
        mode: String,
        useSlowDns: Boolean,
        domain: String,
        pubkey: String,
        resolver: String,
        recordType: String,
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        sshPass: String,
        sshEnhanced: Boolean,
        sshPayload: String,
        sshSni: String,
        v2rayRawLink: String,
        v2rayUuid: String,
        wakeLock: Boolean,
        kcpInterval: Int,
        customDns: String,
        engine: String
    ) {
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) {
            val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_STOP
            }
            startService(stopIntent)
            AppLogger.log("[App] Menghentikan koneksi...")
            return
        }

        // Validasi input
        if (useSlowDns) {
            if (domain.isBlank() || pubkey.isBlank()) {
                Toast.makeText(this, "Domain NS dan Server Public Key wajib diisi!", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (mode == "ssh" && sshUser.isBlank()) {
            Toast.makeText(this, "SSH Username wajib diisi!", Toast.LENGTH_SHORT).show()
            return
        }

        val startIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra("EXTRA_MODE", mode)
            putExtra("EXTRA_USE_SLOWDNS", useSlowDns)
            putExtra("EXTRA_DOMAIN", domain.trim())
            putExtra("EXTRA_PUBKEY", pubkey.trim())
            putExtra("EXTRA_RESOLVER", resolver.trim())
            putExtra("EXTRA_RECORD_TYPE", recordType.trim())
            putExtra("EXTRA_SSH_HOST", sshHost.trim())
            putExtra("EXTRA_SSH_PORT", sshPort)
            putExtra("EXTRA_SSH_USER", sshUser.trim())
            putExtra("EXTRA_SSH_PASS", sshPass)
            putExtra("EXTRA_SSH_ENHANCED", sshEnhanced)
            putExtra("EXTRA_SSH_PAYLOAD", sshPayload)
            putExtra("EXTRA_SSH_SNI", sshSni.trim())
            putExtra("EXTRA_V2RAY_LINK", v2rayRawLink.trim())
            putExtra("EXTRA_V2RAY_UUID", v2rayUuid.trim())
            putExtra("EXTRA_WAKELOCK", wakeLock)
            putExtra("EXTRA_KCP_INTERVAL", kcpInterval)
            putExtra("EXTRA_CUSTOM_DNS", customDns.trim())
            putExtra("EXTRA_ENGINE", engine.trim())
        }

        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            pendingIntent = startIntent
            vpnPermissionLauncher.launch(vpnPrepareIntent)
        } else {
            startForegroundService(startIntent)
            AppLogger.log("[App] Memulai service VPN...")
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
