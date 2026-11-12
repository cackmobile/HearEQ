package com.cackmobile.heareq

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var settings: EqSettings

    private lateinit var statusText: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var onlyHaSwitch: Switch
    private lateinit var presetSpinner: Spinner
    private lateinit var deleteButton: Button
    private lateinit var preampBar: SeekBar
    private lateinit var preampValue: TextView
    private val bandBars = ArrayList<SeekBar>()
    private val bandValues = ArrayList<TextView>()

    private var presetNames: List<String> = emptyList()
    /** Spinner position we set ourselves, so the spinner's own callbacks don't re-apply it. */
    private var spinnerPos = -1
    /** Stops programmatic UI updates from being treated as user edits. */
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = EqSettings(this)
        setContentView(buildUi())
        loadFromSettings()
        askForNotifications()
        handleIntent(intent)
        if (settings.enabled) EqService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        EqService.listener = { showStatus(it) }
        showStatus(EqService.current)
    }

    override fun onPause() {
        EqService.listener = null
        super.onPause()
    }

    /** A music app opened us from its "Equaliser" menu: hook into that app's audio. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (settings.enabled && session > 0) EqService.start(this, session)
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Equaliser for music streamed to your hearing aids"
            textSize = 14f
            alpha = 0.7f
        })

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.status_bg)
        }
        col.addView(statusText, lp(top = 16))

        enableSwitch = Switch(this).apply {
            text = "Equaliser on"
            textSize = 17f
            setOnCheckedChangeListener { _, on ->
                if (suppress) return@setOnCheckedChangeListener
                settings.enabled = on
                if (on) EqService.start(this@MainActivity) else EqService.stop(this@MainActivity)
            }
        }
        col.addView(enableSwitch, lp(top = 16))

        onlyHaSwitch = Switch(this).apply {
            text = "Only when hearing aids are connected"
            textSize = 15f
            setOnCheckedChangeListener { _, on -> if (!suppress) settings.onlyHearingAids = on }
        }
        col.addView(onlyHaSwitch, lp(top = 8))

        col.addView(sectionTitle("Preset"), lp(top = 24))
        presetSpinner = Spinner(this)
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppress || pos == spinnerPos) return
                spinnerPos = pos
                applyPreset(presetNames[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(presetSpinner, lp(top = 4))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Save as…"
            setOnClickListener { promptSave() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        deleteButton = Button(this).apply {
            text = "Delete"
            setOnClickListener { confirmDelete() }
        }
        buttons.addView(deleteButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener { selectPreset("Flat"); applyPreset("Flat") }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(buttons, lp(top = 4))

        col.addView(sectionTitle("Pre-amp"), lp(top = 20))
        col.addView(TextView(this).apply {
            text = "Keep this below zero when you boost bands, to leave headroom and avoid distortion."
            textSize = 13f
            alpha = 0.7f
        })
        val (preRow, preBar, preVal) = sliderRow("Gain", Bands.PREAMP_MIN_DB, Bands.PREAMP_MAX_DB) { db ->
            settings.preampDb = db
        }
        preampBar = preBar
        preampValue = preVal
        col.addView(preRow, lp(top = 4))

        col.addView(sectionTitle("Bands"), lp(top = 20))
        for (i in 0 until Bands.COUNT) {
            val (row, bar, value) = sliderRow("${Bands.LABELS[i]} Hz", Bands.MIN_DB, Bands.MAX_DB) { db ->
                val g = settings.gainsDb
                g[i] = db
                settings.gainsDb = g
                settings.presetName = CUSTOM
                selectPreset(CUSTOM)
            }
            bandBars += bar
            bandValues += value
            col.addView(row, lp(top = 2))
        }

        col.addView(TextView(this).apply {
            text = "Tips\n" +
                "• Low numbers (31–250 Hz) are bass, 500 Hz–2k is voices, 4k–16k is treble and detail.\n" +
                "• Your hearing aids already boost the frequencies you need. Small changes (±3 dB) go a long way.\n" +
                "• If a music app has its own Equaliser setting, choose HearEQ there so it connects to that app.\n" +
                "• The Signia app's sound balance still works on top of this."
            textSize = 13f
            alpha = 0.75f
            setLineSpacing(0f, 1.2f)
        }, lp(top = 24))

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        return scroll
    }

    /** A labelled slider in 0.5 dB steps. */
    private fun sliderRow(
        label: String, minDb: Float, maxDb: Float, onUserChange: (Float) -> Unit,
    ): Triple<View, SeekBar, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))

        val value = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.END
        }
        val bar = SeekBar(this).apply {
            max = ((maxDb - minDb) / STEP).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    val db = minDb + progress * STEP
                    value.text = formatDb(db)
                    if (fromUser && !suppress) onUserChange(db)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(value, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
        return Triple(row, bar, value)
    }

    private fun loadFromSettings() {
        suppress = true
        enableSwitch.isChecked = settings.enabled
        onlyHaSwitch.isChecked = settings.onlyHearingAids
        setBar(preampBar, preampValue, settings.preampDb, Bands.PREAMP_MIN_DB)
        showGains(settings.gainsDb)
        reloadPresets(settings.presetName)
        suppress = false
    }

    private fun reloadPresets(select: String) {
        // "Custom" is the unsaved state you get after moving a slider.
        presetNames = listOf(CUSTOM) + settings.allPresets().keys
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presetNames,
        )
        selectPreset(select)
    }

    private fun selectPreset(name: String) {
        val idx = presetNames.indexOf(name).coerceAtLeast(0)
        spinnerPos = idx
        presetSpinner.setSelection(idx, false)
        deleteButton.isEnabled = settings.isCustom(name)
    }

    private fun applyPreset(name: String) {
        val gains = settings.allPresets()[name] ?: return
        settings.gainsDb = gains.copyOf()
        settings.presetName = name
        suppress = true
        showGains(gains)
        suppress = false
        deleteButton.isEnabled = settings.isCustom(name)
    }

    private fun showGains(g: FloatArray) {
        for (i in 0 until Bands.COUNT) setBar(bandBars[i], bandValues[i], g[i], Bands.MIN_DB)
    }

    private fun setBar(bar: SeekBar, value: TextView, db: Float, minDb: Float) {
        bar.progress = ((db - minDb) / STEP).roundToInt()
        value.text = formatDb(db)
    }

    private fun promptSave() {
        val input = EditText(this).apply {
            hint = "e.g. Car, Gym, Jazz"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Save preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Unit
                    Presets.BUILT_IN.containsKey(name) || name == CUSTOM -> toastish("That name is taken by a built-in preset.")
                    else -> {
                        settings.saveCustom(name, settings.gainsDb)
                        settings.presetName = name
                        reloadPresets(name)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        val name = settings.presetName
        if (!settings.isCustom(name)) return
        AlertDialog.Builder(this)
            .setMessage("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                settings.deleteCustom(name)
                settings.presetName = CUSTOM
                reloadPresets(CUSTOM)
                deleteButton.isEnabled = false
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatus(s: EqService.Status) {
        val headline = when {
            !s.running -> "Equaliser is off."
            s.active && s.hearingAid != null -> "On: shaping music to ${s.hearingAid}."
            s.active -> "On: shaping music on all outputs."
            else -> "Paused: waiting for your hearing aids to connect."
        }
        val coverage = when {
            !s.running -> ""
            s.globalAttached -> "\nWorking across all apps."
            s.appSessions > 0 -> "\nConnected to ${s.appSessions} music app session(s)."
            else -> "\nThis phone doesn't allow all-apps mode. Start playing music, or pick HearEQ in your music app's Equaliser setting."
        }
        statusText.text = headline + coverage
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toastish(msg: String) =
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun formatDb(db: Float) = (if (db > 0) "+" else "") + String.format("%.1f dB", db)

    companion object {
        private const val STEP = 0.5f
        private const val CUSTOM = "Custom"
    }
}
