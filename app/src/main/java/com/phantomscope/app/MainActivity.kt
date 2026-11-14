package com.phantomscope.app

import android.app.Activity
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.DisplayMetrics
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.view.Surface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import android.os.Process
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class RadioStation(val name: String, val streamUrl: String, val stationUuid: String)

data class GhostWordLog(val timestamp: Long, val word: String, val fieldValue: Float, val deviation: Float, val level: String)

data class InvestigationRecord(
    val timestamp: Long,
    val fieldValue: Float,
    val deviation: Float,
    val level: String,
    val evidencePaths: MutableList<String> = mutableListOf()
)

class MainActivity : Activity(), SensorEventListener {

    private val backgroundColor = Color.rgb(2, 5, 4)
    private val panelColor = Color.rgb(5, 13, 9)
    private val accentColor = Color.rgb(75, 255, 95)
    private val warningColor = Color.rgb(230, 55, 65)

    private lateinit var content: LinearLayout
    private lateinit var tabButtons: List<Button>
    private var mainRootView: View? = null
    private var cameraFullscreen = false

    private lateinit var sensorManager: SensorManager
    private var magneticSensor: Sensor? = null

    private lateinit var fieldValueText: TextView
    private lateinit var sensorStatusText: TextView
    private lateinit var radarView: RadarView

    private lateinit var currentText: TextView
    private lateinit var averageText: TextView
    private lateinit var maximumText: TextView
    private lateinit var minimumText: TextView

    private val spectrumSegments = mutableListOf<View>()
    private var spectrumBar: View? = null
    private var ghostWordText: TextView? = null
    private var settingsPreferences: SharedPreferences? = null
    private var selectedLanguage = "ko"
    private var ghostSpeechEnabled = true
    private var ghostTts: TextToSpeech? = null
    private var detectionTone: ToneGenerator? = null
    @Volatile private var ghostTtsReady = false
    private var ghostWordArmed = true
    private var lastGhostWordTime = 0L
    private val ghostWordCooldownMs = 5_000L
    // 음성 감지는 고정 20μT가 아니라 앱 실행 중 측정한 주변 자기장 기준값과 비교합니다.
    private var voiceBaselineTotal = 0f
    private var voiceBaselineSamples = 0
    private var voiceBaseline = 0f
    private var voiceBaselineReady = false
    private val recentGhostWords = ArrayDeque<String>()
    private val spectrumSamples = ArrayDeque<Float>()
    private var smoothedSpectrumValue = 0f
    private var lastSpectrumLevel = 0
    private var detectionRunning = false
    private var detectionButton: Button? = null
    private var ghostLogPreferences: SharedPreferences? = null
    private val ghostWordLogs = mutableListOf<GhostWordLog>()
    private var ghostLogContainer: LinearLayout? = null
    // 인터넷 라디오 스윕: 여러 스트림을 미리 버퍼링한 뒤 출력 볼륨을 교대합니다.
    private var radioDirectionForward = true
    private var radioSweepMs = 900
    private val radioHandler = Handler(Looper.getMainLooper())
    private val radioStations = mutableListOf<RadioStation>()
    private val radioPlayers = mutableListOf<MediaPlayer>()
    private val radioReadyIndexes = mutableSetOf<Int>()
    private val radioAttemptedIndexes = mutableSetOf<Int>()
    private var radioCurrentIndex = -1
    private var radioSweepRunning = false
    private var radioBatchStart = 0
    private var radioBatchSwitchCount = 0
    private val radioBatchSize = 7
    private var radioLoading = false
    private var radioStatusView: TextView? = null
    private var radioCurrentStationView: TextView? = null
    private var radioStationListView: TextView? = null
    private var radioStartButton: Button? = null
    private var radioStopButton: Button? = null
    private val readings = mutableListOf<Float>()
    private lateinit var logPreferences: SharedPreferences
    private val investigationRecords = mutableListOf<InvestigationRecord>()
    private var anomalyActive = false
    private var anomalyTriggerThreshold = 5f
    private val anomalyResetRatio = 0.70f
    private val maximumSavedRecords = 200

    private var audioRecord: AudioRecord? = null
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var soundLevelText: TextView? = null
    private var soundFrequencyText: TextView? = null
    private var soundStatusText: TextView? = null
    private var soundWaveView: SoundWaveView? = null
    private var soundToggleButton: Button? = null
    private var lastSoundEventTime = 0L
    private var lastMagneticChangeToneTime = 0L
    // 66μT 단위 구간 알림: 이미 알린 최고 구간과 마지막 알림 시각을 기억합니다.
    private var lastMagneticCueZone = -1
    private var soundEventActive = false
    private val soundEvents = mutableListOf<String>()
    private lateinit var soundPreferences: SharedPreferences
    private val soundEventThresholdDbfs = -25f

    @Volatile private var wavRecording = false
    @Volatile private var wavBytesWritten = 0L
    private var wavPcmStream: FileOutputStream? = null
    private var wavTempFile: File? = null
    private var wavRecordButton: Button? = null
    private var wavRecordStatus: TextView? = null
    private val savedAudioFiles = mutableListOf<String>()
    private var spectrumView: SpectrumView? = null

    private var camera: Camera? = null
    private var cameraSurfaceView: SurfaceView? = null
    private var cameraSurfaceHolder: SurfaceHolder? = null
    private var cameraNightOverlay: View? = null
    private var cameraStatusText: TextView? = null
    private var cameraGhostWordText: TextView? = null
    private var cameraControlsView: LinearLayout? = null
    private var cameraExitButton: Button? = null
    private var screenRecording = false
    private var screenCaptureRequestPending = false
    private val screenCaptureRequestCode = 9012
    private var nightVisionEnabled = false
    private var useFrontCamera = false
    private var flashEnabled = false
    private var cameraPageVisible = false
    private var cameraPreviewReady = false
    private var cameraPermissionPending = false

    private var poseDetector: PoseDetector? = null
    private var poseOverlay: PoseOverlayView? = null
    @Volatile private var poseProcessing = false
    private var poseRotationDegrees = 0
    private var poseImageWidth = 0
    private var poseImageHeight = 0
    private var poseDetectionEnabled = true

    private var baselineTotal = 0f
    private var baselineCount = 0
    private var baseline = 0f
    private var currentValue = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        // 배경 이미지는 별도 리소스로 관리합니다. 이미지 상단의 PHANTOM SCOPE 로고를
        // 그대로 노출하고, 실제 UI는 그 위에 투명 레이어로 표시합니다.
        val backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.phantom_background)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = BitmapDrawable(resources, backgroundBitmap).apply {
                gravity = Gravity.FILL
            }
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }

        val tabNames = listOf("■\n탐지", "■\n카메라", "■\n라디오", "■\n설정")

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(5))
            background = roundedBackground(Color.rgb(3, 9, 6), 18)
        }

        tabButtons = tabNames.mapIndexed { index, name ->
            Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setLineSpacing(dp(1).toFloat(), 0.9f)
                setTextColor(Color.rgb(115, 130, 120))
                background = roundedBackground(Color.rgb(3, 10, 6), 10)
                elevation = dp(2).toFloat()
                setOnClickListener { showPage(index) }

                tabRow.addView(
                    this,
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(5), dp(3), dp(5), dp(3))
                    }
                )
            }
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 이미지 안의 로고와 부딪히지 않도록 콘텐츠 시작 위치를 아래로 내립니다.
            setPadding(dp(2), dp(142), dp(2), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }

        scrollView.addView(content)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // 하단 고정 내비게이션
        root.addView(
            tabRow,
            LinearLayout.LayoutParams(-1, dp(66))
        )

        mainRootView = root
        setContentView(root)

        sensorManager = getSystemService(SENSOR_SERVICE)
                as SensorManager

        magneticSensor = sensorManager.getDefaultSensor(
            Sensor.TYPE_MAGNETIC_FIELD
        )

        if (magneticSensor == null && ::sensorStatusText.isInitialized) {
            sensorStatusText.text = "MAGNETIC SENSOR NOT SUPPORTED"
            sensorStatusText.setTextColor(warningColor)
        }

        logPreferences = getSharedPreferences("phantom_scope_logs", MODE_PRIVATE)
        soundPreferences = getSharedPreferences("phantom_scope_sound_events", MODE_PRIVATE)
        settingsPreferences = getSharedPreferences("phantom_scope_settings", MODE_PRIVATE)
        radioDirectionForward = settingsPreferences?.getBoolean("radio_forward", true) ?: true
        radioSweepMs = settingsPreferences?.getInt("radio_sweep_ms", 900)?.coerceIn(200, 1200) ?: 900
        try { detectionTone = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 85) } catch (_: Exception) { detectionTone = null }
        val savedSensitivity = settingsPreferences?.getInt("emf_sensitivity_percent", 52) ?: 52
        anomalyTriggerThreshold = if (savedSensitivity >= 0) {
            10f - (savedSensitivity / 99f) * 9.5f
        } else {
            settingsPreferences?.getFloat("emf_word_threshold_ut", 5f) ?: 5f
        }
        ghostLogPreferences = getSharedPreferences("phantom_scope_word_logs", MODE_PRIVATE)
        loadGhostWordLogs()
        selectedLanguage = settingsPreferences?.getString("ghost_language", "ko") ?: "ko"
        ghostSpeechEnabled = settingsPreferences?.getBoolean("ghost_speech_enabled", true) ?: true
        initializeGhostVoice()
        loadInvestigationRecords()
        loadSoundEvents()
        loadSavedAudioFiles()
        showPage(0)
    }

    private fun createSpectrum(): View {
        val panel = makePanel()
        // EMF LED는 개별 View 대신 Canvas에서 직접 그려 작은 칸도 색상이 선명하게 보이게 합니다.
        fieldValueText = TextView(this).apply { visibility = View.GONE }
        val ledView = EmfLedSpectrumView(this).apply {
            setFieldValue(currentValue)
        }
        spectrumBar = ledView
        panel.addView(ledView, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(2), dp(8), dp(2), dp(8))
        })
        sensorStatusText = TextView(this).apply {
            text = ""
            visibility = View.GONE
        }
        return panel
    }

    override fun onResume() {
        super.onResume()

        magneticSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        stopInternetRadio(releasePlayers = true)
        stopAudioMonitoring()
        if (screenRecording) stopScreenRecording()
        // The system screen-capture consent dialog briefly pauses the Activity.
        // Keep the camera alive until the permission result returns.
        if (!screenCaptureRequestPending) {
            cameraPageVisible = false
            cameraGhostWordText = null
            releaseCamera()
            closePoseDetector()
        }
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt(x * x + y * y + z * z)

        currentValue = magnitude

        updateSpectrum(magnitude)

        readings.add(magnitude)
        if (readings.size > 60) {
            readings.removeAt(0)
        }

        updateStatistics()

        // 기존 탐지 시각화 및 조사 기록용 기준값입니다. 음성 판정은 별도의 주변 기준값을 사용합니다.
        val deviation = abs(magnitude - 20f)
        if (::radarView.isInitialized) {
            radarView.updateData(deviation, x, y)
        }
        // 첫 30개 센서 샘플로 현재 장소의 자기장 기준값을 잡습니다.
        // 기준값이 준비되기 전에는 음성 감지를 하지 않습니다.
        if (!voiceBaselineReady) {
            voiceBaselineTotal += magnitude
            voiceBaselineSamples++
            if (voiceBaselineSamples >= 30) {
                voiceBaseline = voiceBaselineTotal / voiceBaselineSamples
                voiceBaselineReady = true
                lastMagneticCueZone = (voiceBaseline / 66f).toInt()
            }
        }

        if (detectionRunning) {
            // 기존 조사 기록 판정은 유지하고, 음성 출력은 기준값 대비 실제 변화량으로 별도 판정합니다.
            handleAnomaly(deviation, magnitude)
            playMagneticChangeCue(magnitude)
            maybeShowGhostWord(magnitude)
        } else if (voiceBaselineReady) {
            // 탐지 대기 중에도 기준값을 아주 천천히 보정해 장소 변화에 적응시킵니다.
            voiceBaseline += (magnitude - voiceBaseline) * 0.01f
        }
    }

    private fun updateSpectrum(value: Float) {
        // 20~200 μT를 300 LED에 직접 매핑합니다. 200 μT 이상은 빨간색 최대 단계입니다.
        spectrumSamples.addLast(value)
        while (spectrumSamples.size > 3) spectrumSamples.removeFirst()
        smoothedSpectrumValue = spectrumSamples.average().toFloat()
        val normalized = ((smoothedSpectrumValue - 20f) / 180f).coerceIn(0f, 1f)
        lastSpectrumLevel = (normalized * 300f).roundToInt().coerceIn(0, 300)

        // 색상은 단어 글자색과 완전히 독립적이며, 전용 Canvas 뷰가 직접 렌더링합니다.
        (spectrumBar as? EmfLedSpectrumView)?.setFieldValue(smoothedSpectrumValue)

        if (::sensorStatusText.isInitialized) {
            sensorStatusText.text = ""
            sensorStatusText.visibility = View.GONE
        }
    }

    private fun updateStatistics() {
        if (readings.isEmpty()) return

        val average = readings.average().toFloat()
        val maximum = readings.maxOrNull() ?: 0f
        val minimum = readings.minOrNull() ?: 0f

        if (::currentText.isInitialized) {
            currentText.text = formatField(currentValue)
            averageText.text = formatField(average)
            maximumText.text = formatField(maximum)
            minimumText.text = formatField(minimum)
        }
    }

    private fun formatField(value: Float): String {
        return String.format(Locale.US, "%.1f μT", value)
    }

    private fun showPage(index: Int) {
        if (index != 2) stopInternetRadio(releasePlayers = true)
        if (index != 1) {
            cameraPageVisible = false
            cameraGhostWordText = null
            releaseCamera()
            closePoseDetector()
            if (cameraFullscreen) {
                cameraFullscreen = false
                setContentView(mainRootView)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        content.removeAllViews()

        if (!cameraFullscreen) {
            tabButtons.forEachIndexed { i, button ->
                button.background = roundedBackground(
                    if (i == index) Color.rgb(8, 35, 16) else Color.rgb(3, 10, 6), 10
                )
                button.setTextColor(if (i == index) accentColor else Color.rgb(95, 110, 100))
                button.setShadowLayer(if (i == index) dp(8).toFloat() else 0f, 0f, 0f,
                    Color.rgb(30, 255, 75))
            }
        }

        when (index) {
            0 -> showRadarPage()
            1 -> showCameraPage()
            2 -> showRadioPage()
            3 -> showSettingsPage()
        }
    }

    private fun showCameraPage() {
        cameraPageVisible = true
        cameraPreviewReady = false
        cameraFullscreen = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN

        val cameraRoot = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(this)
        cameraSurfaceView = surfaceView
        cameraSurfaceHolder = surfaceView.holder
        cameraSurfaceHolder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraSurfaceHolder = holder
                cameraPreviewReady = true
                surfaceView.post {
                    if (cameraPageVisible) {
                        try { requestCameraPermissionAndStart() }
                        catch (t: Throwable) { showCameraFailure(t) }
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                cameraSurfaceHolder = holder
                if (cameraPageVisible && camera != null) {
                    try {
                        camera?.stopPreview()
                        camera?.setPreviewDisplay(holder)
                        camera?.startPreview()
                        attachPosePreviewCallback(camera!!)
                    } catch (t: Throwable) { showCameraFailure(t) }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraPreviewReady = false
                releaseCamera()
                cameraSurfaceHolder = null
            }
        })
        previewFrame.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val nightOverlay = View(this).apply {
            setBackgroundColor(Color.argb(75, 0, 255, 90))
            visibility = if (nightVisionEnabled) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
        }
        cameraNightOverlay = nightOverlay
        previewFrame.addView(nightOverlay, FrameLayout.LayoutParams(-1, -1))

        val poseView = PoseOverlayView(this)
        poseOverlay = poseView
        previewFrame.addView(poseView, FrameLayout.LayoutParams(-1, -1))

        // EMF 탭과 동일한 감지 결과를 카메라 화면에도 실시간 표시합니다.
        val cameraWordOverlay = TextView(this).apply {
            text = ""
            textSize = 27f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.argb(105, 0, 0, 0))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        cameraGhostWordText = cameraWordOverlay
        previewFrame.addView(
            cameraWordOverlay,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(26))
            }
        )
        cameraRoot.addView(previewFrame, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.argb(150, 5, 10, 14))
        }
        val status = TextView(this).apply {
            text = "CAMERA"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        // 상단의 CAMERA / 모션캡처 상태 문구는 화면에서 숨깁니다.
        // 상태 텍스트 객체는 오류 처리 코드와의 호환성을 위해 유지하되 레이아웃에는 추가하지 않습니다.
        status.visibility = View.GONE
        cameraStatusText = status
        val exitButton = Button(this).apply {
            text = "나가기 ✕"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(warningColor)
            setOnClickListener {
                if (screenRecording) {
                    stopScreenRecording()
                } else {
                    showPage(0)
                }
            }
        }
        cameraExitButton = exitButton
        topBar.addView(exitButton, LinearLayout.LayoutParams(dp(92), dp(44)))
        cameraRoot.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(175, 5, 10, 14))
        }
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeButton = Button(this).apply {
            text = if (nightVisionEnabled) "야간 모드 ON" else "일반 화면"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                nightVisionEnabled = !nightVisionEnabled
                applyCameraPreviewFilter()
                text = if (nightVisionEnabled) "야간 모드 ON" else "일반 화면"
            }
        }
        val switchButton = Button(this).apply {
            text = "전·후면 전환"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                useFrontCamera = !useFrontCamera
                releaseCamera()
                if (cameraPreviewReady) {
                    try { startCameraPreview() } catch (t: Throwable) { showCameraFailure(t) }
                }
            }
        }
        val poseToggle = Button(this).apply {
            text = if (poseDetectionEnabled) "인체 감지 ON" else "인체 감지 OFF"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                poseDetectionEnabled = !poseDetectionEnabled
                text = if (poseDetectionEnabled) "인체 감지 ON" else "인체 감지 OFF"
                if (!poseDetectionEnabled) poseOverlay?.clearPose()
                else camera?.let { attachPosePreviewCallback(it) }
            }
        }
        firstRow.addView(modeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        firstRow.addView(switchButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        firstRow.addView(poseToggle, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val flashButton = Button(this).apply {
            text = if (flashEnabled) "플래시 ON" else "플래시 OFF"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                flashEnabled = !flashEnabled
                updateCameraFlash()
                text = if (flashEnabled) "플래시 ON" else "플래시 OFF"
            }
        }
        val captureButton = Button(this).apply {
            text = "무료 사진 촬영"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 125, 85))
            setOnClickListener { captureEvidencePhoto() }
        }
        val recordButton = Button(this).apply {
            text = "동영상 녹화"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(155, 45, 45))
            setOnClickListener {
                requestScreenRecording()
            }
        }
        secondRow.addView(flashButton, LinearLayout.LayoutParams(0, dp(48), 0.8f))
        secondRow.addView(captureButton, LinearLayout.LayoutParams(0, dp(48), 1.1f))
        secondRow.addView(recordButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(secondRow)
        cameraControlsView = controls
        cameraRoot.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(cameraRoot)
    }

    private fun requestCameraPermissionAndStart() {
        if (!cameraPageVisible || !cameraPreviewReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionPending) {
                cameraPermissionPending = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 2101)
            }
            return
        }
        try {
            startCameraPreview()
        } catch (t: Throwable) {
            showCameraFailure(t)
        }
    }

    private fun showCameraFailure(error: Throwable) {
        cameraStatusText?.apply {
            text = "CAMERA ERROR: ${error.javaClass.simpleName}"
            setTextColor(warningColor)
        }
        releaseCamera()
    }

    private fun requestScreenRecording() {
        if (screenRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2102)
            return
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureRequestPending = true
            startActivityForResult(manager.createScreenCaptureIntent(), screenCaptureRequestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "화면 녹화 준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenCaptureRequestCode) {
            screenCaptureRequestPending = false
            if (resultCode == Activity.RESULT_OK && data != null) {
                val start = Intent(this, PhantomScreenRecordingService::class.java).apply {
                    action = PhantomScreenRecordingService.ACTION_START
                    putExtra(PhantomScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(PhantomScreenRecordingService.EXTRA_RESULT_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(start)
                } else {
                    startService(start)
                }
                screenRecording = true
                cameraControlsView?.visibility = View.GONE
                cameraExitButton?.text = "녹화 종료"
                cameraStatusText?.apply {
                    text = "● REC  |  기록 중"
                    setTextColor(Color.RED)
                }
                Toast.makeText(this, "화면 녹화를 시작했습니다. 모션캡쳐 화면이 영상에 포함됩니다.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "동영상 녹화 권한이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopScreenRecording() {
        if (!screenRecording) return
        val stop = Intent(this, PhantomScreenRecordingService::class.java).apply {
            action = PhantomScreenRecordingService.ACTION_STOP
        }
        startService(stop)
        screenRecording = false
        cameraControlsView?.visibility = View.VISIBLE
        cameraExitButton?.text = "나가기 ✕"
        cameraStatusText?.apply {
            text = "동영상 저장 중..."
            setTextColor(accentColor)
        }
        Toast.makeText(this, "녹화를 종료했습니다. 동영상 저장을 마무리합니다.", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            2101 -> {
                cameraPermissionPending = false
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (cameraPageVisible && cameraPreviewReady) {
                        try {
                            startCameraPreview()
                        } catch (t: Throwable) {
                            showCameraFailure(t)
                        }
                    }
                } else {
                    cameraStatusText?.apply {
                        text = "CAMERA PERMISSION DENIED"
                        setTextColor(warningColor)
                    }
                }
            }
            2102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    requestScreenRecording()
                } else {
                    Toast.makeText(this, "동영상에 주변 소리를 녹음하려면 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                }
            }
            501 -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startAudioMonitoring()
                } else {
                    Toast.makeText(
                        this,
                        "소리 감지를 사용하려면 마이크 권한이 필요합니다.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startCameraPreview() {
        if (!cameraPageVisible || !cameraPreviewReady || camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cameraCount = Camera.getNumberOfCameras()
            if (cameraCount == 0) {
                cameraStatusText?.text = "NO CAMERA AVAILABLE"
                return
            }
            val info = Camera.CameraInfo()
            var selectedId = -1
            for (id in 0 until cameraCount) {
                Camera.getCameraInfo(id, info)
                if ((!useFrontCamera && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) ||
                    (useFrontCamera && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)) {
                    selectedId = id
                    break
                }
            }
            if (selectedId < 0) {
                cameraStatusText?.text = "SELECTED CAMERA NOT AVAILABLE"
                return
            }

            val opened = Camera.open(selectedId)
            camera = opened
            val holder = cameraSurfaceHolder ?: run {
                releaseCamera()
                cameraStatusText?.text = "PREVIEW SURFACE NOT READY"
                return
            }
            val params = opened.parameters
            val focusModes = params.supportedFocusModes ?: emptyList<String>()
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            opened.parameters = params
            val displayDegrees = when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            Camera.getCameraInfo(selectedId, info)
            val result = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (info.orientation + displayDegrees) % 360
            } else {
                (info.orientation - displayDegrees + 360) % 360
            }
            opened.setDisplayOrientation(
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - result) % 360 else result
            )
            opened.setPreviewDisplay(holder)
            poseRotationDegrees = result
            val previewSize = opened.parameters.previewSize
            poseImageWidth = previewSize?.width ?: 640
            poseImageHeight = previewSize?.height ?: 480
            ensurePoseDetector()
            opened.setPreviewCallback { data, source ->
                if (poseDetectionEnabled && data != null && !poseProcessing && cameraPageVisible) {
                    val size = source.parameters.previewSize
                    if (size != null) processPoseFrame(data, size.width, size.height, poseRotationDegrees, useFrontCamera)
                }
            }
            opened.startPreview()
            cameraStatusText?.apply {
                text = if (useFrontCamera) "뒤를 확인하라" else "앞을 응시하라"
                setTextColor(accentColor)
            }
            applyCameraPreviewFilter()
            updateCameraFlash()
        } catch (e: Throwable) {
            showCameraFailure(e)
        }
    }

    private fun ensurePoseDetector() {
        if (poseDetector != null) return
        try {
            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)
        } catch (_: Exception) {
            cameraStatusText?.text = "POSE DETECTOR INIT FAILED"
        }
    }

    private fun attachPosePreviewCallback(active: Camera) {
        try {
            active.setPreviewCallback { data, source ->
                if (poseDetectionEnabled && data != null && !poseProcessing && cameraPageVisible) {
                    val size = source.parameters.previewSize
                    if (size != null) processPoseFrame(data, size.width, size.height, poseRotationDegrees, useFrontCamera)
                }
            }
        } catch (_: Exception) { }
    }

    private fun processPoseFrame(
        data: ByteArray, width: Int, height: Int, rotation: Int, front: Boolean
    ) {
        val detector = poseDetector ?: return
        poseProcessing = true
        try {
            val image = InputImage.fromByteArray(
                data, width, height, rotation, InputImage.IMAGE_FORMAT_NV21
            )
            detector.process(image)
                .addOnSuccessListener { pose ->
                    poseOverlay?.setPose(pose, width, height, rotation, front)
                    cameraStatusText?.text = if (pose.allPoseLandmarks.isNotEmpty()) {
                        "PERSON DETECTED  |  ${pose.allPoseLandmarks.size} BODY POINTS"
                    } else {
                        if (camera != null) "SCANNING FOR HUMAN FORM" else "CAMERA STANDBY"
                    }
                }
                .addOnFailureListener {
                    cameraStatusText?.text = "POSE ANALYSIS UNAVAILABLE"
                }
                .addOnCompleteListener {
                    poseProcessing = false
                }
        } catch (_: Exception) {
            poseProcessing = false
        }
    }

    private fun closePoseDetector() {
        try { poseDetector?.close() } catch (_: Exception) { }
        poseDetector = null
        poseOverlay?.clearPose()
        poseProcessing = false
    }

    private fun applyCameraPreviewFilter() {
        cameraNightOverlay?.visibility =
            if (nightVisionEnabled) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun updateCameraFlash() {
        val active = camera ?: return
        if (useFrontCamera) {
            flashEnabled = false
            return
        }
        try {
            val params = active.parameters
            val modes = params.supportedFlashModes ?: emptyList<String>()
            val desired = if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            if (modes.contains(desired)) {
                params.flashMode = desired
                active.parameters = params
            } else {
                cameraStatusText?.text = "FLASH NOT SUPPORTED"
            }
        } catch (_: Exception) {
            cameraStatusText?.text = "FLASH CONTROL UNAVAILABLE"
        }
    }

    @Suppress("DEPRECATION")
    private fun captureEvidencePhoto() {
        val active = camera
        if (active == null) {
            cameraStatusText?.apply {
                text = "CAMERA NOT READY"
                setTextColor(warningColor)
            }
            return
        }
        try {
            val poseSnapshot = capturePoseOverlaySnapshot()
            val subtitleSnapshot = cameraGhostWordText?.text?.toString()?.takeIf { it.isNotBlank() }
            active.takePicture(null, null) { bytes, sourceCamera ->
                try {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val displayName = "PHANTOM_$stamp.jpg"
                    val photoWithOverlay = composePoseOverlayIntoPhoto(bytes, poseSnapshot, subtitleSnapshot)
                    val savedLocation = savePhotoToGallery(photoWithOverlay, displayName)
                    attachEvidenceToLatestRecord(savedLocation.first)

                    runOnUiThread {
                        cameraStatusText?.apply {
                            text = "사진 저장 완료: Pictures/PHANTOM SCOPE/$displayName"
                            setTextColor(accentColor)
                        }
                        Toast.makeText(
                            this,
                            "사진이 갤러리의 Pictures/PHANTOM SCOPE 폴더에 저장되었습니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        cameraStatusText?.apply {
                            text = "PHOTO SAVE FAILED: ${error.javaClass.simpleName}"
                            setTextColor(warningColor)
                        }
                        Toast.makeText(this, "사진 저장 실패: ${error.message ?: "알 수 없는 오류"}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    try {
                        sourceCamera.startPreview()
                        attachPosePreviewCallback(sourceCamera)
                    } catch (_: Exception) { }
                }
            }
        } catch (error: Exception) {
            cameraStatusText?.apply {
                text = "PHOTO CAPTURE FAILED: ${error.javaClass.simpleName}"
                setTextColor(warningColor)
            }
        }
    }

    /**
     * Android 10+ saves into the public Pictures/PHANTOM SCOPE collection
     * through MediaStore, so the image appears in Gallery/Google Photos.
     * Older Android versions use the app-specific Pictures folder.
     * Returns the persisted location and a short location label.
     */
    private fun capturePoseOverlaySnapshot(): Bitmap? {
        val overlayView = poseOverlay ?: return null
        if (!poseDetectionEnabled || overlayView.width <= 0 || overlayView.height <= 0) return null
        return try {
            Bitmap.createBitmap(overlayView.width, overlayView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                overlayView.draw(canvas)
            }
        } catch (_: Exception) { null }
    }

    private fun composePoseOverlayIntoPhoto(jpegBytes: ByteArray, poseBitmap: Bitmap?, subtitle: String?): ByteArray {
        if ((poseBitmap == null || poseBitmap.isRecycled) && subtitle.isNullOrBlank()) return jpegBytes
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return jpegBytes
        return try {
            // Camera JPEG bytes are commonly landscape even when the live preview is portrait.
            // Rotate the pixels before compositing so the pose overlay uses the same orientation.
            val rotation = ((poseRotationDegrees % 360) + 360) % 360
            val rotateMatrix = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
                if (useFrontCamera) postScale(-1f, 1f)
            }
            val photoBitmap = if (rotation != 0 || useFrontCamera) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, rotateMatrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded

            val canvas = Canvas(photoBitmap)
            if (poseBitmap != null && !poseBitmap.isRecycled) {
                val srcW = poseBitmap.width.toFloat()
                val srcH = poseBitmap.height.toFloat()
                val scale = max(photoBitmap.width / srcW, photoBitmap.height / srcH)
                val scaledW = srcW * scale
                val scaledH = srcH * scale
                val left = (photoBitmap.width - scaledW) / 2f
                val top = (photoBitmap.height - scaledH) / 2f
                val dst = RectF(left, top, left + scaledW, top + scaledH)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(poseBitmap, null, dst, paint)
            }
            if (!subtitle.isNullOrBlank()) {
                val bandHeight = photoBitmap.height * 0.13f
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 0, 0, 0) }
                canvas.drawRect(0f, photoBitmap.height - bandHeight, photoBitmap.width.toFloat(), photoBitmap.height.toFloat(), band)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textSize = photoBitmap.width * 0.055f
                    setShadowLayer(photoBitmap.width * 0.008f, 0f, 0f, Color.BLACK)
                }
                canvas.drawText(subtitle, photoBitmap.width / 2f, photoBitmap.height - bandHeight / 2f + textPaint.textSize * 0.34f, textPaint)
            }
            val output = java.io.ByteArrayOutputStream()
            photoBitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)
            poseBitmap?.takeIf { !it.isRecycled }?.recycle()
            photoBitmap.recycle()
            output.toByteArray()
        } catch (_: Exception) {
            try { poseBitmap?.takeIf { !it.isRecycled }?.recycle(); if (!decoded.isRecycled) decoded.recycle() } catch (_: Exception) { }
            jpegBytes
        }
    }

    private fun savePhotoToGallery(bytes: ByteArray, displayName: String): Pair<String, String> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/PHANTOM SCOPE"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore에 사진 항목을 만들지 못했습니다.")

            try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw java.io.IOException("사진 저장 스트림을 열지 못했습니다.")
                output.use { it.write(bytes) }

                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
                return Pair(uri.toString(), "Pictures/PHANTOM SCOPE")
            } catch (error: Exception) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                throw error
            }
        }

        val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw java.io.IOException("사진 저장 폴더를 사용할 수 없습니다.")
        val folder = File(base, "PhantomScope")
        if (!folder.exists() && !folder.mkdirs()) {
            throw java.io.IOException("사진 저장 폴더를 만들 수 없습니다.")
        }
        val photo = File(folder, displayName)
        FileOutputStream(photo).use { it.write(bytes) }
        return Pair(photo.absolutePath, "앱 전용 Pictures/PhantomScope")
    }

    private fun evidenceDisplayName(path: String): String {
        if (path.startsWith("content://")) {
            try {
                contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) return cursor.getString(index) ?: "증거 사진"
                        }
                    }
            } catch (_: Exception) { }
            return "증거 사진"
        }
        return File(path).name
    }

    @Suppress("DEPRECATION")
    private fun releaseCamera() {
        try { camera?.setPreviewCallback(null) } catch (_: Exception) { }
        try { camera?.stopPreview() } catch (_: Exception) { }
        try { camera?.release() } catch (_: Exception) { }
        camera = null
    }

    private fun handleAnomaly(deviation: Float, fieldValue: Float) {
        if (deviation >= anomalyTriggerThreshold && !anomalyActive) {
            anomalyActive = true
            val level = when {
                deviation >= 20f -> "CRITICAL"
                deviation >= 10f -> "HIGH"
                else -> "ELEVATED"
            }
            investigationRecords.add(
                InvestigationRecord(
                    System.currentTimeMillis(),
                    fieldValue,
                    deviation,
                    level
                )
            )
            if (investigationRecords.size > maximumSavedRecords) {
                investigationRecords.removeAt(0)
            }
            saveInvestigationRecords()
        } else if (deviation < (anomalyTriggerThreshold * anomalyResetRatio)) {
            anomalyActive = false
        }
    }

    private fun attachEvidenceToLatestRecord(path: String) {
        if (investigationRecords.isEmpty()) {
            investigationRecords.add(
                InvestigationRecord(System.currentTimeMillis(), currentValue, 0f, "EVIDENCE")
            )
        }
        investigationRecords.last().evidencePaths.add(path)
        saveInvestigationRecords()
    }

    private fun openEvidence(path: String) {
        val isContentUri = path.startsWith("content://")
        if (!isContentUri && !File(path).exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (path.lowercase(Locale.US).endsWith(".jpg") || path.lowercase(Locale.US).endsWith(".jpeg")) {
            val bitmap = if (isContentUri) {
                try {
                    contentResolver.openInputStream(Uri.parse(path))?.use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) { null }
            } else {
                BitmapFactory.decodeFile(path)
            }
            if (bitmap == null) {
                Toast.makeText(this, "사진을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                return
            }
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            ScrollView(this).apply {
                addView(image)
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("조사 증거 사진")
                    .setView(this)
                    .setPositiveButton("닫기", null)
                    .show()
            }
        } else {
            try {
                val player = MediaPlayer().apply {
                    setDataSource(path)
                    setOnCompletionListener { it.release() }
                    prepare()
                    start()
                }
                Toast.makeText(this, "녹음 파일 재생 중", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "녹음 파일을 재생할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveInvestigationRecords() {
        val array = JSONArray()
        investigationRecords.forEach { record ->
            array.put(JSONObject().apply {
                put("timestamp", record.timestamp)
                put("fieldValue", record.fieldValue.toDouble())
                put("deviation", record.deviation.toDouble())
                put("level", record.level)
                put("evidencePaths", JSONArray(record.evidencePaths))
            })
        }
        logPreferences.edit().putString("records", array.toString()).apply()
    }

    private fun loadInvestigationRecords() {
        investigationRecords.clear()
        val saved = logPreferences.getString("records", null) ?: return
        try {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                investigationRecords.add(
                    InvestigationRecord(
                        item.optLong("timestamp"),
                        item.optDouble("fieldValue").toFloat(),
                        item.optDouble("deviation").toFloat(),
                        item.optString("level", "UNKNOWN"),
                        item.optJSONArray("evidencePaths")?.let { paths ->
                            MutableList(paths.length()) { index -> paths.optString(index) }
                        } ?: mutableListOf()
                    )
                )
            }
        } catch (_: Exception) {
            investigationRecords.clear()
        }
    }

    private fun showAudioPage() {
        addHeading("SOUND DETECTION")

        val livePanel = makePanel()

        val status = TextView(this).apply {
            text = if (audioRunning) "MICROPHONE ACTIVE" else "MICROPHONE STANDBY"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (audioRunning) accentColor else Color.LTGRAY)
        }
        soundStatusText = status
        livePanel.addView(status)

        val level = TextView(this).apply {
            text = "-- dBFS"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, dp(10), 0, dp(2))
        }
        soundLevelText = level
        livePanel.addView(level)

        val frequency = TextView(this).apply {
            text = "DOMINANT FREQUENCY  |  -- Hz"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(10))
        }
        soundFrequencyText = frequency
        livePanel.addView(frequency)

        val wave = SoundWaveView(this)
        soundWaveView = wave
        livePanel.addView(wave, LinearLayout.LayoutParams(-1, dp(130)))

        val spectrum = SpectrumView(this)
        spectrumView = spectrum
        livePanel.addView(spectrum, LinearLayout.LayoutParams(-1, dp(100)).apply {
            setMargins(0, dp(8), 0, 0)
        })

        val toggle = Button(this).apply {
            text = if (audioRunning) "마이크 감지 중지" else "마이크 감지 시작"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(if (audioRunning) warningColor else Color.rgb(30, 125, 85))
            setOnClickListener {
                if (audioRunning) {
                    stopAudioMonitoring()
                    showPage(1)
                } else {
                    requestAudioPermissionAndStart()
                }
            }
        }
        soundToggleButton = toggle
        livePanel.addView(
            toggle,
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                setMargins(0, dp(12), 0, 0)
            }
        )

        val wavStatus = TextView(this).apply {
            text = if (wavRecording) "WAV 녹음 중" else "WAV 녹음 대기"
            textSize = 11f
            setTextColor(if (wavRecording) warningColor else Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(4))
        }
        wavRecordStatus = wavStatus
        livePanel.addView(wavStatus)

        val wavButton = Button(this).apply {
            text = if (wavRecording) "WAV 녹음 종료 및 저장" else "WAV 녹음 시작"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(if (wavRecording) warningColor else Color.rgb(40, 95, 145))
            setOnClickListener {
                if (wavRecording) stopWavRecording() else {
                    if (!audioRunning) requestAudioPermissionAndStart()
                    if (audioRunning) startWavRecording()
                    else Toast.makeText(this@MainActivity, "먼저 마이크 감지를 시작하고 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
                }
                showPage(1)
            }
        }
        wavRecordButton = wavButton
        livePanel.addView(wavButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(0, dp(4), 0, 0)
        })

        content.addView(
            livePanel,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(12))
            }
        )

        addPanelText(
            "AUDIO ANALYSIS",
            "dBFS는 디지털 입력 신호의 상대적인 음량입니다.\\n" +
                "주요 주파수는 현재 입력에서 가장 강한 주파수 성분을 추정합니다.\\n" +
                "휴대폰 마이크와 기기 설정에 따라 측정값이 달라질 수 있습니다."
        )

        val eventsPanel = makePanel()
        val eventsTitle = TextView(this).apply {
            text = "SOUND EVENTS  |  ${soundEvents.size}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        eventsPanel.addView(eventsTitle)

        if (soundEvents.isEmpty()) {
            val empty = TextView(this).apply {
                text = "아직 저장된 소리 이벤트가 없습니다."
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(10), 0, 0)
            }
            eventsPanel.addView(empty)
        } else {
            soundEvents.takeLast(8).asReversed().forEach { event ->
                val row = TextView(this).apply {
                    text = event
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(7), 0, dp(7))
                }
                eventsPanel.addView(row)
            }
        }

        if (savedAudioFiles.isNotEmpty()) {
            val audioTitle = TextView(this).apply {
                text = "SAVED WAV RECORDINGS"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
                setPadding(0, dp(12), 0, dp(4))
            }
            eventsPanel.addView(audioTitle)
            savedAudioFiles.asReversed().toList().forEach { path ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val play = Button(this).apply {
                    text = "▶ ${evidenceDisplayName(path)}"
                    textSize = 10f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setOnClickListener { openEvidence(path) }
                }
                val delete = Button(this).apply {
                    text = "삭제"
                    textSize = 10f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(warningColor)
                    setOnClickListener { confirmDeleteWav(path) }
                }
                row.addView(play, LinearLayout.LayoutParams(0, dp(40), 1f))
                row.addView(delete, LinearLayout.LayoutParams(dp(64), dp(40)))
                eventsPanel.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }

        val clearEvents = Button(this).apply {
            text = "소리 이벤트 기록 삭제"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(48, 43, 52))
            isEnabled = soundEvents.isNotEmpty()
            setOnClickListener {
                soundEvents.clear()
                saveSoundEvents()
                showPage(1)
            }
        }
        eventsPanel.addView(
            clearEvents,
            LinearLayout.LayoutParams(-1, dp(40)).apply {
                setMargins(0, dp(8), 0, 0)
            }
        )
        content.addView(
            eventsPanel,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(12))
            }
        )
    }

    private fun confirmDeleteWav(path: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("녹음 파일 삭제")
            .setMessage("이 WAV 녹음 파일을 앱과 조사 기록에서 삭제할까요?\n${evidenceDisplayName(path)}")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                val file = File(path)
                val deleted = !file.exists() || file.delete()
                if (deleted) {
                    savedAudioFiles.remove(path)
                    investigationRecords.forEach { it.evidencePaths.remove(path) }
                    saveSavedAudioFiles()
                    saveInvestigationRecords()
                    Toast.makeText(this, "녹음 파일을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "파일 삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
                }
                showPage(1)
            }
            .show()
    }

    private fun requestAudioPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startAudioMonitoring()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                501
            )
        }
    }

    private fun startAudioMonitoring() {
        if (audioRunning) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionAndStart()
            return
        }

        val sampleRate = 44100
        val frameSize = 2048
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Toast.makeText(this, "오디오 입력을 초기화할 수 없습니다.", Toast.LENGTH_LONG).show()
            return
        }

        val bufferSize = maxOf(minBuffer, frameSize * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Exception) {
            null
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            Toast.makeText(this, "마이크를 시작하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }

        audioRecord = recorder
        try {
            recorder.startRecording()
        } catch (_: Exception) {
            recorder.release()
            audioRecord = null
            Toast.makeText(this, "마이크 녹음을 시작하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }

        audioRunning = true
        soundStatusText?.apply {
            text = "MICROPHONE ACTIVE"
            setTextColor(accentColor)
        }
        soundToggleButton?.apply {
            text = "마이크 감지 중지"
            setBackgroundColor(warningColor)
        }

        audioThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(frameSize)

            while (audioRunning) {
                val readCount = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    -1
                }

                if (readCount > 0) {
                    if (wavRecording) writeWavPcm(buffer, readCount)
                    var sumSquares = 0.0
                    var peak = 0

                    for (i in 0 until readCount) {
                        val sample = buffer[i].toInt()
                        sumSquares += sample.toDouble() * sample.toDouble()
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }

                    val rms = sqrt(sumSquares / readCount).toFloat()
                    val dbfs = if (rms <= 0.5f) {
                        -90f
                    } else {
                        (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-90f, 0f)
                    }

                    val frequency = estimateDominantFrequency(buffer, readCount, sampleRate)
                    val waveform = FloatArray(readCount) {
                        buffer[it].toFloat() / 32768f
                    }
                    val spectrumSamples = buffer.copyOf(readCount)

                    processSoundEvent(dbfs)

                    runOnUiThread {
                        if (audioRunning) {
                            spectrumView?.setSamples(spectrumSamples, sampleRate)
                            soundLevelText?.apply {
                                text = String.format(Locale.US, "%.1f dBFS", dbfs)
                                setTextColor(
                                    if (dbfs >= soundEventThresholdDbfs) warningColor
                                    else accentColor
                                )
                            }
                            soundFrequencyText?.text = String.format(
                                Locale.US,
                                "DOMINANT FREQUENCY  |  %.0f Hz  |  PEAK %d",
                                frequency,
                                peak
                            )
                            soundWaveView?.setWaveform(waveform)
                        }
                    }
                } else {
                    if (readCount < 0) break
                }
            }
        }.apply {
            name = "PhantomAudioAnalyzer"
            start()
        }
    }

    private fun startWavRecording() {
        if (!audioRunning) {
            Toast.makeText(this, "마이크 감지를 먼저 시작하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (wavRecording) return
        try {
            val folder = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "PhantomScope")
            if (!folder.exists()) folder.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val temp = File(folder, "PHANTOM_AUDIO_$stamp.pcm")
            wavPcmStream = FileOutputStream(temp)
            wavTempFile = temp
            wavBytesWritten = 0L
            wavRecording = true
            wavRecordStatus?.apply { text = "WAV 녹음 중 · 44.1 kHz / 16-bit / Mono"; setTextColor(warningColor) }
            wavRecordButton?.text = "WAV 녹음 종료 및 저장"
        } catch (_: Exception) {
            wavRecording = false
            Toast.makeText(this, "녹음 파일을 만들 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized
    private fun writeWavPcm(samples: ShortArray, count: Int) {
        val stream = wavPcmStream ?: return
        try {
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val value = samples[i].toInt()
                bytes[i * 2] = (value and 0xff).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
            }
            stream.write(bytes)
            wavBytesWritten += bytes.size
        } catch (_: Exception) {
            wavRecording = false
        }
    }

    @Synchronized
    private fun stopWavRecording() {
        if (!wavRecording && wavPcmStream == null) return
        wavRecording = false
        try { wavPcmStream?.flush(); wavPcmStream?.close() } catch (_: Exception) { }
        wavPcmStream = null
        val temp = wavTempFile
        if (temp != null && temp.exists() && wavBytesWritten > 0) {
            try {
                val wav = File(temp.parentFile, temp.nameWithoutExtension + ".wav")
                FileOutputStream(wav).use { out ->
                    out.write(createWavHeader(wavBytesWritten.toInt(), 44100, 1, 16))
                    temp.inputStream().use { input -> input.copyTo(out) }
                }
                temp.delete()
                savedAudioFiles.add(wav.absolutePath)
                if (savedAudioFiles.size > 100) savedAudioFiles.removeAt(0)
                saveSavedAudioFiles()
                attachEvidenceToLatestRecord(wav.absolutePath)
                wavRecordStatus?.apply { text = "저장 완료: ${wav.name}"; setTextColor(accentColor) }
                Toast.makeText(this, "WAV 녹음 저장 완료", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "WAV 파일 저장에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        } else {
            temp?.delete()
        }
        wavTempFile = null
        wavRecordButton?.text = "WAV 녹음 시작"
    }

    private fun createWavHeader(dataLength: Int, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val total = dataLength + 36
        val byteRate = sampleRate * channels * bits / 8
        val header = ByteArray(44)
        fun putAscii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        putAscii(0, "RIFF"); putInt(4, total); putAscii(8, "WAVE")
        putAscii(12, "fmt "); putInt(16, 16); putShort(20, 1)
        putShort(22, channels); putInt(24, sampleRate); putInt(28, byteRate)
        putShort(32, channels * bits / 8); putShort(34, bits)
        putAscii(36, "data"); putInt(40, dataLength)
        return header
    }

    private fun saveSavedAudioFiles() {
        soundPreferences.edit().putString("wav_files", JSONArray(savedAudioFiles).toString()).apply()
    }

    private fun loadSavedAudioFiles() {
        savedAudioFiles.clear()
        try {
            val array = JSONArray(soundPreferences.getString("wav_files", "[]"))
            for (i in 0 until array.length()) savedAudioFiles.add(array.optString(i))
        } catch (_: Exception) { savedAudioFiles.clear() }
    }

    private fun stopAudioMonitoring() {
        if (wavRecording) stopWavRecording()
        audioRunning = false
        val recorder = audioRecord
        audioRecord = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        audioThread = null
        soundStatusText?.apply {
            text = "MICROPHONE STANDBY"
            setTextColor(Color.LTGRAY)
        }
        soundLevelText?.text = "-- dBFS"
        soundFrequencyText?.text = "DOMINANT FREQUENCY  |  -- Hz"
        soundToggleButton?.apply {
            text = "마이크 감지 시작"
            setBackgroundColor(Color.rgb(30, 125, 85))
        }
    }

    private fun processSoundEvent(dbfs: Float) {
        val now = System.currentTimeMillis()

        if (dbfs >= soundEventThresholdDbfs && !soundEventActive) {
            soundEventActive = true

            if (now - lastSoundEventTime >= 5000L) {
                lastSoundEventTime = now
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.KOREA
                ).format(Date(now))
                soundEvents.add("$timestamp  |  ${String.format(Locale.US, "%.1f", dbfs)} dBFS")
                if (soundEvents.size > 100) soundEvents.removeAt(0)
                saveSoundEvents()
            }
        } else if (dbfs < -32f) {
            soundEventActive = false
        }
    }

    private fun saveSoundEvents() {
        val array = JSONArray()
        soundEvents.forEach { array.put(it) }
        soundPreferences.edit().putString("events", array.toString()).apply()
    }

    private fun loadSoundEvents() {
        soundEvents.clear()
        val saved = soundPreferences.getString("events", null) ?: return
        try {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                soundEvents.add(array.optString(i))
            }
        } catch (_: Exception) {
            soundEvents.clear()
        }
    }

    private fun estimateDominantFrequency(
        input: ShortArray,
        count: Int,
        sampleRate: Int
    ): Float {
        val n = 2048
        if (count < n) return 0f

        val real = DoubleArray(n)
        val imag = DoubleArray(n)

        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1))
            real[i] = input[i].toDouble() * window
        }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val temp = real[i]
                real[i] = real[j]
                real[j] = temp
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var start = 0

            while (start < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2

                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal

                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag

                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                start += length
            }
            length = length shl 1
        }

        var bestIndex = 1
        var bestMagnitude = 0.0
        for (i in 1 until n / 2) {
            val magnitude = real[i] * real[i] + imag[i] * imag[i]
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestIndex = i
            }
        }

        return bestIndex * sampleRate.toFloat() / n
    }

    private fun showInvestigationLogPage() {
        addHeading("INVESTIGATION LOG")
        val summary = makePanel()

        val count = TextView(this).apply {
            text = "SAVED RECORDS  |  ${investigationRecords.size}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        summary.addView(count)

        val info = TextView(this).apply {
            text = "감지 기록"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        summary.addView(info)

        val clear = Button(this).apply {
            text = "전체 기록 삭제"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(warningColor)
            isEnabled = investigationRecords.isNotEmpty()
            setOnClickListener {
                investigationRecords.clear()
                anomalyActive = false
                saveInvestigationRecords()
                showPage(3)
            }
        }
        summary.addView(clear, LinearLayout.LayoutParams(-1, dp(42)).apply {
            setMargins(0, dp(12), 0, 0)
        })
        content.addView(summary, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        if (investigationRecords.isEmpty()) {
            addPanelText(
                "NO SAVED INVESTIGATIONS",
                "아직 자동 기록된 자기장 변화가 없습니다.\n\n" +
                    "기록 없음"
            )
            return
        }

        investigationRecords.asReversed().forEachIndexed { reverseIndex, record ->
            val actualIndex = investigationRecords.lastIndex - reverseIndex
            val panel = makePanel()
            val levelColor = when (record.level) {
                "CRITICAL" -> Color.rgb(230, 45, 55)
                "HIGH" -> Color.rgb(255, 140, 30)
                else -> Color.rgb(230, 210, 50)
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val level = TextView(this).apply {
                text = record.level
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(levelColor)
            }
            val time = TextView(this).apply {
                text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
                    .format(Date(record.timestamp))
                textSize = 10f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.END
            }
            header.addView(level, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(time)
            panel.addView(header)

            val detail = TextView(this).apply {
                text = if (record.level == "EVIDENCE") {
                    "수동 증거 자료가 연결된 조사 기록"
                } else {
                    "FIELD  ${formatField(record.fieldValue)}\\n" +
                        "DEVIATION  +${String.format(Locale.US, "%.1f", record.deviation)} μT"
                }
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(8))
            }
            panel.addView(detail)

            record.evidencePaths.forEach { path ->
                val evidenceButton = Button(this).apply {
                    val name = evidenceDisplayName(path)
                    text = if (name.endsWith(".jpg", true)) "📷 사진 열기: $name" else "▶ 녹음 재생: $name"
                    textSize = 10f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setOnClickListener { openEvidence(path) }
                }
                panel.addView(evidenceButton, LinearLayout.LayoutParams(-1, dp(38)).apply {
                    setMargins(0, dp(4), 0, 0)
                })
            }

            val delete = Button(this).apply {
                text = "이 기록 삭제"
                textSize = 10f
                isAllCaps = false
                setTextColor(Color.LTGRAY)
                setBackgroundColor(Color.rgb(48, 43, 52))
                setOnClickListener {
                    if (actualIndex in investigationRecords.indices) {
                        investigationRecords.removeAt(actualIndex)
                        saveInvestigationRecords()
                        showPage(3)
                    }
                }
            }
            panel.addView(delete, LinearLayout.LayoutParams(-1, dp(38)))
            content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(10))
            })
        }
    }

    private fun showRadarPage() {
        val emfHeader = makePanel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val emfTag = TextView(this).apply {
            text = "EMF"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 1f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(12, 48, 29), 6)
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        val emfDescription = TextView(this).apply {
            text = "전자기장 측정기"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(9), 0, 0, 0)
        }
        emfHeader.addView(emfTag)
        emfHeader.addView(emfDescription)
        content.addView(emfHeader, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(2), 0, dp(10))
        })

        // 기존 EMF LED 스펙트럼을 유지합니다. 사용자는 숫자 없이 LED 변화만 확인합니다.
        val spectrumPanel = createSpectrum()
        content.addView(spectrumPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(2), 0, dp(12))
        })

        val wordPanel = makePanel().apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = neonPanelBackground()
        }
        val wordCaption = TextView(this).apply {
            text = "DETECTED WORD"
            textSize = 11f
            letterSpacing = 1.6f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(165, 190, 178))
            gravity = Gravity.CENTER
        }
        wordPanel.addView(wordCaption)
        ghostWordText = TextView(this).apply {
            text = "—"
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(accentColor)
            setShadowLayer(dp(13).toFloat(), 0f, 0f, Color.rgb(35, 255, 100))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        wordPanel.addView(ghostWordText)
        content.addView(wordPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(7))
        }
        val logTitle = TextView(this).apply {
            text = "DETECTION HISTORY"
            textSize = 11f
            letterSpacing = 1.2f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        logHeader.addView(logTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val clearVoiceLogsButton = Button(this).apply {
            text = "기록 제거"
            textSize = 10f
            minHeight = dp(32)
            minimumHeight = dp(32)
            isAllCaps = false
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(Color.rgb(255, 165, 165))
            background = roundedBackground(Color.argb(45, 125, 20, 25), 8)
            setOnClickListener {
                if (ghostWordLogs.isEmpty()) return@setOnClickListener
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("음성 기록 제거")
                    .setMessage("저장된 EMF 음성 기록을 모두 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ -> clearGhostWordLogs() }
                    .show()
            }
        }
        logHeader.addView(clearVoiceLogsButton)
        content.addView(logHeader)
        ghostLogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonPanelBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(ghostLogContainer, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })
        renderGhostWordLogs()

        val spacer = View(this)
        content.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        detectionButton = Button(this).apply {
            text = if (detectionRunning) "탐지 중지" else "◉  탐지 실시"
            textSize = 17f
            isAllCaps = false
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (detectionRunning) Color.WHITE else Color.rgb(0, 20, 7))
            setShadowLayer(if (detectionRunning) 0f else dp(8).toFloat(), 0f, 0f, Color.WHITE)
            background = detectionButtonBackground(detectionRunning)
            setOnClickListener {
                detectionRunning = !detectionRunning
                if (!detectionRunning) {
                    ghostTts?.stop()
                    if (::sensorStatusText.isInitialized) sensorStatusText.text = "STANDBY"
                } else {
                    if (::sensorStatusText.isInitialized) sensorStatusText.text = "SCANNING"
                }
                text = if (detectionRunning) "탐지 중지" else "◉  탐지 실시"
                setTextColor(if (detectionRunning) Color.WHITE else Color.rgb(0, 20, 7))
                background = detectionButtonBackground(detectionRunning)
            }
        }
        content.addView(detectionButton, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(14))
        })
    }

    private fun saveGhostWordLog(word: String, fieldValue: Float, deviation: Float) {
        val level = when {
            deviation >= 20f -> "CRITICAL"
            deviation >= 10f -> "HIGH"
            else -> "ELEVATED"
        }
        ghostWordLogs.add(0, GhostWordLog(System.currentTimeMillis(), word, fieldValue, deviation, level))
        while (ghostWordLogs.size > 100) ghostWordLogs.removeAt(ghostWordLogs.lastIndex)
        val array = JSONArray()
        ghostWordLogs.forEach { item ->
            array.put(JSONObject().apply {
                put("timestamp", item.timestamp); put("word", item.word)
                put("field", item.fieldValue.toDouble()); put("deviation", item.deviation.toDouble())
                put("level", item.level)
            })
        }
        ghostLogPreferences?.edit()?.putString("word_logs", array.toString())?.apply()
        renderGhostWordLogs()
    }

    private fun clearGhostWordLogs() {
        ghostWordLogs.clear()
        ghostLogPreferences?.edit()?.remove("word_logs")?.apply()
        renderGhostWordLogs()
    }

    private fun loadGhostWordLogs() {
        ghostWordLogs.clear()
        val raw = ghostLogPreferences?.getString("word_logs", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                ghostWordLogs.add(GhostWordLog(o.optLong("timestamp"), o.optString("word"),
                    o.optDouble("field").toFloat(), o.optDouble("deviation").toFloat(), o.optString("level")))
            }
        } catch (_: Exception) { ghostWordLogs.clear() }
    }

    private fun renderGhostWordLogs() {
        val container = ghostLogContainer ?: return
        container.removeAllViews()
        if (ghostWordLogs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "기록 없음"; textSize = 12f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        ghostWordLogs.take(8).forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val left = TextView(this).apply {
                text = "${formatter.format(Date(item.timestamp))}   ${item.word}"
                textSize = 12.5f
                setTextColor(Color.rgb(242, 248, 244))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val right = TextView(this).apply {
                text = "${item.fieldValue.formatOne()} μT"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(when (item.level) {
                    "CRITICAL" -> Color.rgb(255, 105, 105)
                    "HIGH" -> Color.rgb(255, 185, 80)
                    else -> accentColor
                })
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            val status = TextView(this).apply {
                text = item.level
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(4), dp(7), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.argb(28, 255, 35, 45))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), if (item.level == "CRITICAL") Color.rgb(190, 35, 45) else Color.rgb(130, 115, 55))
                }
            }
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(right, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(5), 0, dp(7), 0) })
            row.addView(status, LinearLayout.LayoutParams(-2, -2))
            container.addView(row)
            val divider = View(this).apply { setBackgroundColor(Color.rgb(35, 65, 49)) }
            container.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun Float.formatOne(): String = String.format(Locale.US, "%.1f", this)

    private fun playMagneticChangeCue(fieldStrength: Float) {
        // 절대 자기장 수치를 66μT 단위 구간으로 나눕니다.
        // 더 높은 새 구간에 진입할 때만 알리고, 하락 시에는 알림 구간을 낮추지 않습니다.
        // 알림 사이에는 최소 10초를 둡니다.
        if (!voiceBaselineReady) return
        val currentZone = (fieldStrength / 66f).toInt()
        if (currentZone <= lastMagneticCueZone) return

        val now = System.currentTimeMillis()
        if (now - lastMagneticChangeToneTime < 10_000L) return

        lastMagneticCueZone = currentZone
        lastMagneticChangeToneTime = now
        runOnUiThread {
            try {
                detectionTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 85)
                radioHandler.postDelayed({
                    try { detectionTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 85) } catch (_: Exception) { }
                }, 115L)
            } catch (_: Exception) { }
        }
    }

    private fun maybeShowGhostWord(fieldStrength: Float) {
        // 절대 자기장 기준은 20μT로 고정하고, 주변 기준값 대비 변화량으로 민감도를 판정합니다.
        val fixedMagneticThreshold = 20f
        val sensitivityPercent = (settingsPreferences?.getInt("emf_sensitivity_percent", 52) ?: 52)
            .coerceIn(0, 99) + 1
        // 민감도 53%에서 기준값 대비 약 5μT 변화가 감지 기준입니다.
        val detectionDeltaThreshold = (5f * (53f / sensitivityPercent)).coerceIn(1f, 10f)
        val magneticDelta = abs(fieldStrength - voiceBaseline)

        if (!voiceBaselineReady || fieldStrength < fixedMagneticThreshold || magneticDelta < detectionDeltaThreshold * 0.55f) {
            ghostWordArmed = true
            return
        }
        if (magneticDelta < detectionDeltaThreshold) return
        if (!ghostWordArmed) return
        if (System.currentTimeMillis() - lastGhostWordTime < ghostWordCooldownMs) return
        ghostWordArmed = false

        // 민감도 53%에서 약 40% 확률, 민감도에 따라 조정하되 최대 65%로 제한합니다.
        val probability = (0.40f * (sensitivityPercent / 53f)).coerceIn(0f, 0.65f)
        if (Math.random().toFloat() >= probability) return

        val words = ghostWordsForLanguage(selectedLanguage)
        val candidates = words.filter { it !in recentGhostWords }
        val chosen = (candidates.ifEmpty { words.filter { it != recentGhostWords.lastOrNull() }.ifEmpty { words } }).random()
        recentGhostWords.addLast(chosen)
        while (recentGhostWords.size > 5) recentGhostWords.removeFirst()
        lastGhostWordTime = System.currentTimeMillis()
        saveGhostWordLog(chosen, currentValue, magneticDelta)

        runOnUiThread {
            try { detectionTone?.startTone(ToneGenerator.TONE_PROP_ACK, 210) } catch (_: Exception) { }
            val wordColor = when {
                fieldStrength >= 70f -> Color.rgb(255, 90, 110)
                fieldStrength >= 50f -> Color.rgb(255, 170, 75)
                else -> accentColor
            }
            listOfNotNull(
                ghostWordText?.takeIf { it.isAttachedToWindow },
                cameraGhostWordText?.takeIf { it.isAttachedToWindow }
            ).forEach { wordView ->
                wordView.apply {
                    text = chosen
                    setTextColor(wordColor)
                    visibility = View.VISIBLE
                    animate().cancel()
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            speakGhostWord(chosen)
        }
    }

    private fun initializeGhostVoice() {
        ghostTts?.shutdown()
        ghostTtsReady = false
        ghostTts = TextToSpeech(this) { status ->
            ghostTtsReady = status == TextToSpeech.SUCCESS
            if (ghostTtsReady) configureGhostVoiceLanguage()
        }
    }

    private fun configureGhostVoiceLanguage() {
        val locale = when (selectedLanguage) {
            "en" -> Locale.US
            "ja" -> Locale.JAPAN
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "es" -> Locale("es", "ES")
            else -> Locale.KOREAN
        }
        val engine = ghostTts ?: return
        val availability = engine.isLanguageAvailable(locale)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            engine.language = Locale.KOREAN
        }
        engine.setSpeechRate(0.88f)
        engine.setPitch(0.82f)
    }

    private fun speakGhostWord(word: String) {
        if (!ghostSpeechEnabled || !ghostTtsReady) return
        runOnUiThread {
            try {
                configureGhostVoiceLanguage()
                ghostTts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "phantom-${System.currentTimeMillis()}")
            } catch (t: Throwable) {
                android.util.Log.w("PhantomVoice", "TTS playback failed; recording continues", t)
            }
        }
    }

    private fun ghostWordsForLanguage(language: String): List<String> {
        // 한국어 흉가 탐사 상황에서 자연스럽게 사용할 수 있는 일상 대화형 표현 120개
        return when (language) {
            "en" -> listOf("Hello", "Who is there?", "Where are you?", "Are you okay?", "Answer me", "Come here", "Stay there", "Don't move", "I heard that", "What was that?")
            "ja" -> listOf("こんにちは", "誰ですか", "どこにいるの", "大丈夫", "返事して", "こっちに来て", "動かないで", "聞こえた", "何の音", "ここにいる")
            "zh" -> listOf("你好", "你是谁", "你在哪里", "还好吗", "回答我", "过来", "别动", "我听见了", "那是什么声音", "我在这里")
            "es" -> listOf("Hola", "Quién está ahí", "Dónde estás", "Estás bien", "Respóndeme", "Ven aquí", "No te muevas", "Oí eso", "Qué fue eso", "Estoy aquí")
            else -> listOf(
                "안녕",
                "안녕하세요",
                "여보세요",
                "누구세요",
                "누구야",
                "누구 있어",
                "누가 왔어",
                "어디 있어",
                "어디야",
                "여기야",
                "저기야",
                "이쪽이야",
                "저쪽이야",
                "뭐야",
                "무슨 소리야",
                "무슨 일이야",
                "괜찮아",
                "괜찮니",
                "괜찮아요",
                "대답해",
                "말해줘",
                "들려요",
                "들려",
                "보여요",
                "보여",
                "기다려",
                "잠깐만",
                "잠시만",
                "천천히",
                "조심해",
                "도와줘",
                "도와주세요",
                "살려줘",
                "무서워",
                "추워",
                "춥다",
                "더워",
                "어두워",
                "밝아",
                "조용해",
                "시끄러워",
                "그만해",
                "멈춰",
                "움직이지 마",
                "가만히 있어",
                "오지 마",
                "이리 와",
                "가까이 와",
                "저리 가",
                "가지 마",
                "돌아와",
                "따라와",
                "따라오지 마",
                "나가",
                "들어와",
                "들어가",
                "나 여기 있어",
                "여기 있어",
                "저기 봐",
                "이쪽 봐",
                "뒤를 봐",
                "앞을 봐",
                "위에 봐",
                "아래 봐",
                "문 앞에 있어",
                "방 안에 있어",
                "복도에 있어",
                "계단에 있어",
                "밖에 있어",
                "안에 있어",
                "문 열어",
                "문 닫아",
                "불 켜",
                "불 꺼",
                "창문 열어",
                "창문 닫아",
                "누가 문 두드려",
                "문 두드리지 마",
                "발소리 들려",
                "소리 들었어",
                "방금 뭐였어",
                "다시 말해",
                "한 번 더",
                "이름이 뭐야",
                "내 이름 알아",
                "기억나",
                "기억해",
                "잊지 마",
                "찾아줘",
                "찾았어",
                "여기 봐",
                "저기 봐",
                "사진 찍어",
                "불빛 보여",
                "휴대폰 줘",
                "전화해",
                "전화 받아",
                "잠깐 나가",
                "여기 앉아",
                "저기 앉아",
                "혼자야",
                "같이 있어",
                "누가 따라와",
                "뒤에 있어",
                "옆에 있어",
                "아무도 없어",
                "사람 있어",
                "아이 있어",
                "엄마",
                "아빠",
                "집에 가자",
                "이제 가자",
                "아직이야",
                "다 왔어",
                "문이 열렸어",
                "문이 닫혔어",
                "불이 켜졌어",
                "불이 꺼졌어",
                "여기 추워",
                "나가고 싶어"
            )
        }
    }

    private fun hasPremiumAccess(): Boolean = true

    private fun showPremiumRequired(feature: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("PREMIUM 기능")
            .setMessage("${feature} 기능은 현재 사용할 수 없습니다.")
            .setNegativeButton("나중에", null)
            .setPositiveButton("설정 열기") { _, _ -> showPage(3) }
            .show()
    }

    // 유료 확장팩: 40개 대상 표현 × 25개 상황 문구 = 고유한 한국어 문구 1,000개.
    // 무료 기본 100개는 기존 ghostWordsForLanguage("ko") 목록에 그대로 보존합니다.
    private fun premiumGhostWordPack(): List<String> {
        val subjects = listOf(
            "그림자", "발자국", "검은 형체", "낯선 사람", "아이", "여자", "남자", "노인", "속삭임", "울음소리",
            "웃음소리", "문", "창문", "복도", "계단", "지하실", "다락방", "옷장", "거울", "침대 밑",
            "천장", "벽 뒤", "문틈", "창밖", "발소리", "손자국", "얼굴", "눈", "목소리", "숨소리",
            "기척", "낡은 인형", "빈 방", "닫힌 문", "검은 손", "차가운 손", "흐릿한 형체", "낯선 목소리", "긴 머리", "하얀 옷"
        )
        val templates = listOf(
            "%s가 보여", "%s가 있어", "%s가 움직여", "%s가 다가와", "%s가 기다려", "%s를 보지 마", "%s를 찾아", "%s를 따라가",
            "%s 뒤에 있어", "%s 안에 있어", "%s 아래를 봐", "%s 위를 봐", "%s에서 소리가 나", "%s가 널 불러", "%s가 널 보고 있어",
            "%s를 열지 마", "%s를 닫아", "%s를 기억해", "%s가 다시 왔어", "%s가 사라졌어", "%s가 가까워", "%s가 멀어졌어",
            "%s에게 대답해", "%s를 조심해", "%s가 여기 있어"
        )
        val result = ArrayList<String>(1000)
        for (template in templates) {
            for (subject in subjects) {
                result.add(template.format(subject))
                if (result.size == 1000) return result.distinct().take(1000)
            }
        }
        return result.distinct().take(1000)
    }

    private fun showRadioPage() {
        addHeading("INTERNET RADIO SWEEP")
        addPanelText(
            "SPIRIT BOX · 인터넷 라디오 스윕",
            "짧은 음성 조각이 빠르게 바뀌며 단어처럼 들릴 수 있는 ‘범블비 효과’를 활용한 엔터테인먼트 기능입니다. 실제 영적 존재와의 통신을 입증하는 장비는 아닙니다."
        )

        val statusPanel = makePanel()
        val statusTitle = TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 12f
            letterSpacing = 0.22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        radioStatusView = TextView(this).apply {
            text = "대기 중 · 시작 버튼을 눌러 7개 채널을 불러오세요"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        radioCurrentStationView = TextView(this).apply {
            text = "CURRENT CHANNEL  —"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, dp(7), 0, 0)
        }
        statusPanel.addView(statusTitle)
        statusPanel.addView(radioStatusView)
        statusPanel.addView(radioCurrentStationView)
        content.addView(statusPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val speedPanel = makePanel()
        val speedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speedTitle = TextView(this).apply {
            text = "스윕 전환 간격"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val speedValue = TextView(this).apply {
            text = "${radioSweepMs} ms / 채널"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        speedHeader.addView(speedTitle, LinearLayout.LayoutParams(0, -2, 1f))
        speedHeader.addView(speedValue)
        speedPanel.addView(speedHeader)
        val speedSeek = SeekBar(this).apply {
            max = 1000
            progress = radioSweepMs - 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    radioSweepMs = progress + 200
                    speedValue.text = "${radioSweepMs} ms / 채널"
                    if (fromUser) {
                        settingsPreferences?.edit()?.putInt("radio_sweep_ms", radioSweepMs)?.apply()
                        if (radioSweepRunning) scheduleNextRadioChannel()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        speedPanel.addView(speedSeek)
        speedPanel.addView(TextView(this).apply {
            text = "200ms 빠른 전환  ·  900ms 기본  ·  1,200ms 느린 전환"
            textSize = 10.5f
            setTextColor(Color.rgb(145, 165, 150))
        })
        content.addView(speedPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val directionPanel = makePanel()
        directionPanel.addView(TextView(this).apply {
            text = "채널 순서"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        val directionButton = Button(this).apply {
            text = if (radioDirectionForward) "정방향  →" else "←  역방향"
            isAllCaps = false
            setTextColor(accentColor)
            background = roundedBackground(Color.rgb(8, 28, 16), 9)
            setOnClickListener {
                radioDirectionForward = !radioDirectionForward
                settingsPreferences?.edit()?.putBoolean("radio_forward", radioDirectionForward)?.apply()
                text = if (radioDirectionForward) "정방향  →" else "←  역방향"
            }
        }
        directionPanel.addView(directionButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(0, dp(8), 0, 0)
        })
        content.addView(directionPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val controlPanel = makePanel()
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        radioStartButton = Button(this).apply {
            text = "라디오 불러오기 / 시작"
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = roundedBackground(accentColor, 10)
            setOnClickListener { loadInternetRadioStations() }
        }
        radioStopButton = Button(this).apply {
            text = "정지"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(55, 25, 25), 10)
            setOnClickListener { stopInternetRadio(releasePlayers = true) }
        }
        controlRow.addView(radioStartButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(0, 0, dp(5), 0)
        })
        controlRow.addView(radioStopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(5), 0, 0, 0)
        })
        controlPanel.addView(controlRow)
        radioStationListView = TextView(this).apply {
            text = "채널 목록이 아직 준비되지 않았습니다.\n인터넷 연결 후 시작 버튼을 눌러주세요."
            textSize = 11.5f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, 0)
        }
        controlPanel.addView(radioStationListView)
        content.addView(controlPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

    }

    private fun loadInternetRadioStations() {
        if (radioLoading) return
        stopInternetRadio(releasePlayers = true)
        radioLoading = true
        radioStatusView?.text = "전 세계 인터넷 방송 검색 중…"
        radioStartButton?.isEnabled = false
        Thread {
            // 시작 시 딱 한 번 7개 채널만 검색합니다. 스왑 중에는 재검색하지 않습니다.
            val stations = fetchRadioStations(
                "hidebroken=true&is_https=true&order=random&limit=7", 7
            ).distinctBy { it.streamUrl }.take(7)
            runOnUiThread {
                radioLoading = false
                radioStartButton?.isEnabled = true
                if (stations.isEmpty()) {
                    radioStatusView?.text = "방송 목록을 가져오지 못했습니다. 인터넷 연결을 확인하고 다시 시도하세요."
                    return@runOnUiThread
                }
                radioStations.clear()
                radioStations.addAll(stations)
                radioBatchStart = 0
                radioBatchSwitchCount = 0
                radioStationListView?.text = "채널 이름 비공개 · 총 ${radioStations.size}개 채널 준비"
                radioStatusView?.text = "${radioStations.size}개 채널 확보 · 7개 채널 연결 중…"
                prepareRadioPlayers()
            }
        }.start()
    }

    private fun fetchRadioStations(filters: String, maxResults: Int): List<RadioStation> {
        var connection: HttpURLConnection? = null
        return try {
            val cleanFilters = filters.replace(Regex("&?limit=\\d+"), "")
            val endpoint = "https://de1.api.radio-browser.info/json/stations/search?$cleanFilters&limit=${maxResults.coerceIn(1, 1000)}"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "PHANTOM-SCOPE/1.1 Android")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(body)
            val result = mutableListOf<RadioStation>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                if (!stream.startsWith("https://", true)) continue
                val name = item.optString("name").trim().ifBlank { "UNKNOWN STATION" }
                result.add(RadioStation(name, stream, item.optString("stationuuid")))
            }
            result
        } catch (e: Exception) {
            android.util.Log.w("PhantomRadio", "Station directory request failed", e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun prepareRadioPlayers() {
        releaseRadioPlayers()
        if (radioStations.isEmpty()) return
        radioReadyIndexes.clear()
        radioAttemptedIndexes.clear()
        val batch = radioStations.take(10)
        radioStatusView?.text = "7개 채널 연결 중…"
        batch.forEachIndexed { localIndex, station ->
            val player = MediaPlayer()
            radioPlayers.add(player)
            try {
                player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                player.setVolume(0f, 0f)
                player.setDataSource(station.streamUrl)
                player.setOnPreparedListener { prepared ->
                    try {
                        prepared.setVolume(0f, 0f)
                        prepared.start()
                        radioAttemptedIndexes.add(localIndex)
                        radioReadyIndexes.add(localIndex)
                        maybeStartRadioSweepWhenReady()
                    } catch (e: Exception) {
                        radioAttemptedIndexes.add(localIndex)
                        android.util.Log.w("PhantomRadio", "Could not start stream ${station.name}", e)
                        maybeStartRadioSweepWhenReady()
                    }
                }
                player.setOnErrorListener { _, what, extra ->
                    android.util.Log.w("PhantomRadio", "Stream error ${station.name}: $what/$extra")
                    radioAttemptedIndexes.add(localIndex)
                    radioReadyIndexes.remove(localIndex)
                    maybeStartRadioSweepWhenReady()
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                radioAttemptedIndexes.add(localIndex)
                android.util.Log.w("PhantomRadio", "Stream preparation failed: ${station.name}", e)
                maybeStartRadioSweepWhenReady()
            }
        }
    }

    private fun maybeStartRadioSweepWhenReady() {
        val batchCount = radioPlayers.size
        radioStatusView?.text = "전체 ${radioStations.size}개 방송 · 현재 묶음 연결 ${radioReadyIndexes.size}/$batchCount"
        if (radioAttemptedIndexes.size < batchCount || radioSweepRunning) return
        if (radioReadyIndexes.isEmpty()) {
            radioStatusView?.text = "연결 가능한 방송이 없습니다. 새로고침을 시도하세요."
            return
        }
        radioStatusView?.text = "${radioReadyIndexes.size}개 채널 연결 완료 · 정지 버튼을 누를 때까지 반복 스왑"
        radioBatchSwitchCount = 0
        radioSweepRunning = true
        radioCurrentIndex = if (radioDirectionForward) -1 else 0
        switchRadioChannel()
    }

    private fun switchRadioChannel() {
        if (!radioSweepRunning || radioReadyIndexes.isEmpty()) return
        val count = radioPlayers.size
        var next = radioCurrentIndex
        var found = false
        var checked = 0
        while (checked < count) {
            next = if (radioDirectionForward) (next + 1) % count else (next - 1 + count) % count
            if (radioReadyIndexes.contains(next)) {
                found = true
                break
            }
            checked++
        }
        if (!found) {
            radioStatusView?.text = "재생 가능한 방송이 없습니다. 다시 불러와 주세요."
            stopInternetRadio(releasePlayers = true)
            return
        }
        radioPlayers.forEachIndexed { index, player ->
            try { player.setVolume(if (index == next) 1f else 0f, if (index == next) 1f else 0f) }
            catch (_: Exception) { }
        }
        radioCurrentIndex = next
        val globalIndex = radioBatchStart + next
        radioCurrentStationView?.text = "CURRENT CHANNEL  ${globalIndex + 1}/${radioStations.size}"
        // 준비된 7개 채널만 정지 버튼을 누를 때까지 계속 순환합니다.
        radioBatchSwitchCount++
        scheduleNextRadioChannel()
    }

    private fun scheduleNextRadioBatch() {
        radioHandler.removeCallbacks(radioSweepRunnable)
        if (!radioSweepRunning) return
        radioHandler.postDelayed({
            if (!radioSweepRunning) return@postDelayed
            radioSweepRunning = false
            radioBatchStart = (radioBatchStart + radioBatchSize) % radioStations.size
            radioBatchSwitchCount = 0
            radioCurrentIndex = -1
            prepareRadioPlayers()
        }, radioSweepMs.toLong())
    }

    private fun scheduleNextRadioChannel() {
        radioHandler.removeCallbacks(radioSweepRunnable)
        if (radioSweepRunning) radioHandler.postDelayed(radioSweepRunnable, radioSweepMs.toLong())
    }

    private val radioSweepRunnable = object : Runnable {
        override fun run() {
            if (radioSweepRunning) switchRadioChannel()
        }
    }

    private fun stopInternetRadio(releasePlayers: Boolean) {
        radioSweepRunning = false
        radioHandler.removeCallbacks(radioSweepRunnable)
        radioPlayers.forEach { player ->
            try { player.setVolume(0f, 0f) } catch (_: Exception) { }
        }
        if (releasePlayers) {
            releaseRadioPlayers()
            radioStatusView?.text = if (radioStations.isEmpty()) "대기 중" else "정지됨 · ${radioStations.size}개 방송 목록 유지"
            radioCurrentStationView?.text = "CURRENT CHANNEL  —"
        }
    }

    private fun releaseRadioPlayers() {
        radioPlayers.forEach { player ->
            try { player.setOnPreparedListener(null); player.setOnErrorListener(null); player.stop() } catch (_: Exception) { }
            try { player.reset(); player.release() } catch (_: Exception) { }
        }
        radioPlayers.clear()
        radioReadyIndexes.clear()
        radioAttemptedIndexes.clear()
    }

    private fun showSettingsPage() {

        val languagePanel = makePanel()
        val languageTitle = TextView(this).apply {
            text = "언어"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        languagePanel.addView(languageTitle)

        val languageOptions = listOf("한국어", "English", "日本語", "中文", "Español")
        val languageCodes = listOf("ko", "en", "ja", "zh", "es")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                languageOptions
            )
            setSelection(languageCodes.indexOf(selectedLanguage).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedLanguage = languageCodes[position]
                    settingsPreferences?.edit()?.putString("ghost_language", selectedLanguage)?.apply()
                    configureGhostVoiceLanguage()
                    ghostWordText?.text = "탐사 대기 중..."
                }
            }
        }
        languagePanel.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(0, dp(12), 0, 0)
        })
        content.addView(languagePanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val voicePanel = makePanel()
        val voiceSwitch = Switch(this).apply {
            text = "목소리"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = ghostSpeechEnabled
            setOnCheckedChangeListener { _, checked ->
                ghostSpeechEnabled = checked
                settingsPreferences?.edit()?.putBoolean("ghost_speech_enabled", checked)?.apply()
                if (!checked) ghostTts?.stop()
            }
        }
        voicePanel.addView(voiceSwitch)
        content.addView(voicePanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val sensitivityPanel = makePanel()
        val sensitivityHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sensitivityTitle = TextView(this).apply {
            text = "민감도"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val savedPercent = settingsPreferences?.getInt("emf_sensitivity_percent", 52) ?: 52
        val initialPercent = if (savedPercent >= 0) savedPercent else {
            (((10f - anomalyTriggerThreshold) / 9.5f) * 99f).roundToInt().coerceIn(0, 99)
        }
        val sensitivityValue = TextView(this).apply {
            text = "${initialPercent + 1}%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        sensitivityHeader.addView(sensitivityTitle, LinearLayout.LayoutParams(0, -2, 1f))
        sensitivityHeader.addView(sensitivityValue)
        sensitivityPanel.addView(sensitivityHeader)
        val sensitivitySeek = SeekBar(this).apply {
            max = 99
            progress = initialPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = progress + 1
                    // 민감도 값이 높을수록 감지 임계값이 낮아집니다.
                    anomalyTriggerThreshold = (5f * (53f / (progress + 1f))).coerceIn(1f, 10f)
                    sensitivityValue.text = "${percent}%"
                    if (fromUser) {
                        settingsPreferences?.edit()
                            ?.putInt("emf_sensitivity_percent", progress)
                            ?.putFloat("emf_word_threshold_ut", anomalyTriggerThreshold)
                            ?.apply()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        sensitivityPanel.addView(sensitivitySeek)
        content.addView(sensitivityPanel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })


    }

    private fun addStoreItem(title: String, description: String, symbol: String) {
        val panel = makePanel()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = symbol
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            gravity = Gravity.CENTER
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val descriptionView = TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, 0)
        }
        textColumn.addView(titleView)
        textColumn.addView(descriptionView)
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(row)
        content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(10))
        })
    }

    private fun addStatisticsPanel() {
        val panel = makePanel()

        val heading = TextView(this).apply {
            text = "MAGNETIC FIELD STATISTICS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.LTGRAY)
            letterSpacing = 1f
        }

        panel.addView(heading)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        currentText = addStatRow(grid, "CURRENT", "-- μT")
        averageText = addStatRow(grid, "AVERAGE", "-- μT")
        maximumText = addStatRow(grid, "MAXIMUM", "-- μT")
        minimumText = addStatRow(grid, "MINIMUM", "-- μT")

        panel.addView(grid)
        content.addView(panel)

        updateStatistics()
    }

    private fun addStatRow(
        parent: LinearLayout,
        label: String,
        initial: String
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }

        val name = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val value = TextView(this).apply {
            text = initial
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            gravity = Gravity.END
        }

        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)

        parent.addView(row)

        return value
    }

    private fun addHeading(text: String) {
        val heading = TextView(this).apply {
            this.text = text.uppercase(Locale.ROOT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 1.5f
            setTextColor(accentColor)
            setPadding(dp(5), dp(5), dp(5), dp(10))
        }
        content.addView(heading)
    }

    private fun addPanelText(title: String, description: String) {
        val panel = makePanel()

        val heading = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val body = TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }

        panel.addView(heading)
        panel.addView(body)

        content.addView(
            panel,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(4), 0, dp(12))
            }
        )
    }

    private fun makePanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = neonPanelBackground()
            elevation = dp(4).toFloat()
        }
    }

    private fun neonPanelBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(218, 5, 22, 14), Color.argb(224, 2, 10, 8), Color.argb(218, 3, 15, 10))
        ).apply {
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.rgb(45, 155, 88))
        }
    }

    private fun detectionButtonBackground(active: Boolean): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (active) intArrayOf(Color.rgb(170, 35, 45), Color.rgb(105, 12, 20))
            else intArrayOf(Color.rgb(95, 255, 105), Color.rgb(35, 220, 75))
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(2), if (active) Color.rgb(255, 105, 110) else Color.rgb(125, 255, 150))
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.rgb(35, 125, 65))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // 센서 정확도 변경 시 별도 처리 없음
    }

    override fun onDestroy() {
        stopInternetRadio(releasePlayers = true)
        try {
            ghostTts?.stop()
            ghostTts?.shutdown()
            ghostTts = null
            ghostTtsReady = false
        } catch (_: Exception) { }
        try { detectionTone?.release(); detectionTone = null } catch (_: Exception) { }
        super.onDestroy()
    }
}

/**
 * 300개의 EMF LED를 Canvas로 직접 렌더링합니다.
 * 개별 View의 녹색 테두리가 실제 LED 색상을 덮던 문제를 제거합니다.
 */
class EmfLedSpectrumView(context: android.content.Context) : View(context) {
    private val ledCount = 300
    private var fieldValue = 0f
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val inactiveColor = Color.rgb(17, 35, 24)

    fun setFieldValue(value: Float) {
        fieldValue = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
        invalidate()
    }

    private fun colorFor(value: Float): Int {
        val t = ((value - 20f) / 180f).coerceIn(0f, 1f)
        // 녹색(120°) → 노랑(60°) → 주황(30°) → 빨강(0°)
        val hue = when {
            t <= 0.5f -> 120f - (t / 0.5f) * 60f
            t <= (2f / 3f) -> 60f - ((t - 0.5f) / (1f / 6f)) * 30f
            else -> 30f - ((t - (2f / 3f)) / (1f / 3f)) * 30f
        }.coerceIn(0f, 120f)
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val normalized = ((fieldValue - 20f) / 180f).coerceIn(0f, 1f)
        val litCount = (normalized * ledCount).roundToInt().coerceIn(0, ledCount)
        val activeColor = colorFor(fieldValue)

        val gap = (width * 0.0018f).coerceAtLeast(0.45f)
        val cellWidth = (width - gap * (ledCount - 1)) / ledCount.toFloat()
        val top = height * 0.20f
        val bottom = height * 0.80f
        val radius = (cellWidth * 0.22f).coerceAtMost(height * 0.12f)

        for (i in 0 until ledCount) {
            ledPaint.color = if (i < litCount) activeColor else inactiveColor
            ledPaint.alpha = if (i < litCount) 255 else 190
            val left = i * (cellWidth + gap)
            canvas.drawRoundRect(
                left, top, left + cellWidth, bottom,
                radius, radius, ledPaint
            )
        }
    }
}


/**
 * 마이크 입력 파형을 표시하는 간단한 Canvas 뷰입니다.
 * 오디오 분석 스레드에서 전달된 샘플을 화면 스레드에서 갱신합니다.
 */
class SoundWaveView(context: android.content.Context) : View(context) {
    private var waveform = FloatArray(0)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 255, 95)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 95, 55)
        strokeWidth = 1f
    }

    fun setWaveform(samples: FloatArray) {
        waveform = samples.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val centerY = height / 2f
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerPaint)
        if (waveform.isEmpty()) return

        val path = Path()
        val count = waveform.size
        val step = width.toFloat() / max(1, count - 1)
        waveform.forEachIndexed { index, sample ->
            val x = index * step
            val y = centerY - sample.coerceIn(-1f, 1f) * height * 0.42f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}

/**
 * 입력 오디오의 간단한 대역별 에너지를 막대 형태로 표시합니다.
 * 외부 FFT 라이브러리 없이 시간영역 샘플을 구간별 RMS로 계산합니다.
 */
class SpectrumView(context: android.content.Context) : View(context) {
    private var levels = FloatArray(32)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 255, 95)
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(17, 35, 24)
        style = Paint.Style.FILL
    }

    fun setSamples(samples: ShortArray, sampleRate: Int) {
        if (samples.isEmpty() || sampleRate <= 0) return
        val next = FloatArray(levels.size)
        val chunkSize = max(1, samples.size / next.size)
        for (band in next.indices) {
            val start = band * chunkSize
            if (start >= samples.size) break
            val end = min(samples.size, start + chunkSize)
            var sum = 0.0
            for (i in start until end) {
                val normalized = samples[i].toDouble() / 32768.0
                sum += normalized * normalized
            }
            val rms = sqrt(sum / max(1, end - start)).toFloat()
            next[band] = (rms * 5f).coerceIn(0f, 1f)
        }
        levels = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val gap = max(2f, width * 0.006f)
        val barWidth = (width - gap * (levels.size - 1)) / levels.size.toFloat()
        levels.forEachIndexed { index, level ->
            val left = index * (barWidth + gap)
            val barHeight = max(dpHeight(3f), height * level)
            val top = height - barHeight
            canvas.drawRoundRect(
                left, 0f, left + barWidth, height.toFloat(),
                barWidth * 0.18f, barWidth * 0.18f, backgroundPaint
            )
            canvas.drawRoundRect(
                left, top, left + barWidth, height.toFloat(),
                barWidth * 0.18f, barWidth * 0.18f, barPaint
            )
        }
    }

    private fun dpHeight(value: Float): Float =
        value * resources.displayMetrics.density
}

class RadarView(context: android.content.Context) : View(context) {

    private val radarGreen = Color.rgb(60, 220, 150)
    private val radarRed = Color.rgb(230, 45, 60)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 12, 17)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 105, 80)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = radarGreen
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = radarRed
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
    }

    private var sweepAngle = 0f
    private var deviation = 0f
    private var direction = 0f
    private var calibrationProgress = 0f
    private var calibrated = false

    private var lastUpdate = 0L
    private var pulse = 0f

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.rgb(8, 12, 17))
    }

    fun setCalibration(progress: Float) {
        calibrationProgress = progress.coerceIn(0f, 1f)
        calibrated = calibrationProgress >= 1f
        invalidate()
    }

    fun updateData(value: Float, x: Float, y: Float) {
        deviation = value
        direction = Math.toDegrees(
            atan2(y.toDouble(), x.toDouble())
        ).toFloat()

        calibrated = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f - dp(4)
        val radius = minOf(width, height) * 0.36f

        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            backgroundPaint
        )

        // 원형 레이더 격자
        for (i in 1..4) {
            canvas.drawCircle(
                cx, cy, radius * i / 4f, gridPaint
            )
        }

        canvas.drawLine(
            cx - radius, cy, cx + radius, cy, gridPaint
        )

        canvas.drawLine(
            cx, cy - radius, cx, cy + radius, gridPaint
        )

        // 스캔 라인 잔상
        for (i in 8 downTo 1) {
            val angle = Math.toRadians(
                (sweepAngle - i * 5f).toDouble()
            )

            val endX = cx + cos(angle).toFloat() * radius
            val endY = cy + sin(angle).toFloat() * radius

            trailPaint.color = radarGreen
            trailPaint.alpha = (150 * (1f - i / 9f)).toInt()

            canvas.drawLine(
                cx, cy, endX, endY, trailPaint
            )
        }

        // 현재 스캔 라인
        val radians = Math.toRadians(sweepAngle.toDouble())

        val endX = cx + cos(radians).toFloat() * radius
        val endY = cy + sin(radians).toFloat() * radius

        sweepPaint.color = radarGreen

        canvas.drawLine(
            cx, cy, endX, endY, sweepPaint
        )

        // 감지점 맥동 효과
        if (calibrated && deviation > 1.5f) {
            val distance = radius * (
                0.25f + (deviation / 30f).coerceIn(0f, 0.65f)
            )

            val angle = Math.toRadians(direction.toDouble())

            val dotX = cx + cos(angle).toFloat() * distance
            val dotY = cy - sin(angle).toFloat() * distance

            val pulseRadius = 7f + pulse * 8f

            val dotColor = when {
                deviation < 5f -> Color.YELLOW
                deviation < 10f -> Color.rgb(255, 140, 30)
                else -> radarRed
            }

            dotPaint.color = dotColor
            dotPaint.alpha = 55

            canvas.drawCircle(
                dotX, dotY, pulseRadius + 5f, dotPaint
            )

            dotPaint.alpha = 255

            canvas.drawCircle(
                dotX, dotY, 7f, dotPaint
            )
        }

        // 중앙점
        dotPaint.color = radarGreen
        canvas.drawCircle(cx, cy, 4f, dotPaint)

        // 방향 표시
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - radius - 10f, textPaint)
        canvas.drawText("S", cx, cy + radius + 18f, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("W", cx - radius - 18f, cy + 5f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("E", cx + radius + 18f, cy + 5f, textPaint)

        // 상태 표시
        textPaint.textAlign = Paint.Align.CENTER

        val status = if (calibrated) {
            when {
                deviation < 1.5f -> "SIGNAL: LOW"
                deviation < 5f -> "SIGNAL: MODERATE"
                deviation < 10f -> "SIGNAL: HIGH"
                else -> "SIGNAL: VERY HIGH"
            }
        } else {
            "CALIBRATING SENSOR"
        }

        canvas.drawText(
            status,
            cx,
            height - dp(10).toFloat(),
            textPaint
        )

        // 초당 약 15도 회전
        sweepAngle = (sweepAngle + 1.5f) % 360f

        pulse += 0.12f
        if (pulse > 1f) pulse = 0f

        if (isAttachedToWindow) {
            postInvalidateDelayed(100)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
