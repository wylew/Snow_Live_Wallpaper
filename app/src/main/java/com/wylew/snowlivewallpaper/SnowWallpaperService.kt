package com.wylew.snowlivewallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlin.math.sqrt
import kotlin.random.Random

class SnowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return SnowEngine()
    }

    inner class SnowEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
        private val tag = "SnowWallpaper"
        
        private var renderThread: RenderThread? = null
        private val activeFlakes = mutableListOf<Snowflake>()
        private val groundedFlakes = mutableListOf<Snowflake>()
        // Flakes blown off the pile by a shake. Kept separate so they never count toward the
        // falling-flake limit (which used to starve the top of new flakes after a shake).
        private val blowingFlakes = mutableListOf<Snowflake>()
        
        private var backgroundBitmap: Bitmap? = null
        private var scaledBackground: Bitmap? = null
        private var groundedLayerBitmap: Bitmap? = null
        private var groundedLayerCanvas: Canvas? = null
        
        private val prefs: SharedPreferences by lazy {
            getSharedPreferences("snow_prefs", MODE_PRIVATE)
        }

        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }

        init {
            prefs.registerOnSharedPreferenceChangeListener(this)
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sensorManager = sm
            accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            loadSettings()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                startRenderThread()
                accelerometer?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } else {
                stopRenderThread()
                sensorManager?.unregisterListener(this)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            loadBackground()
            loadSettings()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopRenderThread()
            
            scaledBackground?.recycle()
            scaledBackground = null
            groundedLayerBitmap?.recycle()
            groundedLayerBitmap = null
            groundedLayerCanvas = null
        }

        private fun startRenderThread() {
            if (renderThread == null) {
                renderThread = RenderThread().apply {
                    start()
                }
            }
        }

        private fun stopRenderThread() {
            renderThread?.running = false
            try {
                renderThread?.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            renderThread = null
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            // Only re-decode the background image when it actually changed, not on every slider tick.
            if (key == "background_uri") loadBackground()
            loadSettings()
        }

        private fun loadBackground() {
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            val bgUriStr = prefs.getString("background_uri", null)
            if (bgUriStr == null || width <= 0 || height <= 0) {
                backgroundBitmap = null
                scaledBackground = null
                return
            }
            try {
                val uri = bgUriStr.toUri()
                // Read dimensions first so large photos are subsampled instead of decoded at full size.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { stream ->
                    backgroundBitmap = BitmapFactory.decodeStream(stream, null, opts)
                    updateScaledBackground()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load background", e)
                backgroundBitmap = null
                scaledBackground = null
            }
        }

        private fun loadSettings() {
            Snowflake.loadEnabledDesigns(prefs)
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            
            if (width > 0 && height > 0) {
                val targetCount = prefs.getInt("flake_count", WallpaperConfig.FLAKE_COUNT_DEFAULT)
                val wind = prefs.getFloat("wind_strength", WallpaperConfig.WIND_STRENGTH_DEFAULT)
                val size = prefs.getFloat("flake_size", WallpaperConfig.FLAKE_SIZE_DEFAULT)
                val speed = prefs.getFloat("fall_speed", WallpaperConfig.FALL_SPEED_DEFAULT)
                val rotSpeed = prefs.getFloat("rotation_speed", WallpaperConfig.ROTATION_SPEED_DEFAULT)
                val color = prefs.getInt("flake_color", WallpaperConfig.COLOR_DEFAULT)
                val alpha = prefs.getInt("flake_alpha", WallpaperConfig.ALPHA_DEFAULT)
                val collect = prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
                val pileHeight = prefs.getFloat("max_pile_height", WallpaperConfig.PILE_HEIGHT_DEFAULT)
                
                // New turbulence settings
                val turbSpeed = prefs.getFloat("turbulence_speed", WallpaperConfig.TURBULENCE_SPEED_DEFAULT)
                val turbRadius = prefs.getFloat("turbulence_radius", WallpaperConfig.TURBULENCE_RADIUS_DEFAULT)

                val oldWidth = activeFlakes.firstOrNull()?.screenWidth ?: 0
                val oldHeight = activeFlakes.firstOrNull()?.screenHeight ?: 0
                val isRotation = (width != oldWidth || height != oldHeight) && oldWidth > 0

                synchronized(activeFlakes) {
                    if (!collect) {
                        groundedFlakes.clear()
                        groundedLayerBitmap?.eraseColor(Color.TRANSPARENT)
                    }

                    // Fill the whole screen on first start and on rotation/fold. Other count increases are
                    // handled by the render loop, which trickles new flakes in from above.
                    if (isRotation || activeFlakes.isEmpty()) {
                        activeFlakes.clear()
                        blowingFlakes.clear()
                        repeat(targetCount) {
                            val p = createNewFlake(width, height)
                            p.reset(Snowflake.Spawn.ANYWHERE)
                            activeFlakes.add(p)
                        }
                    }

                    activeFlakes.forEach { 
                        it.updateSettings(
                            screenWidth = width, 
                            screenHeight = height, 
                            windStrength = wind,
                            size = size, 
                            speed = speed, 
                            color = color, 
                            alpha = alpha, 
                            collectAtBottom = collect, 
                            rotationSpeed = rotSpeed, 
                            turbulenceSpeed = turbSpeed, 
                            turbulenceRadius = turbRadius
                        ) 
                    }
                    groundedFlakes.forEach { 
                        it.updateSettings(
                            screenWidth = width, 
                            screenHeight = height, 
                            windStrength = wind,
                            size = size, 
                            speed = speed, 
                            color = color, 
                            alpha = alpha, 
                            collectAtBottom = collect, 
                            rotationSpeed = rotSpeed, 
                            turbulenceSpeed = turbSpeed, 
                            turbulenceRadius = turbRadius,
                            pileHeight = pileHeight
                        ) 
                    }
                }
                
                if (collect) {
                    redrawGroundedLayer()
                }
            }
        }

        private fun updateScaledBackground() {
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            val original = backgroundBitmap
            if (width <= 0 || height <= 0 || original == null) {
                scaledBackground = null
                return
            }

            val bw = original.width.toFloat()
            val bh = original.height.toFloat()
            val scale = if (bw * height > width * bh) height / bh else width / bw
            
            // Use ceil and ensure at least screen dimensions to avoid crop errors
            val sw = Math.ceil((bw * scale).toDouble()).toInt().coerceAtLeast(width)
            val sh = Math.ceil((bh * scale).toDouble()).toInt().coerceAtLeast(height)
            
            val softwareScaled = try {
                original.scale(sw, sh, true)
            } catch (e: OutOfMemoryError) {
                Log.e(tag, "OOM while scaling background", e)
                return
            }
            
            // Center crop
            val x = (sw - width) / 2
            val y = (sh - height) / 2
            val cropped = try {
                Bitmap.createBitmap(softwareScaled, x, y, width, height)
            } catch (e: Exception) {
                Log.e(tag, "Error cropping background", e)
                softwareScaled
            }
            
            scaledBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hw = cropped.copy(Bitmap.Config.HARDWARE, false)
                if (cropped != softwareScaled) cropped.recycle()
                softwareScaled.recycle()
                hw ?: cropped
            } else {
                if (cropped != softwareScaled) softwareScaled.recycle()
                cropped
            }
        }

        private fun redrawGroundedLayer() {
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            if (width <= 0 || height <= 0) return

            // Hold the lock for the whole rebuild: the render thread draws this bitmap,
            // and ensureGroundedLayer may recycle it.
            synchronized(activeFlakes) {
                ensureGroundedLayer(width, height)
                groundedLayerBitmap?.eraseColor(Color.TRANSPARENT)
                groundedFlakes.forEach { p ->
                    groundedLayerCanvas?.let { p.draw(it) }
                }
            }
        }

        private fun ensureGroundedLayer(width: Int, height: Int) {
            if (groundedLayerBitmap == null || groundedLayerBitmap!!.width != width || groundedLayerBitmap!!.height != height) {
                groundedLayerBitmap?.recycle()
                try {
                    groundedLayerBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    groundedLayerCanvas = Canvas(groundedLayerBitmap!!)
                } catch (e: OutOfMemoryError) {
                    Log.e(tag, "OOM while creating grounded layer", e)
                    groundedLayerBitmap = null
                    groundedLayerCanvas = null
                }
            }
        }

        private fun createNewFlake(width: Int, height: Int): Snowflake {
            return Snowflake(
                screenWidth = width,
                screenHeight = height,
                windStrength = prefs.getFloat("wind_strength", WallpaperConfig.WIND_STRENGTH_DEFAULT),
                size = prefs.getFloat("flake_size", WallpaperConfig.FLAKE_SIZE_DEFAULT),
                speed = prefs.getFloat("fall_speed", WallpaperConfig.FALL_SPEED_DEFAULT),
                color = prefs.getInt("flake_color", WallpaperConfig.COLOR_DEFAULT),
                alpha = prefs.getInt("flake_alpha", WallpaperConfig.ALPHA_DEFAULT),
                collectAtBottom = prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT),
                rotationSpeed = prefs.getFloat("rotation_speed", WallpaperConfig.ROTATION_SPEED_DEFAULT),
                turbulenceSpeed = prefs.getFloat("turbulence_speed", WallpaperConfig.TURBULENCE_SPEED_DEFAULT),
                turbulenceRadius = prefs.getFloat("turbulence_radius", WallpaperConfig.TURBULENCE_RADIUS_DEFAULT)
            )
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = sqrt(x*x + y*y + z*z) / SensorManager.GRAVITY_EARTH
                
                if (gForce > (WallpaperConfig.SHAKE_THRESHOLD / 9.81f) + 1.0f) {
                    synchronized(activeFlakes) {
                        if (groundedFlakes.isNotEmpty()) {
                            groundedFlakes.forEach { 
                                it.blowAway()
                                blowingFlakes.add(it)
                            }
                            groundedFlakes.clear()
                            groundedLayerBitmap?.eraseColor(Color.TRANSPARENT)
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            sensorManager?.unregisterListener(this)
            stopRenderThread()
        }

        private inner class RenderThread : Thread("SnowRenderThread") {
            var running = true
            private var lastFrameNanoTime = 0L

            override fun run() {
                lastFrameNanoTime = System.nanoTime()
                
                while (running) {
                    val startTime = System.nanoTime()
                    
                    // Battery Saver Check
                    val isPowerSave = powerManager.isPowerSaveMode
                    val targetFps = if (isPowerSave) 30 else 60
                    val frameTimeNs = 1_000_000_000L / targetFps

                    doDraw()

                    val endTime = System.nanoTime()
                    val sleepTimeMs = ((frameTimeNs - (endTime - startTime)) / 1_000_000L)
                    if (sleepTimeMs > 0) {
                        try {
                            sleep(sleepTimeMs)
                        } catch (_: InterruptedException) {
                            // Exit loop
                            break
                        }
                    }
                }
            }

            private fun doDraw() {
                val holder = surfaceHolder
                var canvas: Canvas? = null
                try {
                    canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        holder.lockHardwareCanvas()
                    } else {
                        holder.lockCanvas()
                    }

                    if (canvas != null) {
                        val currentNanoTime = System.nanoTime()
                        val deltaTime = ((currentNanoTime - lastFrameNanoTime) / 1_000_000_000f).coerceAtMost(0.033f)
                        lastFrameNanoTime = currentNanoTime

                        // Draw background (pre-scaled)
                        scaledBackground?.let {
                            canvas.drawBitmap(it, 0f, 0f, null)
                        } ?: canvas.drawColor(Color.BLACK)
                        
                        val collect = prefs.getBoolean("collect_at_bottom", WallpaperConfig.COLLECT_AT_BOTTOM_DEFAULT)
                        val settleProb = prefs.getFloat("settle_probability", WallpaperConfig.SETTLE_PROBABILITY_DEFAULT)
                        val pileHeightPercent = prefs.getFloat("max_pile_height", WallpaperConfig.PILE_HEIGHT_DEFAULT)
                        
                        val targetCount = prefs.getInt("flake_count", WallpaperConfig.FLAKE_COUNT_DEFAULT)
                        val maxCount = if (powerManager.isPowerSaveMode) (targetCount * 0.5f).toInt() else targetCount

                        synchronized(activeFlakes) {
                            // Draw grounded layer (inside the lock so it can't be recycled mid-draw)
                            groundedLayerBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

                            val h = canvas.height
                            val w = canvas.width
                            val settleAreaHeight = h * pileHeightPercent
                            var settledThisFrame = 0

                            val activeIterator = activeFlakes.iterator()
                            while (activeIterator.hasNext()) {
                                val p = activeIterator.next()
                                val prevY = p.y
                                p.update(deltaTime)

                                // Settle in place the moment a flake crosses its own landing line inside the
                                // settle area (one roll per crossing), instead of teleporting it there.
                                if (collect && groundedFlakes.size < WallpaperConfig.MAX_GROUNDED_FLAKES) {
                                    val landingY = h - p.landingRoll * settleAreaHeight
                                    if (prevY < landingY && p.y >= landingY && Random.nextFloat() < settleProb) {
                                        p.settle()
                                        groundedFlakes.add(p)
                                        groundedLayerCanvas?.let { p.draw(it) }
                                        p.draw(canvas) // grounded layer was already drawn this frame
                                        activeIterator.remove()
                                        settledThisFrame++
                                        continue
                                    }
                                }

                                p.draw(canvas)

                                // Only recycle once the flake is completely below the screen.
                                if (p.y > h + p.extent) {
                                    if (activeFlakes.size > maxCount) {
                                        activeIterator.remove()
                                    } else {
                                        p.reset(Snowflake.Spawn.TOP)
                                    }
                                }
                            }

                            // Replace settled flakes right away at the top so the stream doesn't thin out.
                            repeat(settledThisFrame) {
                                if (activeFlakes.size < maxCount) {
                                    activeFlakes.add(createNewFlake(w, h).apply { reset(Snowflake.Spawn.TOP) })
                                }
                            }
                            // Any other shortfall (count slider raised, leaving battery saver) trickles in from above.
                            while (activeFlakes.size < maxCount) {
                                activeFlakes.add(createNewFlake(w, h).apply { reset(Snowflake.Spawn.ABOVE) })
                            }

                            val blowingIterator = blowingFlakes.iterator()
                            while (blowingIterator.hasNext()) {
                                val p = blowingIterator.next()
                                p.update(deltaTime)
                                p.draw(canvas)
                                // Gravity brings them back down, so only drop them once fully off the bottom or sides.
                                if (p.y > h + p.extent || p.x < -p.extent || p.x > w + p.extent) {
                                    blowingIterator.remove()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Draw error", e)
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: IllegalArgumentException) {
                            Log.e(tag, "Surface already released", e)
                        }
                    }
                }
            }
        }
    }
}
