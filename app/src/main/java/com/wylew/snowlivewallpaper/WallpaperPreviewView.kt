package com.wylew.snowlivewallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import androidx.core.net.toUri
import android.util.AttributeSet
import android.view.View

class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    private val flakes = mutableListOf<Snowflake>()
    private var backgroundBitmap: Bitmap? = null
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("snow_prefs", Context.MODE_PRIVATE)
    }
    
    private var lastTime = System.currentTimeMillis()
    private var running = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        loadBackground()
        loadSettings()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        loadSettings()
    }

    private fun loadBackground() {
        val bgUriStr = prefs.getString("background_uri", null)
        backgroundBitmap = bgUriStr?.let {
            try {
                context.contentResolver.openInputStream(it.toUri())?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun loadSettings() {
        Snowflake.loadEnabledDesigns(prefs)
        val count = prefs.getInt("flake_count", WallpaperConfig.FLAKE_COUNT_DEFAULT)
        val wind = prefs.getFloat("wind_strength", WallpaperConfig.WIND_STRENGTH_DEFAULT)
        val size = prefs.getFloat("flake_size", WallpaperConfig.FLAKE_SIZE_DEFAULT)
        val speed = prefs.getFloat("fall_speed", WallpaperConfig.FALL_SPEED_DEFAULT)
        val rotSpeed = prefs.getFloat("rotation_speed", WallpaperConfig.ROTATION_SPEED_DEFAULT)
        val color = prefs.getInt("flake_color", WallpaperConfig.COLOR_DEFAULT)
        val alpha = prefs.getInt("flake_alpha", WallpaperConfig.ALPHA_DEFAULT)
        val collect = prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
        
        val turbSpeed = prefs.getFloat("turbulence_speed", WallpaperConfig.TURBULENCE_SPEED_DEFAULT)
        val turbRadius = prefs.getFloat("turbulence_radius", WallpaperConfig.TURBULENCE_RADIUS_DEFAULT)
        
        if (width > 0 && height > 0) {
            if (flakes.size != count) {
                flakes.clear()
                repeat(count) {
                    flakes.add(Snowflake(
                        screenWidth = width,
                        screenHeight = height,
                        size = size,
                        windStrength = wind,
                        speed = speed,
                        color = color,
                        alpha = alpha,
                        collectAtBottom = collect,
                        rotationSpeed = rotSpeed,
                        turbulenceSpeed = turbSpeed,
                        turbulenceRadius = turbRadius
                    ))
                }
            } else {
                flakes.forEach { 
                    it.updateSettings(width, height, size, wind, speed, color, alpha, collect, rotSpeed, turbSpeed, turbRadius)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val currentTime = System.currentTimeMillis()
        val deltaTime = ((currentTime - lastTime) / 1000f).coerceAtMost(0.1f)
        lastTime = currentTime

        drawBackground(canvas)

        flakes.forEach {
            it.update(deltaTime)
            if (it.y > height + it.extent) {
                it.reset()
            }
            it.draw(canvas)
        }

        if (running) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawBackground(canvas: Canvas) {
        backgroundBitmap?.let { bitmap ->
            val canvasWidth = width.toFloat()
            val canvasHeight = height.toFloat()
            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()

            val scale: Float
            var dx = 0f
            var dy = 0f

            if (bitmapWidth * canvasHeight > canvasWidth * bitmapHeight) {
                scale = canvasHeight / bitmapHeight
                dx = (canvasWidth - bitmapWidth * scale) * 0.5f
            } else {
                scale = canvasWidth / bitmapWidth
                dy = (canvasHeight - bitmapHeight * scale) * 0.5f
            }

            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            canvas.drawBitmap(bitmap, matrix, null)
        } ?: canvas.drawColor(Color.BLACK)
    }

    fun start() {
        running = true
        lastTime = System.currentTimeMillis()
        invalidate()
    }

    fun stop() {
        running = false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "background_uri") loadBackground()
        loadSettings()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }
}
