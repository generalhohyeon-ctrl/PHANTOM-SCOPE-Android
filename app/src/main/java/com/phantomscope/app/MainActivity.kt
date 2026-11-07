
package com.phantomscope.app

import android.app.Activity
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
import kotlin.math.*
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {

    private val backgroundColor = Color.rgb(10, 14, 20)
    private val panelColor = Color.rgb(20, 27, 36)
    private val accentColor = Color.rgb(70, 220, 150)

    private lateinit var content: LinearLayout
    private lateinit var tabButtons: List<Button>
    private lateinit var sensorManager: SensorManager
    private var magneticSensor: Sensor? = null

    private lateinit var fieldValueText: TextView
    private lateinit var sensorStatusText: TextView
    private lateinit var radarView: RadarView

    private val spectrumSegments = mutableListOf<View>()

    private var baselineTotal = 0f
    private var baselineCount = 0
    private var baseline = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(20, 20, 20, 12)
        }

        val title = TextView(this).apply {
            text = "PHANTOM SCOPE"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        root.addView(title, LinearLayout.LayoutParams(
            -1, 55
        ))

        val subtitle = TextView(this).apply {
            text = "PARANORMAL INVESTIGATION SYSTEM"
            textSize = 10f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
        }

        root.addView(subtitle)
        root.addView(createSpectrum())

        val tabNames = listOf("레이더", "사운드", "카메라", "기록")

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 15)
        }

        tabButtons = tabNames.mapIndexed { index, name ->
            Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)

                setOnClickListener { showPage(index) }

                tabRow.addView(this,
                    LinearLayout.LayoutParams(0, 48, 1f).apply {
                        setMargins(3, 0, 3, 0)
                    }
                )
            }
        }

        root.addView(tabRow)

        val scrollView = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(content)

        root.addView(scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)

        sensorManager = getSystemService(SENSOR_SERVICE)
                as SensorManager

        magneticSensor = sensorManager.getDefaultSensor(
            Sensor.TYPE_MAGNETIC_FIELD
        )

        if (magneticSensor == null) {
            sensorStatusText.text = "MAGNETIC SENSOR NOT SUPPORTED"
            fieldValueText.text = "-- μT"
        } else {
            sensorStatusText.text = "SENSOR READY - WAITING"
        }

        showPage(0)
    }

    private fun createSpectrum(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 15, 15, 15)
            background = roundedBackground(panelColor, 14)
        }

        val label = TextView(this).apply {
            text = "REAL MAGNETIC FIELD SPECTRUM"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        panel.addView(label)

        fieldValueText = TextView(this).apply {
            text = "-- μT"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(0, 12, 0, 5)
        }

        panel.addView(fieldValueText)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 10)
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

            bar.addView(segment,
                LinearLayout.LayoutParams(0, 15, 1f).apply {
                    setMargins(3, 0, 3, 0)
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

        updateSpectrum(magnitude)

        if (baselineCount < 30) {
            baselineTotal += magnitude
            baselineCount++

            if (baselineCount == 30) {
                baseline = baselineTotal / baselineCount
            }

            radarView.setCalibration(
                baselineCount.toFloat() / 30f
            )
        } else {
            val deviation = abs(magnitude - baseline)
            radarView.updateData(deviation, x, y)
        }
    }
override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // 센서 정확도 변경 시 별도 처리 없음
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

        sensorStatusText.text =
            "LIVE SENSOR  |  ${labels[level]}"

        sensorStatusText.setTextColor(colors[level])
    }

    private fun showPage(index: Int) {
        content.removeAllViews()

        tabButtons.forEachIndexed { i, button ->
            button.setBackgroundColor(
                if (i == index) accentColor else panelColor
            )

            button.setTextColor(
                if (i == index) Color.BLACK else Color.WHITE
            )
        }

        when (index) {
            0 -> {
                addHeading("RADAR SYSTEM")

                radarView = RadarView(this)

                content.addView(radarView,
                    LinearLayout.LayoutParams(
                        -1, dp(310)
                    ).apply {
                        setMargins(0, 5, 0, 12)
                    }
                )

                addPanelText(
                    "MAGNETIC FIELD ANALYSIS",
                    "자기장 기준값: ${
                        if (baselineCount >= 30)
                            String.format(Locale.US, "%.1f μT", baseline)
                        else "측정 중"
                    }\n\n" +
                    "레이더는 기준 자기장과 현재 측정값의 차이를 표시합니다.\n\n" +
                    "주변 금속, 전자기기, 자석 등에 의해 수치가 변할 수 있습니다."
                )
            }

            1 -> {
                addHeading("AUDIO DETECTION")
                addPanelText(
                    "사운드 감지 시스템",
                    "마이크 기능은 다음 버전에서 연결할 예정입니다."
                )
            }

            2 -> {
                addHeading("CAMERA SYSTEM")
                addPanelText(
                    "카메라 대기 중",
                    "카메라 기능은 다음 버전에서 연결할 예정입니다."
                )
            }

            3 -> {
                addHeading("INVESTIGATION LOG")
                addPanelText(
                    "탐사 기록 없음",
                    "아직 저장된 탐사 기록이 없습니다."
                )
            }
        }
    }

    private fun addHeading(text: String) {
        val heading = TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(5, 15, 5, 15)
        }

        content.addView(heading)
    }

    private fun addPanelText(title: String, description: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 25, 20, 25)
            background = roundedBackground(panelColor, 14)
        }

        val heading = TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val body = TextView(this).apply {
            text = description
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setLineSpacing(8f, 1f)
            setPadding(0, 15, 0, 0)
        }

        panel.addView(heading)
        panel.addView(body)

        content.addView(panel,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 5, 0, 15)
            }
        )
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

class RadarView(context: android.content.Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 120, 90)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 150)
        strokeWidth = 3f
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 70, 70)
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

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.rgb(8, 18, 20))

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.39f

        val backgroundPaint = Paint().apply {
            color = Color.rgb(10, 35, 30)
            style = Paint.Style.FILL
        }

        canvas.drawCircle(cx, cy, radius, backgroundPaint)

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

        val sweepRadians = Math.toRadians(
            sweepAngle.toDouble()
        )

        val endX = cx + cos(sweepRadians).toFloat() * radius
        val endY = cy + sin(sweepRadians).toFloat() * radius

        val sweepGradient = LinearGradient(
            cx, cy, endX, endY,
            Color.rgb(70, 220, 150),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        sweepPaint.shader = sweepGradient

        canvas.drawLine(cx, cy, endX, endY, sweepPaint)

        sweepPaint.shader = null

        if (calibrated && deviation > 1.5f) {
            val blipRadius = radius *
                    (0.25f + (deviation / 30f).coerceIn(0f, 0.65f))

            val angle = Math.toRadians(direction.toDouble())

            val bx = cx + cos(angle).toFloat() * blipRadius
            val by = cy - sin(angle).toFloat() * blipRadius

            blipPaint.color = when {
                deviation < 5f -> Color.YELLOW
                deviation < 10f -> Color.rgb(255, 150, 35)
                else -> Color.RED
            }

            canvas.drawCircle(bx, by, 8f, blipPaint)
            canvas.drawCircle(bx, by, 14f, gridPaint)
        }

        textPaint.textAlign = Paint.Align.CENTER

        if (!calibrated) {
            canvas.drawText(
                "CALIBRATING ${(calibrationProgress * 100).toInt()}%",
                cx, cy + radius + 28f, textPaint
            )
        } else {
            canvas.drawText(
                String.format(Locale.US, "CHANGE %.1f μT", deviation),
                cx, cy + radius + 28f, textPaint
            )
        }

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("N", cx - 5f, cy - radius - 12f, textPaint)
        canvas.drawText("E", cx + radius + 10f, cy + 5f, textPaint)
        canvas.drawText("S", cx - 5f, cy + radius + 22f, textPaint)
        canvas.drawText("W", cx - radius - 20f, cy + 5f, textPaint)

        sweepAngle = (sweepAngle + 2f) % 360f

        postInvalidateDelayed(40)
    }
}
