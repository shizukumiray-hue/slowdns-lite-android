package com.daisy.mashiro.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TrafficSummary(
    val sessionTx: Long = 0L,
    val sessionRx: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val todayBytes: Long = 0L,
    val yesterdayBytes: Long = 0L,
    val thisWeekBytes: Long = 0L,
    val thisMonthBytes: Long = 0L
)

class TrafficStatsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("slowdns_traffic_stats", Context.MODE_PRIVATE)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val _summaryFlow = MutableStateFlow(TrafficSummary())
    val summaryFlow: StateFlow<TrafficSummary> = _summaryFlow.asStateFlow()

    private var lastTx = 0L
    private var lastRx = 0L
    private var lastTickTime = System.currentTimeMillis()

    init {
        refreshHistoricalStats(0L, 0L, 0L, 0L)
    }

    @Synchronized
    fun onTrafficTick(currentTx: Long, currentRx: Long) {
        val now = System.currentTimeMillis()
        val deltaSec = ((now - lastTickTime) / 1000.0).coerceAtLeast(1.0)

        val deltaTx = (currentTx - lastTx).coerceAtLeast(0L)
        val deltaRx = (currentRx - lastRx).coerceAtLeast(0L)
        val totalDelta = deltaTx + deltaRx

        val upSpeed = (deltaTx / deltaSec).toLong()
        val downSpeed = (deltaRx / deltaSec).toLong()

        lastTx = currentTx
        lastRx = currentRx
        lastTickTime = now

        if (totalDelta > 0) {
            val todayKey = dateFormat.format(Date(now))
            val currentToday = prefs.getLong(todayKey, 0L)
            prefs.edit().putLong(todayKey, currentToday + totalDelta).apply()
        }

        refreshHistoricalStats(currentTx, currentRx, upSpeed, downSpeed)
    }

    fun resetSession() {
        lastTx = 0L
        lastRx = 0L
        lastTickTime = System.currentTimeMillis()
        refreshHistoricalStats(0L, 0L, 0L, 0L)
    }

    private fun refreshHistoricalStats(
        sessionTx: Long,
        sessionRx: Long,
        upSpeed: Long,
        downSpeed: Long
    ) {
        val cal = Calendar.getInstance()
        val todayKey = dateFormat.format(cal.time)
        val todayBytes = prefs.getLong(todayKey, 0L)

        // Kemarin
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayKey = dateFormat.format(cal.time)
        val yesterdayBytes = prefs.getLong(yesterdayKey, 0L)

        // 7 Hari Terakhir (Minggu ini)
        var weekBytes = 0L
        val weekCal = Calendar.getInstance()
        for (i in 0 until 7) {
            val key = dateFormat.format(weekCal.time)
            weekBytes += prefs.getLong(key, 0L)
            weekCal.add(Calendar.DAY_OF_YEAR, -1)
        }

        // 30 Hari Terakhir (Bulan ini)
        var monthBytes = 0L
        val monthCal = Calendar.getInstance()
        for (i in 0 until 30) {
            val key = dateFormat.format(monthCal.time)
            monthBytes += prefs.getLong(key, 0L)
            monthCal.add(Calendar.DAY_OF_YEAR, -1)
        }

        _summaryFlow.value = TrafficSummary(
            sessionTx = sessionTx,
            sessionRx = sessionRx,
            uploadSpeedBps = upSpeed,
            downloadSpeedBps = downSpeed,
            todayBytes = todayBytes,
            yesterdayBytes = yesterdayBytes,
            thisWeekBytes = weekBytes,
            thisMonthBytes = monthBytes
        )
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val index = digitGroups.coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, index.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[index])
        }

        fun formatSpeed(bps: Long): String {
            return "${formatBytes(bps)}/s"
        }
    }
}
