package com.phantomscope.app

import android.app.Activity
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
import android.view.ViewGroup
import android.graphics.drawable.ColorDrawable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import android.os.Process
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

data class InvestigationRecord(
    val timestamp: Long,
    val fieldValue: Float,
    val deviation: Float,
    val level: String,
    val evidencePaths: MutableList<String> = mutableListOf()
)

class MainActivity : Activity(), SensorEventListener {

    private val backgroundColor = Color.rgb(8, 10, 15)
    private val panelColor = Color.rgb(20, 23, 31)
    private val accentColor = Color.rgb(70, 220, 150)
    private val warningColor = Color.rgb(230, 55, 65)

    private lateinit var content: LinearLayout
    private lateinit var tabButtons: List<Button>

    private lateinit var sensorManager: SensorManager
    private var magneticSensor: Sensor? = null

    private lateinit var fieldValueText: TextView
    private lateinit var sensorStatusText: TextView
    private lateinit var radarView: RadarView
    private lateinit var graphView: FieldGraphView

    private lateinit var currentText: TextView
    private lateinit var averageText: TextView
    private lateinit var maximumText: TextView
    private lateinit var minimumText: TextView

    private val spectrumSegments = mutableListOf<View>()
    private val readings = mutableListOf<Float>()
    private lateinit var logPreferences: SharedPreferences
    private val investigationRecords = mutableListOf<InvestigationRecord>()
    private var anomalyActive = false
    private val anomalyTriggerThreshold = 5f
    private val anomalyResetThreshold = 3.5f
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }

        val title = TextView(this).apply {
            text = "PHANTOM SCOPE"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))

        val subtitle = TextView(this).apply {
            text = "HORROR INVESTIGATION SYSTEM"
            textSize = 10f
            letterSpacing = 2f
            setTextColor(warningColor)
            gravity = Gravity.CENTER
        }

        root.addView(subtitle)
        root.addView(createSpectrum())

        val tabNames = listOf("레이더", "사운드", "카메라", "기록")

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(12))
        }

        tabButtons = tabNames.mapIndexed { index, name ->
            Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setOnClickListener { showPage(index) }

                tabRow.addView(
                    this,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    }
                )
            }
        }

        root.addView(tabRow)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(content)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)

        sensorManager = getSystemService(SENSOR_SERVICE)
                as SensorManager

        magneticSensor = sensorManager.getDefaultSensor(
            Sensor.TYPE_MAGNETIC_FIELD
        )

        if (magneticSensor == null) {
            fieldValueText.text = "-- μT"
            sensorStatusText.text = "MAGNETIC SENSOR NOT SUPPORTED"
            sensorStatusText.setTextColor(warningColor)
        } else {
            sensorStatusText.text = "SENSOR READY - WAITING"
        }

        logPreferences = getSharedPreferences("phantom_scope_logs", MODE_PRIVATE)
        soundPreferences = getSharedPreferences("phantom_scope_sound_events", MODE_PRIVATE)
        loadInvestigationRecords()
        loadSoundEvents()
        loadSavedAudioFiles()
        showPage(0)
    }

    private fun createSpectrum(): View {
        val panel = makePanel()

        val label = TextView(this).apply {
            text = "REAL MAGNETIC FIELD SPECTRUM"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 1f
            setTextColor(Color.LTGRAY)
        }

        panel.addView(label)

        fieldValueText = TextView(this).apply {
            text = "-- μT"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, dp(8), 0, dp(5))
        }

        panel.addView(fieldValueText)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val colors = listOf(
            Color.rgb(40, 210, 120),
            Color.rgb(230, 210, 50),
            Color.rgb(255, 150, 35),
            Color.rgb(230, 45, 55)
        )

        colors.forEach { color ->
            val segment = View(this).apply {
                background = roundedBackground(color, 5)
                alpha = 0.25f
            }

            spectrumSegments.add(segment)

            bar.addView(
                segment,
                LinearLayout.LayoutParams(0, dp(12), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            )
        }

        panel.addView(bar)

        sensorStatusText = TextView(this).apply {
            text = "SENSOR INITIALIZING"
            textSize = 10f
            setTextColor(Color.LTGRAY)
        }

        panel.addView(sensorStatusText)

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
        stopAudioMonitoring()
        cameraPageVisible = false
        releaseCamera()
        closePoseDetector()
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

        if (::graphView.isInitialized) {
            graphView.setReadings(readings.toList())
        }

        updateStatistics()

        if (baselineCount < 30) {
            baselineTotal += magnitude
            baselineCount++

            if (baselineCount == 30) {
                baseline = baselineTotal / baselineCount
            }

            if (::radarView.isInitialized) {
                radarView.setCalibration(
                    baselineCount.toFloat() / 30f
                )
            }
        } else {
            val deviation = abs(magnitude - baseline)

            if (::radarView.isInitialized) {
                radarView.updateData(deviation, x, y)
            }

            handleAnomaly(deviation, magnitude)
        }
    }

    private fun updateSpectrum(value: Float) {
        fieldValueText.text = String.format(
            Locale.US, "%.1f μT", value
        )

        val level = when {
            value < 50f -> 0
            value < 70f -> 1
            value < 100f -> 2
            else -> 3
        }

        val labels = listOf(
            "NORMAL", "ELEVATED", "HIGH", "CRITICAL"
        )

        val colors = listOf(
            Color.rgb(40, 210, 120),
            Color.rgb(230, 210, 50),
            Color.rgb(255, 150, 35),
            Color.rgb(230, 45, 55)
        )

        spectrumSegments.forEachIndexed { index, segment ->
            segment.alpha = if (index == level) 1f else 0.25f
        }

        fieldValueText.setTextColor(colors[level])
        sensorStatusText.text = "LIVE SENSOR  |  ${labels[level]}"
        sensorStatusText.setTextColor(colors[level])
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
        if (index != 2) {
            cameraPageVisible = false
            releaseCamera()
        }
        content.removeAllViews()

        tabButtons.forEachIndexed { i, button ->
            button.setBackgroundColor(
                if (i == index) warningColor else panelColor
            )

            button.setTextColor(Color.WHITE)
        }

        when (index) {
            0 -> showRadarPage()
            1 -> showAudioPage()
            2 -> showCameraPage()
            3 -> showInvestigationLogPage()
        }
    }


    private fun showCameraPage() {
        cameraPageVisible = true
        cameraPreviewReady = false
        addHeading("CAMERA SYSTEM")
        val panel = makePanel()

        val status = TextView(this).apply {
            text = "CAMERA STANDBY"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.LTGRAY)
        }
        cameraStatusText = status
        panel.addView(status)

        // Use SurfaceView rather than TextureView for the legacy Camera preview.
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
                        try {
                            requestCameraPermissionAndStart()
                        } catch (t: Throwable) {
                            showCameraFailure(t)
                        }
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
                    } catch (t: Throwable) {
                        showCameraFailure(t)
                    }
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

        panel.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(300)).apply {
            setMargins(0, dp(12), 0, dp(12))
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeButton = Button(this).apply {
            text = if (nightVisionEnabled) "NIGHT VISION: ON" else "NORMAL VISION"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                nightVisionEnabled = !nightVisionEnabled
                applyCameraPreviewFilter()
                text = if (nightVisionEnabled) "NIGHT VISION: ON" else "NORMAL VISION"
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
        modeRow.addView(modeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        modeRow.addView(switchButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        panel.addView(modeRow)

        val poseToggle = Button(this).apply {
            text = "인체 모션 감지: ON"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                poseDetectionEnabled = !poseDetectionEnabled
                text = if (poseDetectionEnabled) "인체 모션 감지: ON" else "인체 모션 감지: OFF"
                if (!poseDetectionEnabled) poseOverlay?.clearPose()
            }
        }
        panel.addView(poseToggle, LinearLayout.LayoutParams(-1, dp(42)).apply {
            setMargins(0, dp(6), 0, dp(6))
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val flashButton = Button(this).apply {
            text = "플래시 OFF"
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
            text = "증거 사진 촬영"
            textSize = 10f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 125, 85))
            setOnClickListener { captureEvidencePhoto() }
        }
        actionRow.addView(flashButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        actionRow.addView(captureButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(actionRow)

        content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        })
        addPanelText(
            "MOTION TRACKING / NIGHT VISION",
            "인체 감지는 ML Kit가 사람의 주요 관절을 추정해 점선으로 연결합니다. 외곽 점선은 관절 위치를 이용한 근사 윤곽이며 실제 신체 분할 영상은 아닙니다. 녹색 야간 모드는 화면 효과로 실제 적외선 야간 투시 기능은 아닙니다."
        )
        applyCameraPreviewFilter()
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
                text = if (useFrontCamera) "FRONT CAMERA ACTIVE" else "REAR CAMERA ACTIVE"
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
            active.takePicture(null, null) { bytes, sourceCamera ->
                try {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val displayName = "PHANTOM_$stamp.jpg"
                    val savedLocation = savePhotoToGallery(bytes, displayName)
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
        } else if (deviation < anomalyResetThreshold) {
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
            savedAudioFiles.asReversed().take(10).forEach { path ->
                val play = Button(this).apply {
                    text = "▶ ${evidenceDisplayName(path)}"
                    textSize = 10f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setOnClickListener { openEvidence(path) }
                }
                eventsPanel.addView(play, LinearLayout.LayoutParams(-1, dp(38)))
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
                    spectrumView?.setSamples(spectrumSamples, sampleRate)

                    runOnUiThread {
                        if (audioRunning) {
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
            text = "자기장 변화량이 ${String.format(Locale.US, "%.1f", anomalyTriggerThreshold)} μT 이상이면 자동 저장됩니다."
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
                    "${String.format(Locale.US, "%.1f", anomalyTriggerThreshold)} μT 이상 변화하면 이곳에 기록됩니다."
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
        addHeading("RADAR SYSTEM")

        radarView = RadarView(this)

        content.addView(
            radarView,
            LinearLayout.LayoutParams(-1, dp(300)).apply {
                setMargins(0, dp(4), 0, dp(12))
            }
        )

        radarView.setCalibration(
            baselineCount.toFloat() / 30f
        )

        if (baselineCount >= 30) {
            val deviation = abs(currentValue - baseline)
            radarView.updateData(deviation, 0f, 0f)
        }

        addHeading("FIELD ANALYSIS")

        graphView = FieldGraphView(this)
        graphView.setReadings(readings.toList())

        content.addView(
            graphView,
            LinearLayout.LayoutParams(-1, dp(180)).apply {
                setMargins(0, 0, 0, dp(12))
            }
        )

        addStatisticsPanel()

        addPanelText(
            "SENSOR INFORMATION",
            "실제 휴대폰 자기장 센서 측정값입니다.\n\n" +
            "초기 30회 측정값을 기준으로 변화량을 계산합니다.\n\n" +
            "금속, 자석, 전자기기 및 주변 환경이 측정값에 영향을 줄 수 있습니다."
        )
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
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 1.2f
            setTextColor(warningColor)
            setPadding(dp(4), dp(14), dp(4), dp(12))
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
            background = roundedBackground(panelColor, 14)
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.rgb(48, 43, 52))
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
            String.format(
                Locale.US,
                "FIELD CHANGE: %.1f μT",
                deviation
            )
        } else {
            "CALIBRATING ${(calibrationProgress * 100).toInt()}%"
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

class FieldGraphView(context: android.content.Context) : View(context) {

    private val values = mutableListOf<Float>()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 18, 25)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 65, 70)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 150)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 11f
    }

    fun setReadings(data: List<Float>) {
        values.clear()
        values.addAll(data.takeLast(60))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRoundRect(
            0f, 0f, w, h, 16f, 16f, backgroundPaint
        )

        val left = 42f
        val right = w - 12f
        val top = 20f
        val bottom = h - 30f

        // 그래프 격자
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f

            canvas.drawLine(
                left, y, right, y, gridPaint
            )
        }

        canvas.drawLine(
            left, top, left, bottom, gridPaint
        )

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("60 SAMPLES", left, 14f, textPaint)

        if (values.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "WAITING FOR SENSOR DATA",
                w / 2f,
                h / 2f,
                textPaint
            )
            return
        }

        val minimum = (values.minOrNull() ?: 0f) - 2f
        val maximum = (values.maxOrNull() ?: 100f) + 2f
        val range = (maximum - minimum).coerceAtLeast(4f)

        textPaint.textAlign = Paint.Align.RIGHT

        canvas.drawText(
            String.format(Locale.US, "%.1f", maximum),
            left - 6f, top + 4f, textPaint
        )

        canvas.drawText(
            String.format(Locale.US, "%.1f", minimum),
            left - 6f, bottom, textPaint
        )

        val path = Path()

        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) {
                left
            } else {
                left + (right - left) *
                        index / (values.size - 1).toFloat()
            }

            val y = bottom -
                    ((value - minimum) / range) * (bottom - top)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)

        val lastValue = values.last()
        val lastX = if (values.size == 1) {
            left
        } else {
            right
        }

        val lastY = bottom -
                ((lastValue - minimum) / range) * (bottom - top)

        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 65, 75)
            style = Paint.Style.FILL
        }

        canvas.drawCircle(lastX, lastY, 5f, pointPaint)

        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("RECENT MAGNETIC FIELD", w / 2f, h - 7f, textPaint)
    }
}


class SoundWaveView(context: android.content.Context) : View(context) {
    private val samples = mutableListOf<Float>()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 12, 17)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 105, 80)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 150)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setWaveform(data: FloatArray) {
        samples.clear()
        val step = maxOf(1, data.size / 180)
        var index = 0
        while (index < data.size) {
            samples.add(data[index].coerceIn(-1f, 1f))
            index += step
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, backgroundPaint)

        for (i in 1..4) {
            val y = h * i / 5f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)

        if (samples.size < 2) return

        val path = Path()
        samples.forEachIndexed { index, value ->
            val x = index * w / (samples.size - 1).toFloat()
            val y = h / 2f - value * h * 0.42f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, wavePaint)
    }
}


class SpectrumView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var levels = FloatArray(8)
    fun setSamples(samples: ShortArray, sampleRate: Int) {
        val bands = intArrayOf(100, 200, 400, 800, 1600, 3200, 6400, 10000)
        levels = FloatArray(bands.size) { index ->
            val frequency = bands[index]
            var re = 0.0
            var im = 0.0
            val count = minOf(samples.size, 1024)
            for (n in 0 until count) {
                val angle = 2.0 * Math.PI * frequency * n / sampleRate
                re += samples[n] * cos(angle)
                im -= samples[n] * sin(angle)
            }
            (sqrt(re * re + im * im) / (count * 32768.0)).toFloat().coerceIn(0f, 1f)
        }
        postInvalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 15, 18))
        val gap = width * 0.025f
        val barWidth = (width - gap * 9) / 8f
        levels.forEachIndexed { i, level ->
            val left = gap + i * (barWidth + gap)
            val heightValue = max(dp(3f), (height - dp(24f)) * level * 5f)
            paint.color = Color.rgb(65, 220, 145)
            canvas.drawRoundRect(left, height - dp(18f) - heightValue, left + barWidth, height - dp(18f), dp(3f), dp(3f), paint)
            paint.color = Color.LTGRAY
            paint.textSize = dp(8f)
            canvas.drawText("${intArrayOf(100,200,400,800,1600,3200,6400,10000)[i]}", left, height - dp(4f), paint)
        }
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
}

class PoseOverlayView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 255, 150); style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 7f), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 175); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 175); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
    }
    @Volatile private var poseData: Pose? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var rotation = 0
    private var front = false
    fun setPose(pose: Pose, width: Int, height: Int, rotationDegrees: Int, isFront: Boolean) {
        poseData = pose; sourceWidth = width; sourceHeight = height
        rotation = rotationDegrees; front = isFront
        postInvalidate()
    }
    fun clearPose() { poseData = null; postInvalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = poseData ?: return
        if (pose.allPoseLandmarks.isEmpty() || sourceWidth == 0 || sourceHeight == 0) return
        val rotated = rotation == 90 || rotation == 270
        val imageW = if (rotated) sourceHeight.toFloat() else sourceWidth.toFloat()
        val imageH = if (rotated) sourceWidth.toFloat() else sourceHeight.toFloat()
        val scale = max(width / imageW, height / imageH)
        val dx = (width - imageW * scale) / 2f
        val dy = (height - imageH * scale) / 2f
        val save = canvas.save()
        if (front) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f)
        }
        fun point(type: Int): PointF? {
            val landmark = pose.getPoseLandmark(type) ?: return null
            return PointF(dx + landmark.position.x * scale, dy + landmark.position.y * scale)
        }
        val connections = arrayOf(
            intArrayOf(11,12), intArrayOf(11,13), intArrayOf(13,15),
            intArrayOf(12,14), intArrayOf(14,16), intArrayOf(11,23),
            intArrayOf(12,24), intArrayOf(23,24), intArrayOf(23,25),
            intArrayOf(25,27), intArrayOf(24,26), intArrayOf(26,28),
            intArrayOf(27,29), intArrayOf(29,31), intArrayOf(28,30), intArrayOf(30,32)
        )
        connections.forEach { pair ->
            val a = point(pair[0]); val b = point(pair[1])
            if (a != null && b != null) canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        // Dotted body contour approximated from shoulder, elbow, wrist, hip, knee and ankle landmarks.
        val contour = listOf(11,13,15,17,19,15,13,11,23,25,27,31,27,25,23,24,26,28,32,28,26,24,12,14,16,18,20,16,14,12)
            .mapNotNull { point(it) }
        if (contour.size > 2) {
            val path = Path().apply {
                moveTo(contour.first().x, contour.first().y)
                contour.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            canvas.drawPath(path, linePaint)
        }
        pose.allPoseLandmarks.forEach { landmark ->
            val p = point(landmark.landmarkType)
            if (p != null) canvas.drawCircle(p.x, p.y, 3.8f, dotPaint)
        }
        canvas.drawText("HUMAN TRACKING", dp(10f), dp(20f), textPaint)
        canvas.restoreToCount(save)
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
