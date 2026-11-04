package com.wylew.snowlivewallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Snowflake(
    var screenWidth: Int,
    var screenHeight: Int,
    var size: Float,
    var windStrength: Float,
    var speed: Float,
    var color: Int,
    var alpha: Int,
    var collectAtBottom: Boolean,
    var rotationSpeed: Float,
    var turbulenceSpeed: Float = WallpaperConfig.TURBULENCE_SPEED_DEFAULT,
    var turbulenceRadius: Float = WallpaperConfig.TURBULENCE_RADIUS_DEFAULT
) {
    var x = 0f
    var y = 0f
    
    private var rotationX = Random.nextFloat() * 360f
    private var rotationY = Random.nextFloat() * 360f
    private var rotationZ = Random.nextFloat() * 360f
    
    private var baseRotSpeedX = (Random.nextFloat() - 0.5f) * 0.04f
    private var baseRotSpeedY = (Random.nextFloat() - 0.5f) * 0.1f
    private var baseRotSpeedZ = (Random.nextFloat() - 0.5f) * 0.06f

    private var swayOffset = Random.nextFloat() * Math.PI.toFloat() * 2
    private var swaySpeed = 0.5f + Random.nextFloat() * 1.5f
    private var horizontalDrift = (Random.nextFloat() - 0.5f) * 30f
    private var verticalOscillationOffset = Random.nextFloat() * Math.PI.toFloat() * 2
    private var verticalOscillationSpeed = 0.5f + Random.nextFloat() * 1f

    private var swirlOffset = Random.nextFloat() * Math.PI.toFloat() * 2
    
    /** Where this flake lands inside the settle area (0 = bottom edge, 1 = top of the area). */
    var landingRoll = Random.nextFloat()
        private set

    /** Half the rotated bitmap's diagonal: once the center is this far past an edge, nothing is visible. */
    val extent: Float get() = cachedBw * 0.71f

    enum class Spawn {
        /** Just above the top edge, in a thin band. Used when recycling so the stream stays even. */
        TOP,
        /** Anywhere up to a screen-height above the top. Used when adding flakes mid-animation, so they trickle in. */
        ABOVE,
        /** Anywhere from the spawn band down to the bottom. Used for the initial fill so there's no empty start. */
        ANYWHERE
    }

    /** Which design this flake draws; re-picked from [enabledDesigns] on every respawn. */
    var design = pickDesign()
        private set

    var isGrounded = false
    var isBlowingAway = false
    private var blowAwaySpeedX = 0f
    private var blowAwaySpeedY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transformationMatrix = Matrix()

    // Cached values for draw performance
    private var cachedScaleX = 1f
    private var cachedScaleY = 1f
    private var cachedBw = 0f
    private var cachedBh = 0f
    private var flakeBitmap: Bitmap? = null

    companion object {
        // Design ids. Order matches R.array.flake_design_names.
        const val DESIGN_CLASSIC = 0
        const val DESIGN_FERN = 1
        const val DESIGN_PLATE = 2
        const val DESIGN_STARBURST = 3
        const val DESIGN_FLURRY = 4
        const val DESIGN_COUNT = 5

        const val PREF_DESIGNS = "flake_designs"
        val ALL_DESIGNS: Set<String> = (0 until DESIGN_COUNT).map { it.toString() }.toSet()

        /** Designs new flakes are picked from. Shared by the wallpaper and the in-app preview. */
        @Volatile
        var enabledDesigns: IntArray = IntArray(DESIGN_COUNT) { it }
            private set

        fun loadEnabledDesigns(prefs: SharedPreferences) {
            val ids = prefs.getStringSet(PREF_DESIGNS, ALL_DESIGNS)
                ?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id in 0 until DESIGN_COUNT } }
                ?.sorted()
                .orEmpty()
            enabledDesigns = if (ids.isEmpty()) intArrayOf(DESIGN_CLASSIC) else ids.toIntArray()
        }

        private fun pickDesign(): Int = enabledDesigns.let { it[Random.nextInt(it.size)] }

        private val cache = arrayOfNulls<Bitmap>(DESIGN_COUNT)
        private var lastSize = -1f
        private var lastColor = -1

        private fun strokeWidth(size: Float) = (size * 0.14f).coerceAtLeast(1.2f)

        // Square so rotation stays centered; padding leaves room for the round stroke caps.
        private fun bitmapSide(size: Float) = size * 2f + (2f + strokeWidth(size)) * 2f

        // Called from both the UI thread and the wallpaper render thread.
        @Synchronized
        private fun getOrCreateBitmap(size: Float, color: Int, design: Int): Bitmap {
            if (lastSize != size || lastColor != color) {
                // Don't recycle: flakes may still be drawing the old bitmaps until they pick up the new ones.
                cache.fill(null)
                lastSize = size
                lastColor = color
            }
            cache[design]?.takeIf { !it.isRecycled }?.let { return it }
            return renderDesign(design, size, color).also { cache[design] = it }
        }

        /** Renders one design with arm length [size] px. Also used for the style chips in settings. */
        fun renderDesign(design: Int, size: Float, color: Int): Bitmap {
            val side = bitmapSide(size)
            val bitmap = createBitmap(
                side.toInt().coerceAtLeast(1),
                side.toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val c = side / 2f
            val stroke = strokeWidth(size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            when (design) {
                DESIGN_FERN -> drawFern(canvas, c, size, stroke, paint)
                DESIGN_PLATE -> drawPlate(canvas, c, size, stroke, paint)
                DESIGN_STARBURST -> drawStarburst(canvas, c, size, stroke, paint)
                DESIGN_FLURRY -> drawFlurry(canvas, c, size, color, paint)
                else -> drawClassic(canvas, c, size, stroke, paint)
            }
            return bitmap
        }

        /** Draws [arm] (defined pointing straight up from the center) six times around the center. */
        private fun drawSixfold(canvas: Canvas, c: Float, arm: Path, paint: Paint) {
            repeat(6) {
                canvas.drawPath(arm, paint)
                canvas.rotate(60f, c, c)
            }
        }

        /** Branch from a point on the shaft at distance [t]*r, going outward at ~55 degrees. */
        private fun Path.branch(c: Float, r: Float, t: Float, len: Float) {
            val dx = len * 0.82f
            val dy = len * 0.57f
            moveTo(c, c - r * t); lineTo(c - dx, c - r * t - dy)
            moveTo(c, c - r * t); lineTo(c + dx, c - r * t - dy)
        }

        // Classic: shaft with two V-branches per arm, matching the launcher icon (drawable/snowflake.xml).
        private fun drawClassic(canvas: Canvas, c: Float, r: Float, stroke: Float, paint: Paint) {
            val arm = Path().apply {
                moveTo(c, c); lineTo(c, c - r)
                moveTo(c, c - r * 0.72f); lineTo(c - r * 0.26f, c - r * 0.94f)
                moveTo(c, c - r * 0.72f); lineTo(c + r * 0.26f, c - r * 0.94f)
                if (r >= 8f) { // inner branches (skip when too small to read)
                    moveTo(c, c - r * 0.41f); lineTo(c - r * 0.24f, c - r * 0.61f)
                    moveTo(c, c - r * 0.41f); lineTo(c + r * 0.24f, c - r * 0.61f)
                }
            }
            drawSixfold(canvas, c, arm, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(c, c, stroke * 1.1f, paint)
        }

        // Fern: feathery dendrite with several branches per arm, finer lines.
        private fun drawFern(canvas: Canvas, c: Float, r: Float, stroke: Float, paint: Paint) {
            paint.strokeWidth = stroke * 0.75f
            val arm = Path().apply {
                moveTo(c, c); lineTo(c, c - r)
                if (r >= 8f) branch(c, r, 0.28f, r * 0.24f)
                branch(c, r, 0.48f, r * 0.30f)
                branch(c, r, 0.68f, r * 0.24f)
                branch(c, r, 0.85f, r * 0.14f)
            }
            drawSixfold(canvas, c, arm, paint)
        }

        // Plate: solid six-pointed stellar plate with etched facet lines.
        private fun drawPlate(canvas: Canvas, c: Float, r: Float, stroke: Float, paint: Paint) {
            paint.style = Paint.Style.FILL
            val arm = Path().apply {
                moveTo(c, c)
                lineTo(c + r * 0.2f, c - r * 0.42f)
                lineTo(c, c - r)
                lineTo(c - r * 0.2f, c - r * 0.42f)
                close()
            }
            drawSixfold(canvas, c, arm, paint)
            val hex = Path().apply {
                for (k in 0 until 6) {
                    val a = Math.toRadians(60.0 * k)
                    val x = c + r * 0.34f * sin(a).toFloat()
                    val y = c - r * 0.34f * cos(a).toFloat()
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            canvas.drawPath(hex, paint)
            if (r >= 10f) {
                // Etch a hexagon ring and a center line down each arm by clearing pixels.
                val etch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke * 0.45f
                    strokeCap = Paint.Cap.ROUND
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                canvas.save()
                canvas.scale(0.55f, 0.55f, c, c)
                etch.strokeWidth = stroke * 0.45f / 0.55f
                canvas.drawPath(hex, etch)
                canvas.restore()
                drawSixfold(canvas, c, Path().apply { moveTo(c, c - r * 0.4f); lineTo(c, c - r * 0.8f) }, etch)
            }
        }

        // Starburst: six long thin rays with dot tips, six short rays in between.
        private fun drawStarburst(canvas: Canvas, c: Float, r: Float, stroke: Float, paint: Paint) {
            paint.strokeWidth = stroke * 0.8f
            drawSixfold(canvas, c, Path().apply { moveTo(c, c); lineTo(c, c - r * 0.92f) }, paint)
            canvas.save()
            canvas.rotate(30f, c, c)
            paint.strokeWidth = stroke * 0.65f
            drawSixfold(canvas, c, Path().apply { moveTo(c, c); lineTo(c, c - r * 0.48f) }, paint)
            canvas.restore()
            paint.style = Paint.Style.FILL
            val dot = Path().apply { addCircle(c, c - r * 0.92f, stroke * 0.95f, Path.Direction.CW) }
            drawSixfold(canvas, c, dot, paint)
            canvas.drawCircle(c, c, stroke * 1.1f, paint)
        }

        // Soft Flurry: a soft round glow, like distant out-of-focus snow.
        private fun drawFlurry(canvas: Canvas, c: Float, r: Float, color: Int, paint: Paint) {
            val radius = r * 0.7f
            val clear = color and 0x00FFFFFF
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                c, c, radius,
                intArrayOf(color, color, clear),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(c, c, radius, paint)
        }
    }

    init {
        updateCachedDimensions()
        updateFlakeBitmap()
        reset(Spawn.ANYWHERE)
    }

    private fun updateCachedDimensions() {
        cachedBw = bitmapSide(size)
        cachedBh = cachedBw
    }

    private fun updateFlakeBitmap() {
        flakeBitmap = getOrCreateBitmap(size, color, design)
    }

    fun update(deltaTime: Float) {
        if (isGrounded) return

        if (isBlowingAway) {
            x += blowAwaySpeedX * deltaTime
            y += blowAwaySpeedY * deltaTime
            blowAwaySpeedY += 500f * deltaTime // Gravity
            rotationZ += 10f
            updateRotationalConstants()
            return
        }

        // Must be Double: epoch seconds (~1.8e9) as a Float only has ~2 minute resolution,
        // which froze the global gust below.
        val totalTime = System.currentTimeMillis() / 1000.0

        verticalOscillationOffset += deltaTime * verticalOscillationSpeed
        val vOsc = sin(verticalOscillationOffset.toDouble()).toFloat() * 15f
        
        // Turbulence/Swirl logic
        val radius = turbulenceRadius * 150f 
        val linearSpeed = 200f * turbulenceSpeed // Max path traversal speed
        
        // Angular frequency (omega) = v / R
        val omega = if (radius > 5f) (linearSpeed / radius).coerceAtMost(10f) else 0f
        
        swirlOffset += deltaTime * omega
        
        // Velocity for circular motion
        val swirlVx = if (radius > 5f) -linearSpeed * sin(swirlOffset.toDouble()).toFloat() else 0f
        val swirlVy = if (radius > 5f) linearSpeed * cos(swirlOffset.toDouble()).toFloat() else 0f
        
        // Tumbling effect tied to turbulence speed
        rotationZ += (turbulenceSpeed * 10f + turbulenceRadius * 2f) * deltaTime * 50f

        val baseSpeed = (40f + speed * 160f) + vOsc
        y += (baseSpeed + swirlVy) * deltaTime
        
        swayOffset += deltaTime * swaySpeed
        val breeze = sin(swayOffset.toDouble()).toFloat() * (15f + windStrength * 120f)
        val windDrift = windStrength * 250f
        
        // Global gust tied to turbulence speed
        val globalGust = sin(totalTime * 1.5).toFloat() * (turbulenceSpeed * 50f)
        
        x += (windDrift + breeze + horizontalDrift + globalGust + swirlVx) * deltaTime
        
        rotationX += baseRotSpeedX * rotationSpeed * (1f + turbulenceSpeed)
        rotationY += baseRotSpeedY * rotationSpeed * (1f + turbulenceSpeed)
        rotationZ += baseRotSpeedZ * rotationSpeed * (1f + turbulenceSpeed)
        
        updateRotationalConstants()

        // X-axis wrapping
        if (x > screenWidth + extent) x = -extent
        if (x < -extent) x = screenWidth + extent
    }

    private fun updateRotationalConstants() {
        cachedScaleX = cos(rotationX.toDouble()).toFloat()
        cachedScaleY = sin(rotationY.toDouble()).toFloat()
    }

    fun reset(spawn: Spawn = Spawn.TOP) {
        x = Random.nextFloat() * screenWidth
        val band = screenHeight * WallpaperConfig.SPAWN_BAND
        y = when (spawn) {
            Spawn.TOP -> -extent - Random.nextFloat() * band
            Spawn.ABOVE -> -extent - Random.nextFloat() * screenHeight
            // Covers the spawn band too, so the initial layout matches the steady-state flow.
            Spawn.ANYWHERE -> -extent - band + Random.nextFloat() * (screenHeight + band + extent)
        }
        landingRoll = Random.nextFloat()
        design = pickDesign()
        updateFlakeBitmap()
        
        isGrounded = false
        isBlowingAway = false
        swayOffset = Random.nextFloat() * Math.PI.toFloat() * 2
        horizontalDrift = (Random.nextFloat() - 0.5f) * 30f
        verticalOscillationOffset = Random.nextFloat() * Math.PI.toFloat() * 2
        
        baseRotSpeedX = (Random.nextFloat() - 0.5f) * 0.04f
        baseRotSpeedY = (Random.nextFloat() - 0.5f) * 0.1f
        baseRotSpeedZ = (Random.nextFloat() - 0.5f) * 0.06f
        
        updateRotationalConstants()
    }

    fun settle() {
        isGrounded = true
        cachedScaleX = 0.8f + Random.nextFloat() * 0.2f
        cachedScaleY = 0.8f + Random.nextFloat() * 0.2f
        rotationZ = Random.nextFloat() * 360f
    }

    fun blowAway() {
        if (!isGrounded) return
        isGrounded = false
        isBlowingAway = true
        blowAwaySpeedX = (Random.nextFloat() - 0.2f) * 800f
        blowAwaySpeedY = -Random.nextFloat() * 600f - 200f
    }

    fun draw(canvas: Canvas) {
        val bitmap = flakeBitmap ?: return
        paint.alpha = alpha
        
        transformationMatrix.reset()
        transformationMatrix.postTranslate(-cachedBw / 2f, -cachedBh / 2f)
        transformationMatrix.postScale(cachedScaleX, cachedScaleY)
        transformationMatrix.postRotate(rotationZ)
        transformationMatrix.postTranslate(x, y)
        
        canvas.drawBitmap(bitmap, transformationMatrix, paint)
    }
    
    fun updateSettings(
        screenWidth: Int,
        screenHeight: Int,
        size: Float,
        windStrength: Float,
        speed: Float,
        color: Int,
        alpha: Int,
        collectAtBottom: Boolean,
        rotationSpeed: Float,
        turbulenceSpeed: Float = WallpaperConfig.TURBULENCE_SPEED_DEFAULT,
        turbulenceRadius: Float = WallpaperConfig.TURBULENCE_RADIUS_DEFAULT,
        pileHeight: Float = WallpaperConfig.PILE_HEIGHT_DEFAULT
    ) {
        val oldWidth = this.screenWidth
        val oldHeight = this.screenHeight
        val oldSize = this.size
        val oldColor = this.color
        
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.size = size
        this.windStrength = windStrength
        this.speed = speed
        this.color = color
        this.alpha = alpha
        this.collectAtBottom = collectAtBottom
        this.rotationSpeed = rotationSpeed
        this.turbulenceSpeed = turbulenceSpeed
        this.turbulenceRadius = turbulenceRadius
        
        if (oldSize != size || oldColor != color || design !in enabledDesigns) {
            if (design !in enabledDesigns) design = pickDesign()
            updateCachedDimensions()
            updateFlakeBitmap()
        }
        
        if (!collectAtBottom && isGrounded) {
            isGrounded = false
        }

        if (isGrounded && (oldWidth != screenWidth || oldHeight != screenHeight)) {
            x = Random.nextFloat() * screenWidth
            val maxPileY = screenHeight * (1f - pileHeight)
            y = screenHeight - (Random.nextFloat() * (screenHeight - maxPileY))
        }
        
        if (!isGrounded && !isBlowingAway) {
            if (x > screenWidth + size * 10) x = Random.nextFloat() * screenWidth
            if (y > screenHeight + size * 10) y = -(Random.nextFloat() * screenHeight * 0.5f)
        }
    }
}
