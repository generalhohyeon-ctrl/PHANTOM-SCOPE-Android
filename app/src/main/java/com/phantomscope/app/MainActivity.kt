
package com.phantomscope.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sqrt
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

    private val spectrumSegments = mutableListOf<View>()

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

        root.addView(title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 55
            )
        )

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

                setOnClickListener {
                    showPage(index)
                }

                tabRow.addView(
                    this,
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

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        sensorManager =
            getSystemService(SENSOR_SERVICE) as SensorManager

        magneticSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

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

            bar.addView(
                segment,
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

        magneticSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
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

        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = sqrt(
                x * x + y * y + z * z
            )

            updateSpectrum(magnitude)
        }
    }

    private fun updateSpectrum(value: Float) {

        fieldValueText.text = String.format(
            Locale.US,
            "%.1f μT",
            value
        )

        val level = when {
            value < 50f -> 0
            value < 70f -> 1
            value < 100f -> 2
            else -> 3
        }

        val labels = listOf(
            "NORMAL",
            "ELEVATED",
            "HIGH",
            "CRITICAL"
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

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // 센서 정확도 변경 시 별도 처리 없음
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
                addPanelText(
                    "탐사 레이더 대기 중",
                    "실제 자기장 센서가 화면 상단에서 작동 중입니다.\n\n" +
                    "레이더 기능은 다음 버전에서 추가할 예정입니다."
                )
            }

            1 -> {
                addHeading("AUDIO DETECTION")
                addPanelText(
                    "사운드 감지 시스템",
                    "마이크 기능은 아직 연결되지 않았습니다."
                )
            }

            2 -> {
                addHeading("CAMERA SYSTEM")
                addPanelText(
                    "카메라 대기 중",
                    "카메라 기능은 아직 연결되지 않았습니다."
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

    private fun addPanelText(
        title: String,
        description: String
    ) {
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

        content.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
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
}
