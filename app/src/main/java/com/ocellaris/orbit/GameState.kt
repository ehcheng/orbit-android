package com.ocellaris.orbit

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
    READY, ORBITING, TRAVELING, NAME_ENTRY, GAME_OVER
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
    var perfectStreak = 0
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
    private val travelSpeed = 800f

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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("orbit_prefs", Context.MODE_PRIVATE)

    init {
        highScore = prefs.getInt("high_score", 0)
    }

    fun initGame(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        resetGame()
    }

    fun resetGame() {
        phase = Phase.READY
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

        // Randomize starting position each game
        val rng = java.util.Random()
        val startX = screenWidth * 0.25f + rng.nextFloat() * screenWidth * 0.5f
        val startY = screenHeight * 0.5f + rng.nextFloat() * screenHeight * 0.25f
        orbitPoints.add(OrbitPoint(startX, startY, radius = 110f, captureRadius = 120f))
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
            orbitPoints.add(OrbitPoint(nx, ny, radius = 100f, captureRadius = 130f))
        }
    }

    private fun placePoint(fromX: Float, fromY: Float, distance: Float, rng: java.util.Random): Pair<Float, Float> {
        val pad = 220f
        // Minimum separation: must be > sum of largest radii + capture zones
        // Orbit ~140 + capture ~130 = 270 per point, so 2*270 = 540 minimum
        val minSep = 500f

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

            // Radius and capture shrink with difficulty
            val radius = (100f - difficulty * 2f).coerceAtLeast(65f) + rng.nextFloat() * 20f
            val captureRadius = (110f - difficulty * 3f).coerceAtLeast(55f) + rng.nextFloat() * 15f

            orbitPoints.add(OrbitPoint(nx, ny, radius, captureRadius))
        }
    }

    fun onTap() {
        when (phase) {
            Phase.READY -> {
                phase = Phase.ORBITING
            }
            Phase.ORBITING -> {
                travelVX = -sin(dotAngle) * travelSpeed
                travelVY = cos(dotAngle) * travelSpeed
                phase = Phase.TRAVELING
            }
            Phase.TRAVELING -> { }
            Phase.NAME_ENTRY -> { /* handled by UI */ }
            Phase.GAME_OVER -> {
                resetGame()
                phase = Phase.ORBITING
            }
        }
    }

    fun submitName() {
        phase = Phase.GAME_OVER
        gameOverAlpha = 0f
    }

    fun update(dt: Float) {
        updateParticles(dt)
        updateTravelTrail(dt)

        if (catchFlashAlpha > 0f) catchFlashAlpha = (catchFlashAlpha - dt * 3f).coerceAtLeast(0f)
        if (perfectAlpha > 0f) perfectAlpha = (perfectAlpha - dt * 2f).coerceAtLeast(0f)
        if (phase == Phase.GAME_OVER && gameOverAlpha < 1f) {
            gameOverAlpha = (gameOverAlpha + dt * 3f).coerceAtMost(1f)
        }

        when (phase) {
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
                dotX += travelVX * dt
                dotY += travelVY * dt

                // Very slight deceleration — dot should travel far
                travelVX *= (1f - 0.1f * dt)
                travelVY *= (1f - 0.1f * dt)

                spawnTrailParticle()
                // Persistent travel trail — fades slowly
                travelTrail.add(Particle(dotX, dotY, alpha = 1f, size = 3f))

                val nextIndex = currentOrbitIndex + 1
                if (nextIndex < orbitPoints.size) {
                    val next = orbitPoints[nextIndex]
                    val dist = sqrt((dotX - next.x).pow(2) + (dotY - next.y).pow(2))

                    if (dist < next.captureRadius + next.radius) {
                        currentOrbitIndex = nextIndex
                        phase = Phase.ORBITING
                        dotAngle = atan2(dotY - next.y, dotX - next.x)

                        val isPerfect = dist < next.captureRadius * 0.7f
                        if (isPerfect) {
                            perfectStreak++
                            multiplier = (perfectStreak / 2 + 1).coerceAtMost(5)
                            showPerfect = true
                            perfectAlpha = 1f
                        } else {
                            perfectStreak = 0
                            multiplier = 1
                        }

                        score += multiplier
                        catchFlashAlpha = 1f
                        orbitSpeed += 0.12f

                        if (currentOrbitIndex >= orbitPoints.size - 3) {
                            generateNextPoints(3)
                        }

                        if (score > highScore) {
                            highScore = score
                            prefs.edit().putInt("high_score", highScore).apply()
                        }
                    }
                }

                val margin = 100f
                if (dotX < -margin || dotX > screenWidth + margin ||
                    dotY < -margin || dotY > screenHeight + margin) {
                    phase = Phase.GAME_OVER
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
