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
    READY, ORBITING, TRAVELING, GAME_OVER
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
        currentOrbitIndex = 0
        catchFlashAlpha = 0f
        gameOverAlpha = 0f
        showPerfect = false
        perfectAlpha = 0f

        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        orbitPoints.add(OrbitPoint(centerX, centerY, radius = 120f, captureRadius = 120f))
        // First few points are very easy — placed directly above/right
        generateEasyPoints(3)
        generateNextPoints(3)

        dotAngle = 0f
        val first = orbitPoints[0]
        dotX = first.x + first.radius * cos(dotAngle)
        dotY = first.y + first.radius * sin(dotAngle)
    }

    private fun generateEasyPoints(count: Int) {
        // Place points in a simple ascending pattern — easy to reach from any release angle
        val rng = java.util.Random()
        for (i in 0 until count) {
            val last = orbitPoints.last()
            // Place at a fixed comfortable distance, alternating left-right
            val offsetX = if (i % 2 == 0) 200f else -200f
            val offsetY = -200f - rng.nextFloat() * 100f  // always upward

            var nx = last.x + offsetX + (rng.nextFloat() - 0.5f) * 50f
            var ny = last.y + offsetY

            val pad = 180f
            nx = nx.coerceIn(pad, screenWidth - pad)
            ny = ny.coerceIn(pad, screenHeight - pad)

            // Very large capture zone for easy points
            orbitPoints.add(OrbitPoint(nx, ny, radius = 110f, captureRadius = 130f))
        }
    }

    private fun generateNextPoints(count: Int) {
        val rng = java.util.Random()
        for (i in 0 until count) {
            val last = orbitPoints.last()
            
            // Pick a random point on the current orbit circle as the "release point"
            val releaseAngle = rng.nextFloat() * PI.toFloat() * 2f
            val releaseX = last.x + last.radius * cos(releaseAngle)
            val releaseY = last.y + last.radius * sin(releaseAngle)
            
            // Tangent direction at that release point (clockwise orbit)
            val tangentX = -sin(releaseAngle)
            val tangentY = cos(releaseAngle)
            
            // Place the next orbit point along this tangent line
            // at a comfortable distance (not too far, not too close)
            val distance = 250f + rng.nextFloat() * 150f
            
            // Add some perpendicular offset for variety (but keep it catchable)
            val perpX = cos(releaseAngle)
            val perpY = sin(releaseAngle)
            val perpOffset = (rng.nextFloat() - 0.5f) * 100f
            
            var nx = releaseX + tangentX * distance + perpX * perpOffset
            var ny = releaseY + tangentY * distance + perpY * perpOffset

            val pad = 180f
            nx = nx.coerceIn(pad, screenWidth - pad)
            ny = ny.coerceIn(pad, screenHeight - pad)

            val radius = 100f + rng.nextFloat() * 40f
            // Very generous capture radius — the whole zone around the orbit center
            val captureRadius = 100f + rng.nextFloat() * 30f

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
            Phase.GAME_OVER -> {
                resetGame()
                phase = Phase.ORBITING
            }
        }
    }

    fun update(dt: Float) {
        updateParticles(dt)

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
