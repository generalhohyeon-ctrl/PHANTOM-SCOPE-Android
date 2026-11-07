package com.phantomscope.app

import android.app.Activity
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
    val level: String
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
        loadInvestigationRecords()
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
        content.removeAllViews()

        tabButtons.forEachIndexed { i, button ->
            button.setBackgroundColor(
                if (i == index) warningColor else panelColor
            )

            button.setTextColor(Color.WHITE)
        }

        when (index) {
            0 -> showRadarPage()
            1 -> {
                addHeading("AUDIO DETECTION")
                addPanelText(
                    "SOUND SYSTEM OFFLINE",
                    "마이크 연결은 다음 개발 단계에서 추가됩니다."
                )
            }
            2 -> {
                addHeading("CAMERA SYSTEM")
                addPanelText(
                    "CAMERA STANDBY",
                    "카메라 연결은 다음 개발 단계에서 추가됩니다."
                )
            }
            3 -> showInvestigationLogPage()
        }
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

    private fun saveInvestigationRecords() {
              val array = JSONArray()
        investigationRecords.forEach { record ->
            array.put(JSONObject().apply {
                put("timestamp", record.timestamp)
                put("fieldValue", record.fieldValue.toDouble())
                put("deviation", record.deviation.toDouble())
                put("level", record.level)
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
                        item.optString("level", "UNKNOWN")
                    )
                )
            }
        } catch (_: Exception) {
            investigationRecords.clear()
        }
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
                text = "FIELD  ${formatField(record.fieldValue)}\n" +
                    "DEVIATION  +${String.format(Locale.US, "%.1f", record.deviation)} μT"
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(8))
            }
            panel.addView(detail)

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
