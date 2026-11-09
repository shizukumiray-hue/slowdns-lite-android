package com.daisy.mashiro.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.daisy.mashiro.data.ConnectionState
import com.daisy.mashiro.data.TrafficStatsManager
import com.daisy.mashiro.data.TrafficSummary
import com.daisy.mashiro.logger.AppLogger
import kotlinx.coroutines.launch
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
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
        v2rayHost: String,
        v2rayPort: Int,
        wakeLock: Boolean,
        kcpInterval: Int,
        customDns: String,
        engine: String
    ) -> Unit,
    context: Context
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    // Parameter State Form
    var selectedProtocol by remember { mutableStateOf("ssh") }
    var useSlowDns by remember { mutableStateOf(true) }

    // SlowDNS
    var domain by remember { mutableStateOf("") }
    var pubKey by remember { mutableStateOf("") }
    var resolver by remember { mutableStateOf("1.1.1.1:53") }
    var selectedRecordType by remember { mutableStateOf("TXT") }
    var recordDropdownExpanded by remember { mutableStateOf(false) }

    // SSH (Host & Port SELALU ADA)
    var sshHost by remember { mutableStateOf("127.0.0.1") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("") }
    var sshPass by remember { mutableStateOf("") }
    var sshEnhanced by remember { mutableStateOf(false) }
    var sshPayload by remember { mutableStateOf("") }
    var sshSni by remember { mutableStateOf("") }

    // V2Ray (Host & Port 80 SELALU ADA di HC)
    var v2rayRawLink by remember { mutableStateOf("") }
    var v2rayHost by remember { mutableStateOf("127.0.0.1") }
    var v2rayPort by remember { mutableStateOf("80") }
    var v2rayUuid by remember { mutableStateOf("") }

    // Settings
    var wakeLockEnabled by remember { mutableStateOf(false) }
    var batterySaverKcp by remember { mutableStateOf(true) }
    var isCustomDns by remember { mutableStateOf(false) }
    var customDnsServer by remember { mutableStateOf("1.1.1.1") }
    var selectedEngine by remember { mutableStateOf("hev") }

    val logs by AppLogger.logsFlow.collectAsState()
    val scrollState = rememberScrollState()
    val logListState = rememberLazyListState()

    // Auto-scroll log ke baris paling bawah saat baris baru masuk
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING
    val isStopping = connectionState == ConnectionState.STOPPING

    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF00C853)
            ConnectionState.CONNECTING -> Color(0xFFFFAB00)
            ConnectionState.STOPPING -> Color(0xFFFF6D00)
            ConnectionState.DISCONNECTED -> Color(0xFFD50000)
        },
        label = "statusColor"
    )

    fun parseClipboard(raw: String) {
        val trimmed = raw.trim()
        try {
            when {
                trimmed.startsWith("vless://") -> {
                    selectedProtocol = "v2ray"
                    v2rayRawLink = trimmed
                    val uri = URI(trimmed.replace("vless://", "http://"))
                    v2rayUuid = uri.userInfo ?: ""
                    v2rayHost = uri.host ?: "127.0.0.1"
                    val p = uri.port
                    v2rayPort = if (p > 0) p.toString() else "80"
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
                    Toast.makeText(context, "Format tidak valid (vless://, vmess://, slowdns://)", Toast.LENGTH_SHORT).show()
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
                            contentDescription = "Ganti Tema"
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            parseClipboard(text)
                        },
                        enabled = !isConnected && !isConnecting
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
        ) {
            // TAB ROW UTAMA: Geser Kiri/Kanan antara Beranda dan Log
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Beranda") },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("Catatan Log (${logs.size})") },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        // TAB 0: BERANDA / DASHBOARD UTAMA
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // 1. Status Card Realtime
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
                                            if (isConnecting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = statusColor
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .clip(CircleShape)
                                                        .background(statusColor)
                                                )
                                            }
                                            Text(
                                                text = when (connectionState) {
                                                    ConnectionState.CONNECTED -> "TERHUBUNG"
                                                    ConnectionState.CONNECTING -> "MENGHUBUNGKAN..."
                                                    ConnectionState.STOPPING -> "MEMUTUSKAN..."
                                                    ConnectionState.DISCONNECTED -> "TERPUTUS"
                                                },
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

                                    // Kecepatan Realtime
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.ArrowUpward,
                                                    contentDescription = null,
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
                                                    contentDescription = null,
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

                            // 2. Kuota Historis
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

                            // 3. Pilihan Protokol & Toggle SlowDNS
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
                                            onClick = { if (!isConnected && !isConnecting) selectedProtocol = "ssh" },
                                            label = { Text("SSH Tunnel") },
                                            enabled = !isConnected && !isConnecting
                                        )
                                        FilterChip(
                                            selected = selectedProtocol == "v2ray",
                                            onClick = { if (!isConnected && !isConnecting) selectedProtocol = "v2ray" },
                                            label = { Text("V2Ray / Xray") },
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

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
                                                if (useSlowDns) "Bypass port 53 UDP (DNS Tunneling)" else "Koneksi Langsung (Direct / WS / TLS)",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = useSlowDns,
                                            onCheckedChange = { useSlowDns = it },
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }
                                }
                            }

                            // 4. Parameter SlowDNS (Jika Centang SlowDNS Aktif)
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
                                        Text("Parameter SlowDNS (DNS Server :53)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                                        OutlinedTextField(
                                            value = domain,
                                            onValueChange = { domain = it },
                                            label = { Text("Nameserver Domain (NS)") },
                                            placeholder = { Text("ns.domain.com") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )

                                        OutlinedTextField(
                                            value = pubKey,
                                            onValueChange = { pubKey = it },
                                            label = { Text("Server Public Key (Noise 64-hex)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )

                                        OutlinedTextField(
                                            value = resolver,
                                            onValueChange = { resolver = it },
                                            label = { Text("DNS Resolver Publik") },
                                            placeholder = { Text("1.1.1.1:53") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )

                                        // Dropdown Record Type
                                        val recordTypes = listOf("TXT", "AUTO", "CNAME", "A", "AAAA", "NULL", "MX", "NS", "SRV")
                                        ExposedDropdownMenuBox(
                                            expanded = recordDropdownExpanded,
                                            onExpandedChange = { if (!isConnected && !isConnecting) recordDropdownExpanded = !recordDropdownExpanded }
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
                                                enabled = !isConnected && !isConnecting
                                            )
                                            ExposedDropdownMenu(
                                                expanded = recordDropdownExpanded,
                                                onDismissRequest = { recordDropdownExpanded = false }
                                            ) {
                                                recordTypes.forEach { type ->
                                                    DropdownMenuItem(
                                                        text = { Text(type) },
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

                            // 5. Parameter SSH (HOST & PORT SELALU ADA)
                            AnimatedVisibility(visible = selectedProtocol == "ssh") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = sshHost,
                                            onValueChange = { sshHost = it },
                                            label = { Text(if (useSlowDns) "SSH Host (VPS/127.0.0.1)" else "SSH Host / IP") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )
                                        OutlinedTextField(
                                            value = sshPort,
                                            onValueChange = { sshPort = it },
                                            label = { Text("Port") },
                                            placeholder = { Text("22") },
                                            modifier = Modifier.width(95.dp),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }

                                    OutlinedTextField(
                                        value = sshUser,
                                        onValueChange = { sshUser = it },
                                        label = { Text("SSH Username") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = !isConnected && !isConnecting
                                    )

                                    OutlinedTextField(
                                        value = sshPass,
                                        onValueChange = { sshPass = it },
                                        label = { Text("SSH Password") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = !isConnected && !isConnecting
                                    )

                                    // Payload & Enhanced HANYA saat Direct (SlowDNS mati)
                                    AnimatedVisibility(visible = !useSlowDns) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Enhanced Method (SSL + Payload)")
                                                Switch(
                                                    checked = sshEnhanced,
                                                    onCheckedChange = { sshEnhanced = it },
                                                    enabled = !isConnected && !isConnecting
                                                )
                                            }

                                            OutlinedTextField(
                                                value = sshSni,
                                                onValueChange = { sshSni = it },
                                                label = { Text("SNI Bug Host") },
                                                placeholder = { Text("m.youtube.com") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                enabled = !isConnected && !isConnecting
                                            )

                                            OutlinedTextField(
                                                value = sshPayload,
                                                onValueChange = { sshPayload = it },
                                                label = { Text("Custom Payload") },
                                                placeholder = { Text("GET / HTTP/1.1[crlf]Host: ...") },
                                                modifier = Modifier.fillMaxWidth(),
                                                maxLines = 3,
                                                enabled = !isConnected && !isConnecting
                                            )
                                        }
                                    }
                                }
                            }

                            // 6. Parameter V2Ray (HOST & PORT 80 SELALU ADA)
                            AnimatedVisibility(visible = selectedProtocol == "v2ray") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = v2rayHost,
                                            onValueChange = { v2rayHost = it },
                                            label = { Text("V2Ray Host / Server IP") },
                                            placeholder = { Text("127.0.0.1") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )
                                        OutlinedTextField(
                                            value = v2rayPort,
                                            onValueChange = { v2rayPort = it },
                                            label = { Text("Port") },
                                            placeholder = { Text("80") },
                                            modifier = Modifier.width(95.dp),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }

                                    OutlinedTextField(
                                        value = v2rayUuid,
                                        onValueChange = { v2rayUuid = it },
                                        label = { Text("VLESS / VMess UUID") },
                                        placeholder = { Text("d3b07384-d113-4f9e-...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = !isConnected && !isConnecting
                                    )

                                    AnimatedVisibility(visible = !useSlowDns) {
                                        OutlinedTextField(
                                            value = v2rayRawLink,
                                            onValueChange = {
                                                v2rayRawLink = it
                                                parseClipboard(it)
                                            },
                                            label = { Text("Tempel Link Lengkap (WS/gRPC/TLS)") },
                                            placeholder = { Text("vless://...@...:443?type=ws...") },
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 3,
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }
                                }
                            }

                            // 7. Pengaturan Sistem & Baterai
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Pengaturan Jaringan & Baterai", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                                    // Pilihan Engine
                                    Text("IP Packet Engine:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = selectedEngine == "hev",
                                            onClick = { if (!isConnected && !isConnecting) selectedEngine = "hev" },
                                            label = { Text("HEV Tunnel (C lwIP)") },
                                            enabled = !isConnected && !isConnecting
                                        )
                                        FilterChip(
                                            selected = selectedEngine == "gvisor",
                                            onClick = { if (!isConnected && !isConnecting) selectedEngine = "gvisor" },
                                            label = { Text("gVisor (Pure Go)") },
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Kunci CPU WakeLock (Layar Mati)")
                                        Switch(
                                            checked = wakeLockEnabled,
                                            onCheckedChange = { wakeLockEnabled = it },
                                            enabled = !isConnected && !isConnecting
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
                                            enabled = !isConnected && !isConnecting
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
                                            enabled = !isConnected && !isConnecting
                                        )
                                    }

                                    AnimatedVisibility(visible = isCustomDns) {
                                        OutlinedTextField(
                                            value = customDnsServer,
                                            onValueChange = { customDnsServer = it },
                                            label = { Text("IP Custom DNS") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            enabled = !isConnected && !isConnecting
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
                                    val vPort = v2rayPort.toIntOrNull() ?: 80

                                    onConnectToggle(
                                        selectedProtocol, useSlowDns, domain, pubKey, resolver, selectedRecordType,
                                        sshHost, port, sshUser, sshPass, sshEnhanced, sshPayload, sshSni,
                                        v2rayRawLink, v2rayUuid, v2rayHost, vPort, wakeLockEnabled, kcpInt, dns, selectedEngine
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isConnected -> MaterialTheme.colorScheme.error
                                        isConnecting -> Color(0xFFFF8F00)
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(
                                    imageVector = if (isConnected || isConnecting) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isConnecting -> "MEMBATALKAN..."
                                        isConnected -> "PUTUSKAN KONEKSI"
                                        else -> "HUBUNGKAN SEKARANG"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: CATATAN LOG (GESER KANAN) DENGAN SCROLL LAZYCOLUMN PENUH
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF141414))
                                .padding(12.dp)
                        ) {
                            // Toolbar Baris Aksi Log
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Terminal Log Output",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF00FF66)
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Salin Semua Log
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("SlowDNS Log", AppLogger.getAllLogs())
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Log disalin ke clipboard", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Salin", tint = Color.White)
                                    }

                                    // Bersihkan Log
                                    IconButton(onClick = { AppLogger.clear() }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Log", tint = Color.White)
                                    }

                                    // Bagikan Log
                                    IconButton(onClick = { AppLogger.exportAndShare(context) }) {
                                        Icon(Icons.Default.Share, contentDescription = "Kirim Log", tint = Color.White)
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFF2C2C2C))

                            // Daftar Log Scrollable Halus
                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Belum ada catatan log...",
                                        color = Color(0xFF888888),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = logListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(logs) { logLine ->
                                        Text(
                                            text = logLine,
                                            color = when {
                                                logLine.contains("[Error]") || logLine.contains("gagal", ignoreCase = true) -> Color(0xFFFF5252)
                                                logLine.contains("[Warning]") -> Color(0xFFFFD740)
                                                logLine.contains("[Core]") || logLine.contains("[SlowDNS]") -> Color(0xFF69F0AE)
                                                else -> Color(0xFFE0E0E0)
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UsageItem(label: String, bytes: Long) {
    Column(
        modifier = Modifier.width(140.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = TrafficStatsManager.formatBytes(bytes),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
