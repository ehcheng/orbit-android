package com.ehcheng.orbit

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.*

data class OrbitPoint(
    val x: Float,
    val y: Float,
    val radius: Float = 120f,
    val captureRadius: Float = 60f
)

data class Particle(
    var x: Float,
    var y: Float,
    var alpha: Float = 1f,
    var size: Float = 4f
)

enum class Phase {
    ATTRACT,     // title screen — leaderboard + demo gameplay
    READY,       // waiting for first tap
    ORBITING,    // dot orbiting a point
    TRAVELING,   // dot released, flying
    NAME_ENTRY,  // entering initials (top 10 score)
    GAME_OVER    // showing leaderboard, tap to restart
}

class GameState(private val context: Context) {

    var screenWidth = 0f
    var screenHeight = 0f

    // Frame counter — Compose reads this to trigger recomposition
    var frameCount by mutableStateOf(0L)
        private set

    var phase by mutableStateOf(Phase.READY)
        private set
    var score by mutableStateOf(0)
        private set
    var highScore by mutableStateOf(0)
        private set
    var multiplier by mutableStateOf(1)
        private set
    var perfectStreak by mutableStateOf(0)
        private set

    val orbitPoints = mutableListOf<OrbitPoint>()
    var currentOrbitIndex by mutableStateOf(0)
        private set

    var dotX by mutableStateOf(0f)
        private set
    var dotY by mutableStateOf(0f)
        private set
    var dotAngle = 0f
        private set
    var orbitSpeed = 1.8f
        private set

    var travelVX = 0f
        private set
    var travelVY = 0f
        private set
    private val travelSpeed = 600f
    private val gravityStrength = 800f  // acceleration in px/s² at reference distance
    private var totalGravityAssist = 0f  // tracks how much gravity curved the path
    private var travelTime = 0f  // seconds since release
    private val maxTravelTime = 3.0f  // shot timeout
    // Demo auto-play
    private var demoOrbitTime = 0f
    private var demoPhase = 0  // 0=orbiting, 1=traveling
    // Release snapshot for perfect detection
    private var releaseX = 0f
    private var releaseY = 0f
    private var releaseDirX = 0f
    private var releaseDirY = 0f

    val particles = mutableListOf<Particle>()

    // Persistent trail — the path the dot actually traveled (stays on screen longer)
    val travelTrail = mutableListOf<Particle>()

    var catchFlashAlpha by mutableStateOf(0f)
        private set
    var gameOverAlpha by mutableStateOf(0f)
        private set
    var showPerfect by mutableStateOf(false)
        private set
    var perfectAlpha by mutableStateOf(0f)
        private set
    var lastCatchWasPerfect by mutableStateOf(false)
        private set

    private val prefs: SharedPreferences =
        context.getSharedPreferences("orbit_prefs", Context.MODE_PRIVATE)
    val leaderboard = Leaderboard(context)

    init {
        highScore = prefs.getInt("high_score", 0)
    }

    var isNewHighScore by mutableStateOf(false)
        private set
    var lastGameScore by mutableStateOf(-1)  // -1 = no game played yet
        private set

    fun initGame(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        startAttractMode()
    }

    fun startAttractMode() {
        phase = Phase.ATTRACT
        demoPhase = 0
        demoOrbitTime = 0f
        setupLevel()
    }

    fun resetGame() {
        phase = Phase.READY
        isNewHighScore = false
        setupLevel()
    }

    private fun setupLevel() {
        score = 0
        multiplier = 1
        perfectStreak = 0
        orbitSpeed = 1.8f
        orbitPoints.clear()
        particles.clear()
        travelTrail.clear()
        currentOrbitIndex = 0
        catchFlashAlpha = 0f
        gameOverAlpha = 0f
        showPerfect = false
        perfectAlpha = 0f

        val rng = java.util.Random()
        val startX = screenWidth * 0.25f + rng.nextFloat() * screenWidth * 0.5f
        val startY = screenHeight * 0.5f + rng.nextFloat() * screenHeight * 0.25f
        orbitPoints.add(OrbitPoint(startX, startY, radius = 80f, captureRadius = 90f))
        generateEasyPoints(3)
        generateNextPoints(3)

        dotAngle = 0f
        val first = orbitPoints[0]
        dotX = first.x + first.radius * cos(dotAngle)
        dotY = first.y + first.radius * sin(dotAngle)
    }

    private fun generateEasyPoints(count: Int) {
        val rng = java.util.Random()
        for (i in 0 until count) {
            val last = orbitPoints.last()
            val (nx, ny) = placePoint(last.x, last.y, 320f + rng.nextFloat() * 60f, rng)
            orbitPoints.add(OrbitPoint(nx, ny, radius = 75f, captureRadius = 85f))
        }
    }

    private fun placePoint(fromX: Float, fromY: Float, distance: Float, rng: java.util.Random): Pair<Float, Float> {
        val pad = 220f
        // Minimum separation: sum of radii + capture zones + buffer
        // With smaller circles (~80+90 = 170 per point), 2*170 + buffer = 400
        val minSep = 400f

        var bestX = fromX
        var bestY = fromY
        var bestMinDist = 0f

        for (attempt in 0 until 40) {
            val angle = rng.nextFloat() * PI.toFloat() * 2f
            val d = distance + rng.nextFloat() * 120f
            val nx = (fromX + d * cos(angle)).coerceIn(pad, screenWidth - pad)
            val ny = (fromY + d * sin(angle)).coerceIn(pad, screenHeight - pad)

            // Find the closest existing point
            val closestDist = orbitPoints.minOfOrNull { existing ->
                sqrt((nx - existing.x).pow(2) + (ny - existing.y).pow(2))
            } ?: Float.MAX_VALUE

            if (closestDist >= minSep) return Pair(nx, ny)

            // Track the best attempt (most separated) as fallback
            if (closestDist > bestMinDist) {
                bestMinDist = closestDist
                bestX = nx
                bestY = ny
            }
        }
        return Pair(bestX, bestY)
    }

    private fun generateNextPoints(count: Int) {
        val rng = java.util.Random()
        for (i in 0 until count) {
            val last = orbitPoints.last()
            val difficulty = (orbitPoints.size - 4).coerceAtLeast(0)

            // Distance increases with difficulty
            val distance = 350f + difficulty * 20f

            val (nx, ny) = placePoint(last.x, last.y, distance, rng)

            // Smaller circles — radius shrinks with difficulty
            val radius = (75f - difficulty * 2f).coerceAtLeast(50f) + rng.nextFloat() * 15f
            val captureRadius = (85f - difficulty * 3f).coerceAtLeast(40f) + rng.nextFloat() * 10f

            orbitPoints.add(OrbitPoint(nx, ny, radius, captureRadius))
        }
    }

    fun onTap() {
        when (phase) {
            Phase.ATTRACT -> {
                resetGame()
                phase = Phase.ORBITING
            }
            Phase.READY -> {
                phase = Phase.ORBITING
            }
            Phase.ORBITING -> {
                travelVX = -sin(dotAngle) * travelSpeed
                travelVY = cos(dotAngle) * travelSpeed
                totalGravityAssist = 0f
                travelTime = 0f
                releaseX = dotX
                releaseY = dotY
                val speed = sqrt(travelVX * travelVX + travelVY * travelVY)
                releaseDirX = travelVX / speed
                releaseDirY = travelVY / speed
                phase = Phase.TRAVELING
            }
            Phase.TRAVELING -> { }
            Phase.NAME_ENTRY -> { /* handled by UI overlay */ }
            Phase.GAME_OVER -> {
                startAttractMode()
            }
        }
    }

    /** Called from UI after name entry is submitted — go straight to attract */
    fun nameSubmitted() {
        lastGameScore = score
        startAttractMode()
    }

    fun update(dt: Float) {
        updateParticles(dt)
        updateTravelTrail(dt)

        if (catchFlashAlpha > 0f) catchFlashAlpha = (catchFlashAlpha - dt * 3f).coerceAtLeast(0f)
        if (perfectAlpha > 0f) perfectAlpha = (perfectAlpha - dt * 0.7f).coerceAtLeast(0f)  // slower fade — visible ~1.5s
        if (phase == Phase.GAME_OVER && gameOverAlpha < 1f) {
            gameOverAlpha = (gameOverAlpha + dt * 3f).coerceAtMost(1f)
        }

        when (phase) {
            Phase.ATTRACT -> {
                if (orbitPoints.isEmpty()) return
                when (demoPhase) {
                    0 -> {
                        // Orbiting — wait 1-2 seconds then auto-release
                        val current = orbitPoints[currentOrbitIndex]
                        dotAngle += orbitSpeed * dt
                        dotX = current.x + current.radius * cos(dotAngle)
                        dotY = current.y + current.radius * sin(dotAngle)
                        spawnTrailParticle()
                        demoOrbitTime += dt
                        if (demoOrbitTime > 1.2f) {
                            // Auto-release
                            travelVX = -sin(dotAngle) * travelSpeed
                            travelVY = cos(dotAngle) * travelSpeed
                            travelTime = 0f
                            demoPhase = 1
                        }
                    }
                    1 -> {
                        // Traveling with gravity — same physics as real game
                        val nextIndex = currentOrbitIndex + 1
                        if (nextIndex < orbitPoints.size) {
                            val next = orbitPoints[nextIndex]
                            val dx = next.x - dotX
                            val dy = next.y - dotY
                            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(30f)
                            val refDist = 300f
                            val accel = gravityStrength * (refDist / dist).coerceAtMost(4f)
                            travelVX += (dx / dist) * accel * dt
                            travelVY += (dy / dist) * accel * dt
                        }

                        dotX += travelVX * dt
                        dotY += travelVY * dt
                        travelTime += dt
                        travelVX *= (1f - 0.02f * dt)
                        travelVY *= (1f - 0.02f * dt)
                        spawnTrailParticle()
                        travelTrail.add(Particle(dotX, dotY, alpha = 1f, size = 3f))

                        // Check catch
                        if (nextIndex < orbitPoints.size) {
                            val next = orbitPoints[nextIndex]
                            val dist = sqrt((dotX - next.x).pow(2) + (dotY - next.y).pow(2))
                            if (dist < next.radius + 30f) {
                                // Caught! Move to next orbit
                                currentOrbitIndex = nextIndex
                                dotAngle = atan2(dotY - next.y, dotX - next.x)
                                catchFlashAlpha = 1f
                                demoPhase = 0
                                demoOrbitTime = 0f
                                if (currentOrbitIndex >= orbitPoints.size - 3) {
                                    generateNextPoints(3)
                                }
                            }
                        }

                        // Miss or timeout — restart demo
                        val margin = 150f
                        if (dotX < -margin || dotX > screenWidth + margin ||
                            dotY < -margin || dotY > screenHeight + margin ||
                            travelTime > maxTravelTime) {
                            // Reset demo
                            setupLevel()
                            demoPhase = 0
                            demoOrbitTime = 0f
                        }
                    }
                }
            }
            Phase.READY -> {
                val current = orbitPoints[currentOrbitIndex]
                dotAngle += orbitSpeed * dt
                dotX = current.x + current.radius * cos(dotAngle)
                dotY = current.y + current.radius * sin(dotAngle)
                spawnTrailParticle()
            }
            Phase.ORBITING -> {
                val current = orbitPoints[currentOrbitIndex]
                dotAngle += orbitSpeed * dt
                dotX = current.x + current.radius * cos(dotAngle)
                dotY = current.y + current.radius * sin(dotAngle)
                spawnTrailParticle()
            }
            Phase.TRAVELING -> {
                // Apply gravity from the next orbit point
                val nextIndex = currentOrbitIndex + 1
                if (nextIndex < orbitPoints.size) {
                    val next = orbitPoints[nextIndex]
                    val dx = next.x - dotX
                    val dy = next.y - dotY
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(30f)

                    // Gravity: acceleration = G * (refDist / dist)
                    // Linear falloff — strong enough to visibly curve the path
                    val refDist = 300f
                    val accel = gravityStrength * (refDist / dist).coerceAtMost(4f)

                    // Direction toward the orbit point
                    val dirX = dx / dist
                    val dirY = dy / dist

                    travelVX += dirX * accel * dt
                    travelVY += dirY * accel * dt

                    // Track cumulative velocity change from gravity
                    totalGravityAssist += accel * dt
                }

                // Move dot
                dotX += travelVX * dt
                dotY += travelVY * dt
                travelTime += dt

                // Minimal drag
                travelVX *= (1f - 0.02f * dt)
                travelVY *= (1f - 0.02f * dt)

                spawnTrailParticle()
                travelTrail.add(Particle(dotX, dotY, alpha = 1f, size = 3f))

                // Check capture
                if (nextIndex < orbitPoints.size) {
                    val next = orbitPoints[nextIndex]
                    val dist = sqrt((dotX - next.x).pow(2) + (dotY - next.y).pow(2))

                    // Capture zone = just the orbit radius (not capture + radius)
                    if (dist < next.radius + 30f) {
                        currentOrbitIndex = nextIndex
                        phase = Phase.ORBITING
                        dotAngle = atan2(dotY - next.y, dotX - next.x)

                        // Perfect = the straight line from release would have hit near the center
                        // Calculate closest distance from the release line to the orbit center
                        val toTargetX = next.x - releaseX
                        val toTargetY = next.y - releaseY
                        // Project target onto release direction
                        val projLen = toTargetX * releaseDirX + toTargetY * releaseDirY
                        // Perpendicular distance from line to center
                        val perpX = toTargetX - releaseDirX * projLen
                        val perpY = toTargetY - releaseDirY * projLen
                        val lineToCenter = sqrt(perpX * perpX + perpY * perpY)
                        // Perfect if the straight line passes through the circle at all
                        val isPerfect = lineToCenter < next.radius && projLen > 0f
                        lastCatchWasPerfect = isPerfect
                        if (isPerfect) {
                            perfectStreak++
                            multiplier = (perfectStreak + 1).coerceAtMost(5)
                            showPerfect = true
                            perfectAlpha = 1f
                        } else {
                            perfectStreak = 0
                            multiplier = 1
                        }

                        score += multiplier
                        catchFlashAlpha = 1f
                        orbitSpeed += 0.10f
                        totalGravityAssist = 0f  // reset for next launch

                        if (currentOrbitIndex >= orbitPoints.size - 3) {
                            generateNextPoints(3)
                        }

                        if (score > highScore) {
                            highScore = score
                            prefs.edit().putInt("high_score", highScore).apply()
                        }
                    }
                }

                val margin = 150f
                if (dotX < -margin || dotX > screenWidth + margin ||
                    dotY < -margin || dotY > screenHeight + margin ||
                    travelTime > maxTravelTime) {
                    totalGravityAssist = 0f
                    lastGameScore = score
                    if (score > 0 && leaderboard.isHighScore(score)) {
                        isNewHighScore = true
                        phase = Phase.NAME_ENTRY
                    } else {
                        startAttractMode()
                    }
                    gameOverAlpha = 0f
                }
            }
            Phase.NAME_ENTRY -> { /* waiting for input */ }
            Phase.GAME_OVER -> { }
        }

        // Bump frame counter to trigger Compose recomposition
        frameCount++
    }

    private fun spawnTrailParticle() {
        particles.add(Particle(dotX, dotY, alpha = 0.8f, size = 6f))
        if (particles.size > 60) {
            particles.removeAt(0)
        }
    }

    private fun updateTravelTrail(dt: Float) {
        // Very slow fade — trail stays visible for ~3 seconds
        val iterator = travelTrail.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.alpha -= dt * 0.35f
            if (p.alpha <= 0f) {
                iterator.remove()
            }
        }
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.alpha -= dt * 2.5f
            p.size -= dt * 3f
            if (p.alpha <= 0f || p.size <= 0.5f) {
                iterator.remove()
            }
        }
    }
}
