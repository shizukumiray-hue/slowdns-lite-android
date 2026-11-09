package com.daisy.mashiro.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daisy.mashiro.data.TrafficStatsManager
import com.daisy.mashiro.data.TrafficSummary
import com.daisy.mashiro.logger.AppLogger
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isConnected: Boolean,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    trafficSummary: TrafficSummary,
    onConnectToggle: (
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
        customDns: String
    ) -> Unit,
    context: Context
) {
    // 1. Pilihan Engine & Toggle SlowDNS
    var selectedProtocol by remember { mutableStateOf("ssh") } // "ssh" atau "v2ray"
    var useSlowDns by remember { mutableStateOf(true) }         // Centang SlowDNS

    // 2. Parameter SlowDNS
    var domain by remember { mutableStateOf("") }
    var pubKey by remember { mutableStateOf("") }
    var resolver by remember { mutableStateOf("1.1.1.1:53") }
    var selectedRecordType by remember { mutableStateOf("TXT") } // TXT, AUTO, CNAME, A, AAAA, NULL, MX, NS, SRV
    var recordDropdownExpanded by remember { mutableStateOf(false) }

    // 3. Parameter SSH
    var sshHost by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("") }
    var sshPass by remember { mutableStateOf("") }
    var sshEnhanced by remember { mutableStateOf(false) } // SSL + Payload
    var sshPayload by remember { mutableStateOf("") }
    var sshSni by remember { mutableStateOf("") }

    // 4. Parameter V2Ray (RAW TCP murni saat SlowDNS)
    var v2rayRawLink by remember { mutableStateOf("") }
    var v2rayUuid by remember { mutableStateOf("") }

    // 5. Pengaturan Sistem & Jaringan
    var wakeLockEnabled by remember { mutableStateOf(false) }
    var batterySaverKcp by remember { mutableStateOf(true) }
    var isCustomDns by remember { mutableStateOf(false) }
    var customDnsServer by remember { mutableStateOf("1.1.1.1") }

    val logs by AppLogger.logsFlow.collectAsState()
    val scrollState = rememberScrollState()

    val statusColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00C853) else Color(0xFFD50000),
        label = "statusColor"
    )

    // Helper Parse Link V2Ray / SlowDNS Config
    fun parseClipboard(raw: String) {
        val trimmed = raw.trim()
        try {
            when {
                trimmed.startsWith("vless://") -> {
                    selectedProtocol = "v2ray"
                    v2rayRawLink = trimmed
                    val uri = URI(trimmed.replace("vless://", "http://"))
                    v2rayUuid = uri.userInfo ?: ""
                    Toast.makeText(context, "Link VLESS berhasil diimpor!", Toast.LENGTH_SHORT).show()
                }
                trimmed.startsWith("vmess://") -> {
                    selectedProtocol = "v2ray"
                    v2rayRawLink = trimmed
                    Toast.makeText(context, "Link VMess berhasil diimpor!", Toast.LENGTH_SHORT).show()
                }
                trimmed.startsWith("slowdns://") -> {
                    useSlowDns = true
                    val uri = URI(trimmed)
                    domain = uri.host ?: ""
                    val query = uri.query ?: ""
                    val params = query.split("&").associate {
                        val p = it.split("=")
                        if (p.size == 2) p[0] to p[1] else "" to ""
                    }
                    pubKey = params["pubkey"] ?: ""
                    resolver = params["dns"] ?: "1.1.1.1:53"

                    val userParts = (uri.userInfo ?: "").split(":")
                    if (userParts.getOrNull(0) == "ssh") {
                        selectedProtocol = "ssh"
                        sshUser = userParts.getOrNull(1) ?: ""
                        sshPass = userParts.getOrNull(2) ?: ""
                    } else {
                        selectedProtocol = "v2ray"
                        v2rayUuid = userParts.getOrNull(1) ?: ""
                    }
                    Toast.makeText(context, "Config SlowDNS berhasil diimpor!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(context, "Format tidak dikenali (vless://, vmess://, slowdns://)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal parse: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SlowDNS Lite", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onToggleDarkMode) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Ganti Tema Terang/Gelap"
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            parseClipboard(text)
                        },
                        enabled = !isConnected
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Tempel Link / Config")
                    }
                    IconButton(onClick = { AppLogger.exportAndShare(context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Kirim Log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Dashboard Status & Speed Realtime
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = if (isConnected) "TERHUBUNG" else "TERPUTUS",
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        Text(
                            text = if (useSlowDns) "DNS TUNNEL :53" else "DIRECT INTERNET",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Realtime Speed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Upload",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Upload", fontSize = 12.sp)
                            }
                            Text(
                                text = TrafficStatsManager.formatSpeed(trafficSummary.uploadSpeedBps),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Total: ${TrafficStatsManager.formatBytes(trafficSummary.sessionTx)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = "Download",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Download", fontSize = 12.sp)
                            }
                            Text(
                                text = TrafficStatsManager.formatSpeed(trafficSummary.downloadSpeedBps),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Total: ${TrafficStatsManager.formatBytes(trafficSummary.sessionRx)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Kartu Kuota Historis
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Penggunaan Kuota Internet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        UsageItem(label = "Hari ini", bytes = trafficSummary.todayBytes)
                        UsageItem(label = "Kemarin", bytes = trafficSummary.yesterdayBytes)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        UsageItem(label = "Minggu Ini", bytes = trafficSummary.thisWeekBytes)
                        UsageItem(label = "Bulan Ini", bytes = trafficSummary.thisMonthBytes)
                    }
                }
            }

            // 3. Pilihan Protokol Utama & Toggle SlowDNS
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilterChip(
                            selected = selectedProtocol == "ssh",
                            onClick = { if (!isConnected) selectedProtocol = "ssh" },
                            label = { Text("SSH Tunnel") },
                            enabled = !isConnected
                        )
                        FilterChip(
                            selected = selectedProtocol == "v2ray",
                            onClick = { if (!isConnected) selectedProtocol = "v2ray" },
                            label = { Text("V2Ray / Xray") },
                            enabled = !isConnected
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Toggle SlowDNS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Gunakan SlowDNS Tunnel", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                if (useSlowDns) "Bypass port 53 UDP (Host disembunyikan)" else "Koneksi Langsung (Direct / WS / TLS)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useSlowDns,
                            onCheckedChange = { useSlowDns = it },
                            enabled = !isConnected
                        )
                    }
                }
            }

            // 4. Form Parameter SlowDNS (Muncul jika SlowDNS aktif)
            AnimatedVisibility(visible = useSlowDns) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Parameter SlowDNS (Server :53)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it },
                            label = { Text("Nameserver Domain (NS)") },
                            placeholder = { Text("ns.domain.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isConnected
                        )

                        OutlinedTextField(
                            value = pubKey,
                            onValueChange = { pubKey = it },
                            label = { Text("Server Public Key (Noise 64-hex)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isConnected
                        )

                        OutlinedTextField(
                            value = resolver,
                            onValueChange = { resolver = it },
                            label = { Text("DNS Resolver Publik") },
                            placeholder = { Text("1.1.1.1:53") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isConnected
                        )

                        // Dropdown Tipe Record DNS (TXT, AUTO, CNAME, A, AAAA, NULL, MX, NS, SRV)
                        val recordTypes = listOf("TXT", "AUTO", "CNAME", "A", "AAAA", "NULL", "MX", "NS", "SRV")
                        ExposedDropdownMenuBox(
                            expanded = recordDropdownExpanded,
                            onExpandedChange = { if (!isConnected) recordDropdownExpanded = !recordDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedRecordType,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("DNS Record Type") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recordDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                enabled = !isConnected
                            )
                            ExposedDropdownMenu(
                                expanded = recordDropdownExpanded,
                                onDismissRequest = { recordDropdownExpanded = false }
                            ) {
                                recordTypes.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            val desc = when (type) {
                                                "TXT" -> "TXT (Default - Throughput Tertinggi)"
                                                "AUTO" -> "AUTO (Negosiasi Otomatis)"
                                                "CNAME" -> "CNAME (Bypass Filter DPI)"
                                                "AAAA" -> "AAAA (IPv6 DNS Answer)"
                                                "A" -> "A (IPv4 DNS Answer)"
                                                "NULL" -> "NULL (Binary Raw RFC 1035)"
                                                else -> type
                                            }
                                            Text(desc)
                                        },
                                        onClick = {
                                            selectedRecordType = type
                                            recordDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Form Parameter SSH
            AnimatedVisibility(visible = selectedProtocol == "ssh") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Host & Port HANYA muncul jika SlowDNS mati!
                    AnimatedVisibility(visible = !useSlowDns) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = sshHost,
                                    onValueChange = { sshHost = it },
                                    label = { Text("SSH Host / IP") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    enabled = !isConnected
                                )
                                OutlinedTextField(
                                    value = sshPort,
                                    onValueChange = { sshPort = it },
                                    label = { Text("Port") },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    enabled = !isConnected
                                )
                            }

                            // Enhanced Mode (SSL + Payload)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enhanced Method (SSL + Payload)")
                                Switch(
                                    checked = sshEnhanced,
                                    onCheckedChange = { sshEnhanced = it },
                                    enabled = !isConnected
                                )
                            }

                            OutlinedTextField(
                                value = sshSni,
                                onValueChange = { sshSni = it },
                                label = { Text("SNI Bug Host") },
                                placeholder = { Text("m.youtube.com") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isConnected
                            )

                            OutlinedTextField(
                                value = sshPayload,
                                onValueChange = { sshPayload = it },
                                label = { Text("Custom Payload") },
                                placeholder = { Text("GET / HTTP/1.1[crlf]Host: ...") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                                enabled = !isConnected
                            )
                        }
                    }

                    // Akun SSH (User & Pass selalu muncul)
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text("SSH Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isConnected
                    )

                    OutlinedTextField(
                        value = sshPass,
                        onValueChange = { sshPass = it },
                        label = { Text("SSH Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isConnected
                    )
                }
            }

            // 6. Form Parameter V2Ray
            AnimatedVisibility(visible = selectedProtocol == "v2ray") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (useSlowDns) {
                        // V2Ray SlowDNS: Dikunci ke RAW TCP murni!
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Mode V2Ray SlowDNS: RAW TCP",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        "Transport TLS dinonaktifkan otomatis untuk performa maksimal tanpa RTO.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = v2rayUuid,
                            onValueChange = { v2rayUuid = it },
                            label = { Text("VLESS / VMess UUID") },
                            placeholder = { Text("d3b07384-d113-4f9e-...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isConnected
                        )
                    } else {
                        // V2Ray Direct: Mendukung link lengkap (WS, gRPC, TCP, xhttp, TLS, Reality, Mux)
                        OutlinedTextField(
                            value = v2rayRawLink,
                            onValueChange = {
                                v2rayRawLink = it
                                parseClipboard(it)
                            },
                            label = { Text("Tempel Link V2Ray (vless://, vmess://)") },
                            placeholder = { Text("vless://...@...:443?type=ws&security=tls...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            enabled = !isConnected
                        )
                    }
                }
            }

            // 7. Pengaturan Sistem & DNS
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Pengaturan Jaringan & Baterai", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kunci CPU WakeLock (Layar Mati)")
                        Switch(
                            checked = wakeLockEnabled,
                            onCheckedChange = { wakeLockEnabled = it },
                            enabled = !isConnected
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (batterySaverKcp) "Hemat Baterai (KCP 50ms)" else "Responsif (KCP 20ms)")
                        Switch(
                            checked = batterySaverKcp,
                            onCheckedChange = { batterySaverKcp = it },
                            enabled = !isConnected
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isCustomDns) "Custom DNS Server" else "Local DNS (Default ISP)")
                        Switch(
                            checked = isCustomDns,
                            onCheckedChange = { isCustomDns = it },
                            enabled = !isConnected
                        )
                    }

                    AnimatedVisibility(visible = isCustomDns) {
                        OutlinedTextField(
                            value = customDnsServer,
                            onValueChange = { customDnsServer = it },
                            label = { Text("IP Custom DNS") },
                            placeholder = { Text("1.1.1.1 atau 8.8.8.8") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true,
                            enabled = !isConnected
                        )
                    }
                }
            }

            // 8. Tombol Connect / Disconnect
            Button(
                onClick = {
                    val kcpInt = if (batterySaverKcp) 50 else 20
                    val dns = if (isCustomDns) customDnsServer else ""
                    val port = sshPort.toIntOrNull() ?: 22

                    onConnectToggle(
                        selectedProtocol, useSlowDns, domain, pubKey, resolver, selectedRecordType,
                        sshHost, port, sshUser, sshPass, sshEnhanced, sshPayload, sshSni,
                        v2rayRawLink, v2rayUuid, wakeLockEnabled, kcpInt, dns
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "PUTUSKAN KONEKSI" else "HUBUNGKAN SEKARANG",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // 9. Console Terminal Live Preview
            Text("Console Log:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                val lastLogs = logs.takeLast(10).joinToString("\n")
                Text(
                    text = if (lastLogs.isBlank()) "Menunggu perintah koneksi..." else lastLogs,
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
