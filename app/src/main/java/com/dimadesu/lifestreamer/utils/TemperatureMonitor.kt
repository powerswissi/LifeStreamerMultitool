package com.dimadesu.lifestreamer.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.swissi.lifestreamer.multitool.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

import androidx.core.content.ContextCompat

data class TemperatureInfo(
    val cpuTempString: String,
    val cpuColor: Int,
    val batteryTempString: String,
    val batteryColor: Int
)

class TemperatureMonitor(private val context: Context) {

    private val thermalPaths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
        "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/devices/virtual/thermal/thermal_zone0/temp"
    )

    private var activeCpuPath: String? = null

    init {
        // Find first working path
        activeCpuPath = thermalPaths.find { path ->
            val file = File(path)
            file.exists() && file.canRead() && readTempFromFile(path) > 0
        }
    }

    fun monitorTemperatures(intervalMs: Long = 2000L): Flow<TemperatureInfo> = flow {
        while (true) {
            val cpuTemp = getCpuTemperature()
            val batteryTemp = getBatteryTemperature()

            val cpuColorRes = getCpuColor(cpuTemp)
            val batteryColorRes = getBatteryColor(batteryTemp)

            emit(
                TemperatureInfo(
                    cpuTempString = "CPU: ${cpuTemp.roundToInt()}°C",
                    cpuColor = ContextCompat.getColor(context, cpuColorRes),
                    batteryTempString = "BAT: ${batteryTemp.roundToInt()}°C",
                    batteryColor = ContextCompat.getColor(context, batteryColorRes)
                )
            )
            delay(intervalMs)
        }
    }

    private fun getCpuTemperature(): Float {
        activeCpuPath?.let { path ->
            val temp = readTempFromFile(path)
            if (temp > 1000) return temp / 1000f // Normalize huge values (milli-degrees)
            if (temp > 0) return temp
        }
        return 0f
    }

    private fun readTempFromFile(path: String): Float {
        return try {
            RandomAccessFile(path, "r").use { reader ->
                val line = reader.readLine()
                line?.toFloatOrNull() ?: 0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    private fun getBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f // Battery temp is in tenths of a degree Celsius
    }

    private fun getCpuColor(temp: Float): Int {
        // CPU handles higher temps better
        return when {
            temp < 50 -> R.color.active_button_green
            temp < 65 -> R.color.button_yellow
            else -> R.color.button_red
        }
    }

    private fun getBatteryColor(temp: Float): Int {
        // Battery is more sensitive
        return when {
            temp < 37 -> R.color.active_button_green
            temp < 45 -> R.color.button_yellow
            else -> R.color.button_red // > 45 is dangerous for Li-ion
        }
    }
}
