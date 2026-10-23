package com.hermes.volumebooster

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VolumeBooster"
        private const val SHIZUKU_REQUEST_CODE = 1001
    }

    // ── Audio ──────────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var enhancer: LoudnessEnhancer? = null

    // ── UI State (class-level MutableStates — Compose tracks by reference) ──
    private val boostEnabled: MutableState<Boolean> = mutableStateOf(false)
    private val boostGain: MutableState<Int> = mutableStateOf(0)
    private val enhancerSupported: MutableState<Boolean> = mutableStateOf(true)
    private val enhancerErrorMsg: MutableState<String> = mutableStateOf("")

    private val headsetConnected: MutableState<Boolean> = mutableStateOf(false)
    private val boostOnlyHeadset: MutableState<Boolean> = mutableStateOf(false)

    private val shizukuInstalled: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuRunning: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuPermission: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuMsg: MutableState<String> = mutableStateOf("")

    private val statusMsg: MutableState<String> = mutableStateOf("")

    // ── Headset callback ──────────────────────────────────────────────
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (isWiredHeadset(device.type)) {
                    handleHeadsetState(true)
                    break
                }
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (isWiredHeadset(device.type)) {
                    handleHeadsetState(false)
                    break
                }
            }
        }

    // ── Shizuku permission-result listener ────────────────────────────
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuPermission.value = granted
                shizukuMsg.value = if (granted) {
                    "Izin diberikan — silakan aktifkan Shizuku Mode."
                } else {
                    "Izin ditolak. Buka Shizuku → Permission Manager untuk mengizinkan."
                }
            }
        }

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Register Shizuku permission listener
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku listener registration failed (Shizuku not present?)", e)
        }

        // Initial checks
        checkShizuku()
        checkHeadsetState()

        // Register headset detection
        val handler = Handler(Looper.getMainLooper())
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio device callback", e)
        }

        setContent {
            VolumeBoosterTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VolumeBoosterScreen(
                        boostEnabled = boostEnabled,
                        boostGain = boostGain,
                        onGainChange = { g -> updateGain(g) },
                        onToggleBoost = { toggleBoost() },
                        onResetGain = { resetGain() },
                        onMaxAll = { maxAllVolumes() },
                        enhancerSupported = enhancerSupported,
                        enhancerErrorMsg = enhancerErrorMsg,
                        headsetConnected = headsetConnected,
                        boostOnlyHeadset = boostOnlyHeadset,
                        onToggleHeadsetOnly = {
                            boostOnlyHeadset.value = !boostOnlyHeadset.value
                            if (boostOnlyHeadset.value && !headsetConnected.value) {
                                disableBoost()
                            }
                        },
                        shizukuInstalled = shizukuInstalled,
                        shizukuRunning = shizukuRunning,
                        shizukuPermission = shizukuPermission,
                        shizukuMsg = shizukuMsg,
                        onRequestShizukuPermission = { requestShizukuPermission() },
                        onShizukuMode = { enableShizukuMode() },
                        statusMsg = statusMsg,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check Shizuku permission status
        checkShizuku()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Release LoudnessEnhancer
        disableBoost()

        // Unregister headset callback
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering audio device callback", e)
        }

        // Remove Shizuku permission listener
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            // Shizuku might not be available
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SHIZUKU
    // ══════════════════════════════════════════════════════════════════

    private fun checkShizuku() {
        try {
            val running = Shizuku.pingBinder()
            shizukuRunning.value = running

            if (running) {
                shizukuInstalled.value = true
                val perm = Shizuku.checkSelfPermission()
                val granted = perm == PackageManager.PERMISSION_GRANTED
                shizukuPermission.value = granted

                shizukuMsg.value = if (granted) {
                    "Shizuku siap. Klik \"Shizuku Mode\" untuk melepas batas volume aman."
                } else {
                    try {
                        if (Shizuku.shouldShowRequestPermissionRationale()) {
                            "Berikan izin agar Shizuku Mode dapat digunakan."
                        } else {
                            "Klik \"Mintakan Izin Shizuku\" untuk melanjutkan."
                        }
                    } catch (e: Exception) {
                        "Klik \"Mintakan Izin Shizuku\" untuk melanjutkan."
                    }
                }
            } else {
                shizukuInstalled.value = false
                shizukuPermission.value = false
                shizukuMsg.value = "Shizuku belum terpasang atau belum berjalan.\n" +
                    "Unduh dari https://shizuku.rikka.app/ lalu jalankan dengan root atau ADB."
            }
        } catch (e: Exception) {
            shizukuInstalled.value = false
            shizukuRunning.value = false
            shizukuPermission.value = false
            shizukuMsg.value = "Shizuku tidak tersedia di perangkat ini."
            Log.e(TAG, "Shizuku check failed", e)
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuMsg.value = "Shizuku belum berjalan. Buka aplikasi Shizuku terlebih dahulu."
                return
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            shizukuMsg.value = "Permintaan izin sedang dikirim..."
        } catch (e: Exception) {
            shizukuMsg.value = "Gagal meminta izin Shizuku: ${e.message}"
            Log.e(TAG, "Shizuku permission request failed", e)
        }
    }

    private fun enableShizukuMode() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runShizukuCommand(
                    arrayOf("settings", "put", "global", "audio_safe_volume_state", "0")
                )
            }
            shizukuMsg.value = result
        }
    }

    /**
     * Execute a shell command through the Shizuku privileged API.
     * Uses reflection to call Shizuku.newProcess (private API).
     * Returns a human-readable status string.
     */
    private fun runShizukuCommand(cmd: Array<String>): String {
        return try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku belum berjalan. Buka aplikasi Shizuku terlebih dahulu."
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Izin Shizuku belum diberikan. Klik \"Mintakan Izin Shizuku\" di atas."
            }

            // ── Reflection: Shizuku.newProcess(cmd, env, dir) → ShizukuRemoteProcess ──
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null) as ShizukuRemoteProcess

            // Wait up to 10 seconds for the command to finish
            val finished = process.waitForTimeout(10L, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return "Perintah time out setelah 10 detik."
            }

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                "Berhasil! Batas volume aman (audio_safe_volume_state) telah dimatikan.\n" +
                    "Sekarang Anda bisa menaikkan volume melebihi batas normal."
            } else {
                val err = try {
                    process.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    ""
                } ?: ""
                "Gagal (exit $exitCode): $err"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command execution failed", e)
            "Error: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // LOUDNESS ENHANCER  (global boost)
    // ══════════════════════════════════════════════════════════════════

    private fun toggleBoost() {
        if (boostEnabled.value) {
            disableBoost()
        } else {
            if (boostOnlyHeadset.value && !headsetConnected.value) {
                statusMsg.value = "Sambungkan headset untuk mengaktifkan boost."
                return
            }
            enableBoost()
        }
    }

    private fun enableBoost() {
        statusMsg.value = ""
        try {
            // Attach to global output mix (session 0)
            enhancer = LoudnessEnhancer(0)
            enhancer?.let { fx ->
                // getMaxGain() is not part of the public API; use documented max (2000 mB)
                val maxGain = 2000
                val clampedGain = minOf(boostGain.value, maxGain)

                fx.setTargetGain(clampedGain)
                fx.enabled = true
            }
            boostEnabled.value = true
            enhancerSupported.value = true
            enhancerErrorMsg.value = ""
        } catch (e: UnsupportedOperationException) {
            enhancerSupported.value = false
            enhancerErrorMsg.value =
                "Perangkat tidak mendukung LoudnessEnhancer (session 0). " +
                    "Fitur boost global tidak tersedia."
            boostEnabled.value = false
            Log.e(TAG, "LoudnessEnhancer not supported", e)
        } catch (e: Exception) {
            enhancerSupported.value = false
            enhancerErrorMsg.value = "Gagal menginisialisasi enhancer: ${e.message}"
            boostEnabled.value = false
            Log.e(TAG, "LoudnessEnhancer init failed", e)
        }
    }

    private fun disableBoost() {
        try {
            enhancer?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing enhancer", e)
        }
        enhancer = null
        boostEnabled.value = false
    }

    private fun resetGain() {
        boostGain.value = 0
        enhancer?.setTargetGain(0)
    }

    private fun updateGain(newGain: Int) {
        boostGain.value = newGain
        enhancer?.setTargetGain(newGain)
    }

    // ══════════════════════════════════════════════════════════════════
    // MAX ALL VOLUMES
    // ══════════════════════════════════════════════════════════════════

    private fun maxAllVolumes() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val streams = listOf(
                    AudioManager.STREAM_MUSIC to "Music",
                    AudioManager.STREAM_RING to "Ring",
                    AudioManager.STREAM_ALARM to "Alarm",
                    AudioManager.STREAM_NOTIFICATION to "Notif",
                    AudioManager.STREAM_SYSTEM to "System",
                )

                val sb = StringBuilder()
                for ((stream, label) in streams) {
                    try {
                        val max = audioManager.getStreamMaxVolume(stream)
                        audioManager.setStreamVolume(stream, max, 0)
                        sb.append("$label: $max\n")
                    } catch (e: SecurityException) {
                        sb.append("$label: ditolak izin\n")
                    } catch (e: Exception) {
                        sb.append("$label: ${e.message}\n")
                    }
                }
                sb.toString()
            }
            statusMsg.value = "Semua stream diatur ke maksimum:\n$result"
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // HEADSET DETECTION
    // ══════════════════════════════════════════════════════════════════

    private fun isWiredHeadset(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    private fun checkHeadsetState() {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (isWiredHeadset(device.type)) {
                    headsetConnected.value = true
                    return
                }
            }
            headsetConnected.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headset state", e)
            headsetConnected.value = false
        }
    }

    private fun handleHeadsetState(connected: Boolean) {
        headsetConnected.value = connected

        // Auto-disable boost when headset unplugged (if "only headset" is ON)
        if (boostOnlyHeadset.value && !connected && boostEnabled.value) {
            disableBoost()
            statusMsg.value = "Headset dicabut — boost dimatikan otomatis."
        }
    }
}

// ── THEME ───────────────────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6200EE),
    onPrimary = Color.White,
    secondary = Color(0xFF03DAC5),
    onSecondary = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFA0A0A0),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    outline = Color(0xFF303030),
)

@Composable
fun VolumeBoosterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content,
    )
}

// ── SCREEN ──────────────────────────────────────────────────────────────────

@Composable
fun VolumeBoosterScreen(
    boostEnabled: MutableState<Boolean>,
    boostGain: MutableState<Int>,
    onGainChange: (Int) -> Unit,
    onToggleBoost: () -> Unit,
    onResetGain: () -> Unit,
    onMaxAll: () -> Unit,
    enhancerSupported: MutableState<Boolean>,
    enhancerErrorMsg: MutableState<String>,
    headsetConnected: MutableState<Boolean>,
    boostOnlyHeadset: MutableState<Boolean>,
    onToggleHeadsetOnly: () -> Unit,
    shizukuInstalled: MutableState<Boolean>,
    shizukuRunning: MutableState<Boolean>,
    shizukuPermission: MutableState<Boolean>,
    shizukuMsg: MutableState<String>,
    onRequestShizukuPermission: () -> Unit,
    onShizukuMode: () -> Unit,
    statusMsg: MutableState<String>,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ──
            Text(
                text = "Volume Booster",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Global Audio Boost via LoudnessEnhancer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Headset Status ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Status Headset",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    val hsText = if (headsetConnected.value) {
                        "Headset terhubung"
                    } else {
                        "Speaker HP"
                    }
                    Text(
                        text = hsText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (headsetConnected.value)
                            Color(0xFF4CAF50) else Color(0xFFFF9800),
                    )
                }
            }

            // ── Boost Controls ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // ON / OFF button
                    Button(
                        onClick = onToggleBoost,
                        enabled = enhancerSupported.value,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (boostEnabled.value)
                                Color(0xFFE53935) else Color(0xFF43A047)
                        )
                    ) {
                        Text(
                            text = if (boostEnabled.value) "OFF" else "ON",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Gain slider
                    val gain = boostGain.value
                    val gainDb = gain / 100.0
                    Text(
                        text = "Gain: $gain mB (${"%.1f".format(gainDb)} dB)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    Slider(
                        value = gain.toFloat(),
                        onValueChange = { v -> onGainChange(v.toInt()) },
                        valueRange = 0f..2000f,
                        steps = 19, // 20 intervals → 100 mB per step
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(),
                    )

                    // Slider labels
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "0 mB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "1000 mB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "2000 mB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Error / not supported message
                    if (!enhancerSupported.value) {
                        Text(
                            text = enhancerErrorMsg.value,
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // Reset button
                    TextButton(
                        onClick = onResetGain,
                        enabled = boostEnabled.value && enhancerSupported.value,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            text = "Reset",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ── Headset-only toggle ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = boostOnlyHeadset.value,
                    onCheckedChange = { onToggleHeadsetOnly() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedTrackColor = Color(0xFF555555),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Boost hanya saat headset",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // ── Max All ──
            Button(
                onClick = onMaxAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = "Max All",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Shizuku Mode ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Shizuku Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = shizukuMsg.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    when {
                        !shizukuInstalled.value -> {
                            Text(
                                text = "Unduh Shizuku dari https://shizuku.rikka.app/ " +
                                    "lalu jalankan dengan root atau ADB.",
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        shizukuRunning.value && !shizukuPermission.value -> {
                            Button(
                                onClick = onRequestShizukuPermission,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Mintakan Izin Shizuku")
                            }
                        }
                        shizukuRunning.value && shizukuPermission.value -> {
                            Button(
                                onClick = onShizukuMode,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7B1FA2)
                                ),
                            ) {
                                Text("Aktifkan Shizuku Mode")
                            }
                        }
                    }
                }
            }

            // ── Status messages ──
            if (statusMsg.value.isNotEmpty()) {
                Text(
                    text = statusMsg.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800),
                )
            }

            // ── Disclaimer (permanent) ──
            Spacer(Modifier.height(8.dp))
            Divider(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Volume berlebihan dapat merusak pendengaran dan speaker.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6B6B),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
