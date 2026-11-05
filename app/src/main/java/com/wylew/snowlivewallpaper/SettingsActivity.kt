package com.wylew.snowlivewallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("snow_prefs", MODE_PRIVATE) }
    private lateinit var previewView: WallpaperPreviewView

    // OpenDocument (not GetContent) so the URI supports a persistable read grant; the wallpaper
    // service needs to re-open it after process restarts. GetContent URIs often throw here.
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Provider doesn't offer persistable grants; still usable for this session.
            }
            prefs.edit().putString("background_uri", it.toString()).apply()
        }
    }

    // Samsung foldables only: One UI picks the target screen from the fold state, which other
    // vendors' foldables may not do. windowHasFold tracks whether the hinge runs through this
    // window; on a Galaxy Fold that's only true when unfolded (no FoldingFeature on the cover).
    private val isFoldable by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
    }
    private var windowHasFold = false
    private val windowInfoTracker by lazy {
        WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this))
    }
    private val layoutInfoListener = Consumer<WindowLayoutInfo> { info ->
        windowHasFold = info.displayFeatures.any { it is FoldingFeature }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        previewView = findViewById(R.id.wallpaper_preview)

        setupSliders()
        setupButtons()
        setupCheckbox()
        setupDesignChips()
        updateSettleSettingsState(prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT))
    }

    // Suppresses pref writes while chip states are being set from code.
    private var syncingDesignChips = false

    private fun setupDesignChips() {
        val group = findViewById<ChipGroup>(R.id.chip_group_designs) ?: return
        val names = resources.getStringArray(R.array.flake_design_names)
        val density = resources.displayMetrics.density
        val accent = ContextCompat.getColor(this, R.color.snow_accent)
        val textColor = ContextCompat.getColor(this, R.color.snow_text_dark)
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val background = ColorStateList(
            checkedStates,
            intArrayOf(ContextCompat.getColor(this, R.color.snow_blue_light), Color.TRANSPARENT)
        )
        val stroke = ColorStateList(
            checkedStates,
            intArrayOf(accent, ContextCompat.getColor(this, R.color.snow_blue_light))
        )

        group.removeAllViews()
        for (design in 0 until Snowflake.DESIGN_COUNT) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = design
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        this@SettingsActivity, null, 0,
                        com.google.android.material.R.style.Widget_Material3_Chip_Filter
                    )
                )
                text = names.getOrElse(design) { "Style ${design + 1}" }
                setTextColor(textColor)
                isCheckable = true
                isCheckedIconVisible = false // the highlighted background shows selection
                chipIcon = BitmapDrawable(resources, Snowflake.renderDesign(design, 10f * density, accent))
                isChipIconVisible = true
                chipIconSize = 24f * density
                chipBackgroundColor = background
                chipStrokeColor = stroke
                chipStrokeWidth = 1f * density
            }
            group.addView(chip)
        }

        group.setOnCheckedStateChangeListener { g, checkedIds ->
            if (syncingDesignChips) return@setOnCheckedStateChangeListener
            val selected = checkedIds.mapNotNull { g.findViewById<Chip>(it)?.tag?.toString() }.toSet()
            if (selected.isNotEmpty()) {
                prefs.edit().putStringSet(Snowflake.PREF_DESIGNS, selected).apply()
            }
        }
        syncDesignChips()
    }

    private fun syncDesignChips() {
        val group = findViewById<ChipGroup>(R.id.chip_group_designs) ?: return
        val saved = prefs.getStringSet(Snowflake.PREF_DESIGNS, Snowflake.ALL_DESIGNS) ?: Snowflake.ALL_DESIGNS
        syncingDesignChips = true
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag.toString() in saved
        }
        syncingDesignChips = false
    }

    private fun setupSliders() {
        findViewById<Slider>(R.id.slider_flake_count)?.apply {
            valueFrom = WallpaperConfig.FLAKE_COUNT_MIN.toFloat()
            valueTo = WallpaperConfig.FLAKE_COUNT_MAX.toFloat()
            updateValue(this, prefs.getInt("flake_count", WallpaperConfig.FLAKE_COUNT_DEFAULT).toFloat())
            addOnChangeListener { _, value, _ ->
                prefs.edit().putInt("flake_count", value.toInt()).apply()
            }
        }

        findViewById<Slider>(R.id.slider_wind_strength)?.apply {
            valueFrom = WallpaperConfig.WIND_STRENGTH_MIN
            valueTo = WallpaperConfig.WIND_STRENGTH_MAX
            val step = 0.05f
            val savedValue = prefs.getFloat("wind_strength", WallpaperConfig.WIND_STRENGTH_DEFAULT)
            updateValue(this, (savedValue / step).roundToInt() * step)

            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("wind_strength", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_turbulence_speed)?.apply {
            valueFrom = WallpaperConfig.TURBULENCE_SPEED_MIN
            valueTo = WallpaperConfig.TURBULENCE_SPEED_MAX
            updateValue(this, prefs.getFloat("turbulence_speed", WallpaperConfig.TURBULENCE_SPEED_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("turbulence_speed", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_turbulence_radius)?.apply {
            valueFrom = WallpaperConfig.TURBULENCE_RADIUS_MIN
            valueTo = WallpaperConfig.TURBULENCE_RADIUS_MAX
            updateValue(this, prefs.getFloat("turbulence_radius", WallpaperConfig.TURBULENCE_RADIUS_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("turbulence_radius", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_flake_size)?.apply {
            valueFrom = WallpaperConfig.FLAKE_SIZE_MIN
            valueTo = WallpaperConfig.FLAKE_SIZE_MAX
            updateValue(this, prefs.getFloat("flake_size", WallpaperConfig.FLAKE_SIZE_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("flake_size", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_fall_speed)?.apply {
            valueFrom = WallpaperConfig.FALL_SPEED_MIN
            valueTo = WallpaperConfig.FALL_SPEED_MAX
            updateValue(this, prefs.getFloat("fall_speed", WallpaperConfig.FALL_SPEED_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("fall_speed", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_rotation_speed)?.apply {
            valueFrom = WallpaperConfig.ROTATION_SPEED_MIN
            valueTo = WallpaperConfig.ROTATION_SPEED_MAX
            updateValue(this, prefs.getFloat("rotation_speed", WallpaperConfig.ROTATION_SPEED_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("rotation_speed", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_flake_color)?.apply {
            valueFrom = 0f
            valueTo = 100f
            val color = prefs.getInt("flake_color", WallpaperConfig.COLOR_DEFAULT)
            updateValue(this, calculateColorProgress(color).toFloat())
            addOnChangeListener { _, value, _ ->
                prefs.edit().putInt("flake_color", interpolateColor(value / 100f)).apply()
            }
        }

        findViewById<Slider>(R.id.slider_transparency)?.apply {
            valueFrom = WallpaperConfig.ALPHA_MIN.toFloat()
            valueTo = WallpaperConfig.ALPHA_MAX.toFloat()
            updateValue(this, prefs.getInt("flake_alpha", WallpaperConfig.ALPHA_DEFAULT).toFloat())
            addOnChangeListener { _, value, _ ->
                prefs.edit().putInt("flake_alpha", value.toInt()).apply()
            }
        }

        findViewById<Slider>(R.id.slider_settle_probability)?.apply {
            valueFrom = WallpaperConfig.SETTLE_PROBABILITY_MIN
            valueTo = WallpaperConfig.SETTLE_PROBABILITY_MAX
            updateValue(this, prefs.getFloat("settle_probability", WallpaperConfig.SETTLE_PROBABILITY_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("settle_probability", value).apply()
            }
        }

        findViewById<Slider>(R.id.slider_settle_height)?.apply {
            valueFrom = WallpaperConfig.PILE_HEIGHT_MIN
            valueTo = WallpaperConfig.PILE_HEIGHT_MAX
            updateValue(this, prefs.getFloat("max_pile_height", WallpaperConfig.PILE_HEIGHT_DEFAULT))
            addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat("max_pile_height", value).apply()
            }
        }
    }

    private fun updateValue(slider: Slider, value: Float) {
        // setupSliders() runs again on "reset to defaults"; drop old listeners so they don't stack up.
        slider.clearOnChangeListeners()
        slider.value = value.coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btn_select_background)?.setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }

        findViewById<View>(R.id.btn_set_wallpaper)?.setOnClickListener {
            if (isFoldable && !windowHasFold) {
                // One UI applies a live wallpaper set from the cover screen to the cover screen
                // only; set from the unfolded main screen it applies to both. Apps can't choose
                // the target display themselves, so warn before opening the system picker.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.fold_warning_title)
                    .setMessage(R.string.fold_warning_message)
                    .setPositiveButton(R.string.fold_warning_continue) { _, _ -> openLiveWallpaperPicker() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                openLiveWallpaperPicker()
            }
        }

        findViewById<View>(R.id.btn_reset_defaults)?.setOnClickListener {
            resetToDefaults()
        }
    }

    private fun openLiveWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, SnowWallpaperService::class.java))
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        if (isFoldable) windowInfoTracker.addWindowLayoutInfoListener(this, ContextCompat.getMainExecutor(this), layoutInfoListener)
    }

    override fun onStop() {
        if (isFoldable) windowInfoTracker.removeWindowLayoutInfoListener(layoutInfoListener)
        super.onStop()
    }

    private fun resetToDefaults() {
        prefs.edit().apply {
            putInt("flake_count", WallpaperConfig.FLAKE_COUNT_DEFAULT)
            putFloat("wind_strength", WallpaperConfig.WIND_STRENGTH_DEFAULT)
            putFloat("turbulence_speed", WallpaperConfig.TURBULENCE_SPEED_DEFAULT)
            putFloat("turbulence_radius", WallpaperConfig.TURBULENCE_RADIUS_DEFAULT)
            putFloat("flake_size", WallpaperConfig.FLAKE_SIZE_DEFAULT)
            putFloat("fall_speed", WallpaperConfig.FALL_SPEED_DEFAULT)
            putFloat("rotation_speed", WallpaperConfig.ROTATION_SPEED_DEFAULT)
            putInt("flake_color", WallpaperConfig.COLOR_DEFAULT)
            putInt("flake_alpha", WallpaperConfig.ALPHA_DEFAULT)
            putBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
            putFloat("settle_probability", WallpaperConfig.SETTLE_PROBABILITY_DEFAULT)
            putFloat("max_pile_height", WallpaperConfig.PILE_HEIGHT_DEFAULT)
            putStringSet(Snowflake.PREF_DESIGNS, Snowflake.ALL_DESIGNS)
            apply()
        }
        
        // Refresh UI
        setupSliders()
        setupCheckbox()
        syncDesignChips()
        updateSettleSettingsState(WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
    }

    private fun setupCheckbox() {
        findViewById<MaterialCheckBox>(R.id.check_collect_at_bottom)?.apply {
            isChecked = prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("collect_at_bottom", isChecked).apply()
                updateSettleSettingsState(isChecked)
            }
        }
    }

    private fun updateSettleSettingsState(enabled: Boolean) {
        val alphaValue = if (enabled) 1.0f else 0.4f
        
        findViewById<View>(R.id.layout_settle_prob)?.apply {
            this.isEnabled = enabled
            this.alpha = alphaValue
        }
        findViewById<View>(R.id.layout_settle_height)?.apply {
            this.isEnabled = enabled
            this.alpha = alphaValue
        }
        findViewById<Slider>(R.id.slider_settle_probability)?.isEnabled = enabled
        findViewById<Slider>(R.id.slider_settle_height)?.isEnabled = enabled
    }

    private fun interpolateColor(fraction: Float): Int {
        val start = WallpaperConfig.COLOR_START
        val end = WallpaperConfig.COLOR_END
        val r = (Color.red(start) + fraction * (Color.red(end) - Color.red(start))).toInt()
        val g = (Color.green(start) + fraction * (Color.green(end) - Color.green(start))).toInt()
        val b = (Color.blue(start) + fraction * (Color.blue(end) - Color.blue(start))).toInt()
        return Color.rgb(r, g, b)
    }

    private fun calculateColorProgress(color: Int): Int {
        val startG = Color.green(WallpaperConfig.COLOR_START).toFloat()
        val endG = Color.green(WallpaperConfig.COLOR_END).toFloat()
        val currentG = Color.green(color).toFloat()
        if (startG == endG) return 0
        return (((currentG - startG) / (endG - startG)) * 100).toInt().coerceIn(0, 100)
    }

    override fun onResume() {
        super.onResume()
        previewView.start()
    }

    override fun onPause() {
        super.onPause()
        previewView.stop()
    }
}
