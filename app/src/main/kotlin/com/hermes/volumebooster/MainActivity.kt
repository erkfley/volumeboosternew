package com.hermes.volumebooster

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.align
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.rikka.shizuku.Shizuku
import dev.rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VolumeBooster"
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val PREFS_NAME = "VolumeBoosterPrefs"
        private const val KEY_BOOST_ENABLED = "boost_enabled"
        private const val KEY_BOOST_GAIN = "boost_gain"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_STRENGTH = "bass_strength"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_BAND_LEVELS = "eq_band_levels_csv"
        private const val KEY_EQ_NUM_BANDS = "eq_num_bands"
        private const val KEY_HEADSET_ONLY = "boost_only_headset"
    }

    // Audio Effects
    private lateinit var audioManager: AudioManager
    private var enhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null

    // UI State - Boost
    private val boostEnabled: MutableState<Boolean> = mutableStateOf(false)
    private val boostGain: MutableState<Int> = mutableStateOf(0)
    private val maxGainSupported: MutableState<Int> = mutableStateOf(3000)
    private val enhancerSupported: MutableState<Boolean> = mutableStateOf(true)
    private val enhancerErrorMsg: MutableState<String> = mutableStateOf("")

    // UI State - Bass
    private val bassEnabled: MutableState<Boolean> = mutableStateOf(false)
    private val bassStrength: MutableState<Int> = mutableStateOf(700)
    private val bassSupported: MutableState<Boolean> = mutableStateOf(true)

    // UI State - Equalizer
    private val eqEnabled: MutableState<Boolean> = mutableStateOf(false)
    private val eqNumBands: MutableState<Int> = mutableStateOf(0)
    private val eqBandLevels: MutableState<List<Int>> = mutableStateOf(emptyList())
    private val eqMinLevel: MutableState<Int> = mutableStateOf(-1500)
    private val eqMaxLevel: MutableState<Int> = mutableStateOf(1500)
    private val eqBandFreqs: MutableState<List<Int>> = mutableStateOf(emptyList())
    private val eqSupported: MutableState<Boolean> = mutableStateOf(true)

    // UI State - Peak meter
    private val peakLevel: MutableState<Float> = mutableStateOf(0f)
    private val isClipping: MutableState<Boolean> = mutableStateOf(false)
    private val visualizerActive: MutableState<Boolean> = mutableStateOf(false)

    // UI State - Headset & Shizuku
    private val headsetConnected: MutableState<Boolean> = mutableStateOf(false)
    private val boostOnlyHeadset: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuInstalled: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuRunning: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuPermission: MutableState<Boolean> = mutableStateOf(false)
    private val shizukuMsg: MutableState<String> = mutableStateOf("")
    private val statusMsg: MutableState<String> = mutableStateOf("")

    // Headset callback
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
    }

    // Shizuku permission-result listener
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuPermission.value = granted
                shizukuMsg.value = if (granted) {
                    "Izin diberikan — silakan aktifkan Shizuku Mode."
                } else {
                    "Izin ditolak. Buka Shizuku \u2192 Permission Manager untuk mengizinkan."
                }
            }
        }

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku listener registration failed", e)
        }
        restorePreferences()
        checkShizuku()
        checkHeadsetState()
        val handler = Handler(Looper.getMainLooper())
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio device callback", e)
        }
        if (boostEnabled.value) enableBoost()
        if (bassEnabled.value) enableBassBoost()
        if (eqEnabled.value) initEqualizer()
        if (visualizerActive.value) startPeakMeter()

        setContent {
            VolumeBoosterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VolumeBoosterScreen(
                        boostEnabled = boostEnabled, boostGain = boostGain,
                        maxGainSupported = maxGainSupported,
                        onGainChange = { updateGain(it) }, onToggleBoost = { toggleBoost() },
                        onResetGain = { resetGain() }, onMaxAll = { maxAllVolumes() },
                        enhancerSupported = enhancerSupported, enhancerErrorMsg = enhancerErrorMsg,
                        bassEnabled = bassEnabled, bassStrength = bassStrength,
                        onBassToggle = { toggleBassBoost() },
                        onBassChange = { updateBassStrength(it) }, bassSupported = bassSupported,
                        eqEnabled = eqEnabled, eqNumBands = eqNumBands,
                        eqBandLevels = eqBandLevels, eqMinLevel = eqMinLevel,
                        eqMaxLevel = eqMaxLevel, eqBandFreqs = eqBandFreqs,
                        eqSupported = eqSupported, onEqToggle = { toggleEqualizer() },
                        onEqBandChange = { idx, lvl -> updateEqBand(idx, lvl) },
                        peakLevel = peakLevel, isClipping = isClipping,
                        visualizerActive = visualizerActive,
                        onPeakMeterToggle = { if (visualizerActive.value) stopPeakMeter() else startPeakMeter() },
                        shizukuInstalled = shizukuInstalled, shizukuRunning = shizukuRunning,
                        shizukuPermission = shizukuPermission, shizukuMsg = shizukuMsg,
                        onRequestShizukuPermission = { requestShizukuPermission() },
                        onShizukuMode = { enableShizukuMode() },
                        onPresetBassPlus = { applyPresetBassPlus() },
                        onPresetMaxBass = { applyPresetMaxBass() },
                        onPresetFlat = { applyPresetFlat() },
                        onPresetOff = { applyPresetOff() },
                        headsetConnected = headsetConnected, boostOnlyHeadset = boostOnlyHeadset,
                        onToggleHeadsetOnly = {
                            boostOnlyHeadset.value = !boostOnlyHeadset.value
                            if (boostOnlyHeadset.value && !headsetConnected.value) disableBoost()
                        },
                        statusMsg = statusMsg,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkShizuku()
    }

    override fun onDestroy() {
        super.onDestroy()
        disableBoost()
        disableBassBoost()
        disableEqualizer()
        stopPeakMeter()
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (e: Exception) {}
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (e: Exception) {}
        savePreferences()
    }

    // ══════════════════════════════════════════════════════════════════
    // LOUDNESS ENHANCER
    private fun tryMaxGain(fx: LoudnessEnhancer, target: Int): Int {
        var gain = target
        while (gain >= 0) {
            try { fx.setTargetGain(gain); return gain }
            catch (e: Exception) { gain -= 100 }
        }
        return 0
    }

    private fun toggleBoost() {
        if (boostEnabled.value) disableBoost() else {
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
            enhancer = LoudnessEnhancer(0)
            enhancer?.let { fx ->
                val target = minOf(boostGain.value, 3000)
                val deviceMax = tryMaxGain(fx, 3000)
                val actualGain = minOf(target, deviceMax)
                maxGainSupported.value = deviceMax
                if (actualGain < target) {
                    statusMsg.value = "Device hanya mendukung gain hingga $deviceMax mB (diminta $target)."
                }
                fx.setTargetGain(actualGain)
                fx.enabled = true
            }
            boostEnabled.value = true
            enhancerSupported.value = true
            enhancerErrorMsg.value = ""
            // Enable BassBoost alongside LoudnessEnhancer
            enableBassBoost()
        } catch (e: UnsupportedOperationException) {
            enhancerSupported.value = false
            enhancerErrorMsg.value = "Perangkat tidak mendukung LoudnessEnhancer."
            boostEnabled.value = false
        } catch (e: Exception) {
            enhancerSupported.value = false
            enhancerErrorMsg.value = "Gagal init enhancer: ${e.message}"
            boostEnabled.value = false
        }
    }

    private fun disableBoost() {
        try {
            enhancer?.apply { enabled = false; release() }
        } catch (e: Exception) { Log.w(TAG, "Error releasing enhancer", e) }
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
    // BASS BOOST
    private fun toggleBassBoost() {
        if (bassEnabled.value) disableBassBoost() else enableBassBoost()
    }

    private fun enableBassBoost() {
        try {
            bassBoost = BassBoost(0)
            bassBoost?.let { bb ->
                bb.enabled = true
                bb.setStrength(bassStrength.value.toShort())
            }
            bassEnabled.value = true
        } catch (e: UnsupportedOperationException) {
            bassSupported.value = false
            bassEnabled.value = false
            Log.e(TAG, "BassBoost not supported", e)
        } catch (e: Exception) {
            bassEnabled.value = false
            Log.e(TAG, "BassBoost init failed", e)
        }
    }

    private fun disableBassBoost() {
        try { bassBoost?.release() } catch (e: Exception) {}
        bassBoost = null
        bassEnabled.value = false
    }

    private fun updateBassStrength(newStrength: Int) {
        bassStrength.value = newStrength
        try { bassBoost?.setStrength(newStrength.toShort()) } catch (e: Exception) {}
    }

    // EQUALIZER
    private fun initEqualizer() {
        try {
            equalizer = Equalizer(0)
            equalizer?.let { eq ->
                eq.enabled = true
                val numBands = eq.numberOfBands
                eqNumBands.value = numBands
                val range = eq.bandLevelRange
                eqMinLevel.value = range[0]
                eqMaxLevel.value = range[1]
                val freqs = mutableListOf<Int>()
                val levels = mutableListOf<Int>()
                for (i in 0 until numBands) {
                    val band = i.toShort()
                    freqs.add(eq.getBandFrequency(band))
                    levels.add(eq.getBandLevel(band))
                }
                eqBandFreqs.value = freqs
                eqBandLevels.value = levels
                eqSupported.value = true
            }
        } catch (e: UnsupportedOperationException) {
            eqSupported.value = false
            eqEnabled.value = false
            Log.e(TAG, "Equalizer not supported", e)
        } catch (e: Exception) {
            eqSupported.value = false
            eqEnabled.value = false
            Log.e(TAG, "Equalizer init failed", e)
        }
    }

    private fun disableEqualizer() {
        try { equalizer?.release() } catch (e: Exception) {}
        equalizer = null
        eqEnabled.value = false
    }

    private fun toggleEqualizer() {
        if (eqEnabled.value) { disableEqualizer() } else { initEqualizer() }
    }

    private fun updateEqBand(bandIndex: Int, level: Int) {
        val clamped = level.coerceIn(eqMinLevel.value, eqMaxLevel.value)
        val newLevels = eqBandLevels.value.toMutableList()
        if (bandIndex < newLevels.size) newLevels[bandIndex] = clamped
        eqBandLevels.value = newLevels
        try { equalizer?.setBandLevel(bandIndex.toShort(), clamped.toShort()) } catch (e: Exception) {}
    }

    // PRESETS
    private fun applyPresetBassPlus() {
        boostGain.value = 1500
        bassStrength.value = 700
        bassEnabled.value = true
        eqEnabled.value = true
        boostEnabled.value = true
        enableBoost()
        if (eqEnabled.value && equalizer == null) initEqualizer()
        // Boost low bands (freq < 250 Hz) by +6dB (600 centibels)
        val boostLow = 600
        val numBands = eqNumBands.value
        val newLevels = mutableListOf<Int>()
        for (i in 0 until numBands) {
            val freq = eqBandFreqs.value.getOrElse(i) { 0 }
            val level = if (freq < 250) boostLow else 0
            newLevels.add(level)
            try { equalizer?.setBandLevel(i.toShort(), level.toShort()) } catch (e: Exception) {}
        }
        eqBandLevels.value = newLevels
    }

    private fun applyPresetMaxBass() {
        boostGain.value = 3000
        bassStrength.value = 1000
        bassEnabled.value = true
        eqEnabled.value = true
        boostEnabled.value = true
        enableBoost()
        if (eqEnabled.value && equalizer == null) initEqualizer()
        val maxLowBoost = eqMaxLevel.value.coerceAtMost(800) // cap at +8dB
        val numBands = eqNumBands.value
        val newLevels = mutableListOf<Int>()
        for (i in 0 until numBands) {
            val freq = eqBandFreqs.value.getOrElse(i) { 0 }
            val level = if (freq < 250) maxLowBoost else 0
            newLevels.add(level)
            try { equalizer?.setBandLevel(i.toShort(), level.toShort()) } catch (e: Exception) {}
        }
        eqBandLevels.value = newLevels
    }

    private fun applyPresetFlat() {
        boostGain.value = 0
        bassStrength.value = 500
        bassEnabled.value = true
        eqEnabled.value = true
        boostEnabled.value = true
        enableBoost()
        if (eqEnabled.value && equalizer == null) initEqualizer()
        val numBands = eqNumBands.value
        val newLevels = MutableList(numBands) { 0 }
        for (i in 0 until numBands) {
            try { equalizer?.setBandLevel(i.toShort(), 0.toShort()) } catch (e: Exception) {}
        }
        eqBandLevels.value = newLevels
    }

    private fun applyPresetOff() {
        boostGain.value = 0
        bassEnabled.value = false
        eqEnabled.value = false
        boostEnabled.value = false
        disableBoost()
        disableBassBoost()
        disableEqualizer()
        statusMsg.value = "Semua efek audio dimatikan."
    }

    // MAX ALL VOLUMES
    private fun maxAllVolumes() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val streams = listOf(
                    AudioManager.STREAM_MUSIC to "Music",
                    AudioManager.STREAM_RING to "Ring",
                    AudioManager.STREAM_ALARM to "Alarm",
                    AudioManager.STREAM_NOTIFICATION to "Notif",
                    AudioManager.STREAM_SYSTEM to "System",
                    AudioManager.STREAM_VOICE_CALL to "Voice",
                    AudioManager.STREAM_DTMF to "DTMF",
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
                // After Shizuku mode, re-query max volume for STREAM_MUSIC
                if (shizukuPermission.value) {
                    try {
                        val newMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newMax, 0)
                        sb.append("Music (post-Shizuku): $newMax\n")
                    } catch (e: Exception) {
                        sb.append("Music (post-Shizuku): gagal\n")
                    }
                }
                sb.toString()
            }
            statusMsg.value = "Semua stream diatur ke maksimum:\n$result"
        }
    }

    // SHIZUKU
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
                runShizukuCommand(arrayOf("settings", "put", "global", "audio_safe_volume_state", "0"))
            }
            shizukuMsg.value = result
        }
    }

    private fun runShizukuCommand(cmd: Array<String>): String {
        return try {
            if (!Shizuku.pingBinder()) return "Shizuku belum berjalan."
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Izin Shizuku belum diberikan."
            }
            val method = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
            val finished = process.waitForTimeout(10L, TimeUnit.SECONDS)
            if (!finished) { process.destroy(); return "Perintah time out setelah 10 detik." }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                "Berhasil! Batas volume aman telah dimatikan.\n" +
                    "Sekarang Anda bisa menaikkan volume melebihi batas normal."
            } else {
                val err = try {
                    process.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { "" } ?: ""
                "Gagal (exit $exitCode): $err"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command failed", e)
            "Error: ${e.message}"
        }
    }

    // HEADSET DETECTION
    private fun isWiredHeadset(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    private fun checkHeadsetState() {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (isWiredHeadset(device.type)) { headsetConnected.value = true; return }
            }
            headsetConnected.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headset state", e)
            headsetConnected.value = false
        }
    }

    private fun handleHeadsetState(connected: Boolean) {
        headsetConnected.value = connected
        if (boostOnlyHeadset.value && !connected && boostEnabled.value) {
            disableBoost()
            statusMsg.value = "Headset dicabut — boost dimatikan otomatis."
        }
    }

    // PEAK METER
    private fun startPeakMeter() {
        try {
            visualizer = Visualizer(0)
            visualizer?.let { vis ->
                vis.setCaptureSize(Visualizer.getMinCaptureSize())
                vis.setDataCaptureListener({ _, waveform, _ ->
                    val peak = waveform.maxOfOrNull { abs(it.toInt()) }?.toFloat() ?: 0f
                    val normalized = peak / 128f
                    peakLevel.value = normalized
                    isClipping.value = normalized > 0.92f
                }, null, true, false)
                vis.enabled = true
            }
            visualizerActive.value = true
        } catch (e: Exception) {
            visualizerActive.value = false
            Log.e(TAG, "Visualizer init failed", e)
        }
    }

    private fun stopPeakMeter() {
        try { visualizer?.release() } catch (e: Exception) {}
        visualizer = null
        visualizerActive.value = false
        peakLevel.value = 0f
        isClipping.value = false
    }

    // PREFERENCES
    private fun getPrefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun savePreferences() {
        val prefs = getPrefs().edit()
        prefs.putBoolean(KEY_BOOST_ENABLED, boostEnabled.value)
        prefs.putInt(KEY_BOOST_GAIN, boostGain.value)
        prefs.putBoolean(KEY_BASS_ENABLED, bassEnabled.value)
        prefs.putInt(KEY_BASS_STRENGTH, bassStrength.value)
        prefs.putBoolean(KEY_EQ_ENABLED, eqEnabled.value)
        prefs.putInt(KEY_EQ_NUM_BANDS, eqNumBands.value)
        prefs.putString(KEY_EQ_BAND_LEVELS, eqBandLevels.value.joinToString(","))
        prefs.putBoolean(KEY_HEADSET_ONLY, boostOnlyHeadset.value)
        prefs.apply()
    }

    private fun restorePreferences() {
        val prefs = getPrefs()
        boostEnabled.value = prefs.getBoolean(KEY_BOOST_ENABLED, false)
        boostGain.value = prefs.getInt(KEY_BOOST_GAIN, 0)
        bassEnabled.value = prefs.getBoolean(KEY_BASS_ENABLED, false)
        bassStrength.value = prefs.getInt(KEY_BASS_STRENGTH, 700)
        eqEnabled.value = prefs.getBoolean(KEY_EQ_ENABLED, false)
        boostOnlyHeadset.value = prefs.getBoolean(KEY_HEADSET_ONLY, false)
        val levelsCsv = prefs.getString(KEY_EQ_BAND_LEVELS, "")
        if (!levelsCsv.isNullOrEmpty()) {
            val restored = levelsCsv.split(",").mapNotNull { it.toIntOrNull() }
            eqBandLevels.value = restored
            eqNumBands.value = restored.size
        }
    }
    }

// THEME
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
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}

// SCREEN
@Composable
fun VolumeBoosterScreen(
    boostEnabled: MutableState<Boolean>,
    boostGain: MutableState<Int>,
    maxGainSupported: MutableState<Int>,
    onGainChange: (Int) -> Unit,
    onToggleBoost: () -> Unit,
    onResetGain: () -> Unit,
    onMaxAll: () -> Unit,
    enhancerSupported: MutableState<Boolean>,
    enhancerErrorMsg: MutableState<String>,
    bassEnabled: MutableState<Boolean>,
    bassStrength: MutableState<Int>,
    onBassToggle: () -> Unit,
    onBassChange: (Int) -> Unit,
    bassSupported: MutableState<Boolean>,
    eqEnabled: MutableState<Boolean>,
    eqNumBands: MutableState<Int>,
    eqBandLevels: MutableState<List<Int>>,
    eqMinLevel: MutableState<Int>,
    eqMaxLevel: MutableState<Int>,
    eqBandFreqs: MutableState<List<Int>>,
    eqSupported: MutableState<Boolean>,
    onEqToggle: () -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    peakLevel: MutableState<Float>,
    isClipping: MutableState<Boolean>,
    visualizerActive: MutableState<Boolean>,
    onPeakMeterToggle: () -> Unit,
    shizukuInstalled: MutableState<Boolean>,
    shizukuRunning: MutableState<Boolean>,
    shizukuPermission: MutableState<Boolean>,
    shizukuMsg: MutableState<String>,
    onRequestShizukuPermission: () -> Unit,
    onShizukuMode: () -> Unit,
    onPresetBassPlus: () -> Unit,
    onPresetMaxBass: () -> Unit,
    onPresetFlat: () -> Unit,
    onPresetOff: () -> Unit,
    headsetConnected: MutableState<Boolean>,
    boostOnlyHeadset: MutableState<Boolean>,
    onToggleHeadsetOnly: () -> Unit,
    statusMsg: MutableState<String>,
) {
    val animatedPeak by animateFloatAsState(targetValue = peakLevel.value)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Text("Volume Booster", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("BassBoost + Equalizer + Peak Meter", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Headset Status
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status Headset", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    val hsText = if (headsetConnected.value) "Headset terhubung" else "Speaker HP"
                    Text(hsText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = if (headsetConnected.value) Color(0xFF4CAF50) else Color(0xFFFF9800))
                }
            }

            // Boost Controls
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(onClick = onToggleBoost, enabled = enhancerSupported.value, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = if (boostEnabled.value) Color(0xFFE53935) else Color(0xFF43A047))) {
                        Text(if (boostEnabled.value) "OFF" else "ON", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Gain: ${boostGain.value} mB (max device: ${maxGainSupported.value} mB)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = boostGain.value.toFloat(), onValueChange = { onGainChange(it.toInt()) }, valueRange = 0f..3000f, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0 mB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
                        Text("1500 mB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text("3000 mB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    }
                    if (!enhancerSupported.value) {
                        Text(enhancerErrorMsg.value, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onResetGain, enabled = boostEnabled.value && enhancerSupported.value, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Reset Gain", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onMaxAll, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp)) {
                        Text("Max All", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Bass Boost
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bass Boost", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = bassEnabled.value, onCheckedChange = { onBassToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4CAF50)))
                    }
                    if (bassEnabled.value && bassSupported.value) {
                        Text("Strength: ${bassStrength.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = bassStrength.value.toFloat(), onValueChange = { onBassChange(it.toInt()) }, valueRange = 0f..1000f, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("500", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Text("1000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                        }
                    }
                    if (!bassSupported.value) {
                        Text("BassBoost tidak didukung oleh perangkat ini.", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Equalizer
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Equalizer", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = eqEnabled.value, onCheckedChange = { onEqToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF7B1FA2)))
                    }
                    if (eqEnabled.value && eqSupported.value && eqNumBands.value > 0) {
                        Text("Range: ${eqMinLevel.value}–${eqMaxLevel.value} mB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (i in 0 until eqNumBands.value) {
                            val freq = eqBandFreqs.value.getOrElse(i) { 0 }
                            val level = eqBandLevels.value.getOrElse(i) { 0 }
                            Text("${freq} Hz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Slider(value = level.toFloat(), onValueChange = { onEqBandChange(i, it.toInt()) }, valueRange = eqMinLevel.value.toFloat()..eqMaxLevel.value.toFloat(), modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors())
                        }
                    }
                    if (!eqSupported.value) {
                        Text("Equalizer tidak didukung oleh perangkat ini.", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Preset Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPresetBassPlus, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("Bass+", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = onPresetMaxBass, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("Max Bass", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onPresetFlat, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("Flat", fontSize = 14.sp) }
                OutlinedButton(onClick = onPresetOff, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("Mati", fontSize = 14.sp) }
            }

            // Peak Meter
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Peak Meter", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = visualizerActive.value, onCheckedChange = { onPeakMeterToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3)))
                    }
                    if (visualizerActive.value) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(Color(0xFF333333), shape = RoundedCornerShape(4.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedPeak.coerceIn(0f, 1f))
                                    .background(if (isClipping.value) Color(0xFFFF5252) else Color(0xFF4CAF50))
                            )
                        }
                        if (isClipping.value) {
                            Text("CLIPPING!", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // Shizuku Mode
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Shizuku Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(shizukuMsg.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    when {
                        !shizukuInstalled.value -> {
                            Text("Unduh Shizuku dari https://shizuku.rikka.app/ lalu jalankan dengan root atau ADB.", color = Color(0xFFFF9800), style = MaterialTheme.typography.bodySmall)
                        }
                        shizukuRunning.value && !shizukuPermission.value -> {
                            Button(onClick = onRequestShizukuPermission, modifier = Modifier.fillMaxWidth()) { Text("Mintakan Izin Shizuku") }
                        }
                        shizukuRunning.value && shizukuPermission.value -> {
                            Button(onClick = onShizukuMode, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2))) { Text("Aktifkan Shizuku Mode") }
                        }
                    }
                }
            }

            // Headset Only Toggle
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = boostOnlyHeadset.value, onCheckedChange = { onToggleHeadsetOnly() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4CAF50)))
                Spacer(Modifier.width(8.dp))
                Text("Boost hanya saat headset", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }

            // Max All Button
            Button(onClick = onMaxAll, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp)) {
                Text("Max All Stream", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // Status
            if (statusMsg.value.isNotEmpty()) {
                Text(statusMsg.value, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            // Disclaimer + Honest Note
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text("Volume berlebihan dapat merusak pendengaran dan speaker.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Boost mic global membutuhkan root dan tidak didukung Android API publik. Efek global bekerja pada sebagian besar aplikasi musik/video, tapi beberapa aplikasi bypass output mix.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFAA00), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VolumeBoosterScreenPreview() {
    VolumeBoosterTheme {
        VolumeBoosterScreen(
            boostEnabled = mutableStateOf(false), boostGain = mutableStateOf(0),
            maxGainSupported = mutableStateOf(3000),
            onGainChange = {}, onToggleBoost = {}, onResetGain = {}, onMaxAll = {},
            enhancerSupported = mutableStateOf(true), enhancerErrorMsg = mutableStateOf(""),
            bassEnabled = mutableStateOf(false), bassStrength = mutableStateOf(700),
            onBassToggle = {}, onBassChange = {}, bassSupported = mutableStateOf(true),
            eqEnabled = mutableStateOf(false), eqNumBands = mutableStateOf(0),
            eqBandLevels = mutableStateOf(emptyList()), eqMinLevel = mutableStateOf(-1500),
            eqMaxLevel = mutableStateOf(1500), eqBandFreqs = mutableStateOf(emptyList()),
            eqSupported = mutableStateOf(true), onEqToggle = {}, onEqBandChange = { _, _ -> },
            peakLevel = mutableStateOf(0f), isClipping = mutableStateOf(false),
            visualizerActive = mutableStateOf(false), onPeakMeterToggle = {},
            shizukuInstalled = mutableStateOf(false), shizukuRunning = mutableStateOf(false),
            shizukuPermission = mutableStateOf(false), shizukuMsg = mutableStateOf(""),
            onRequestShizukuPermission = {}, onShizukuMode = {},
            onPresetBassPlus = {}, onPresetMaxBass = {}, onPresetFlat = {}, onPresetOff = {},
            headsetConnected = mutableStateOf(false), boostOnlyHeadset = mutableStateOf(false),
            onToggleHeadsetOnly = {}, statusMsg = mutableStateOf(""),
        )
    }
}
