package com.daisy.mashiro.logger

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val MAX_LOGS = 1000
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp] $message"
        logQueue.add(formatted)
        while (logQueue.size > MAX_LOGS) {
            logQueue.poll()
        }
        _logsFlow.value = logQueue.toList()
    }

    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
    }

    fun getAllLogs(): String = logQueue.joinToString("\n")

    fun exportAndShare(context: Context) {
        try {
            val content = getAllLogs()
            val file = File(context.cacheDir, "slowdns_debug_${System.currentTimeMillis()}.log")
            file.writeText(content)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SlowDNS Lite Debug Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Kirim Log Debug via..."))
        } catch (e: Exception) {
            log("[Error] Gagal ekspor log: ${e.message}")
        }
    }
}
