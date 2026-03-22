package com.ocellaris.orbit

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.*

data class OrbitPoint(
    val x: Float,
    val y: Float,
    val radius: Float = 120f,  // orbit radius
    val captureRadius: Float = 60f  // how close the dot needs to be to get captured
)

data class Particle(
    var x: Float,
    var y: Float,
    var alpha: Float = 1f,
    var size: Float = 4f
)

enum class Phase {
    READY,      // waiting for first tap
    ORBITING,   // dot is orbiting a point
    TRAVELING,  // dot released, flying in a line
    GAME_OVER   // missed — show score
}

class GameState(private val context: Context) {

    // Screen dimensions (set on first frame)
    var screenWidth = 0f
    var screenHeight = 0f

    // Game phase
    var phase = Phase.READY
        private set

    // Score
    var score = 0
        private set
    var highScore = 0
        private set
    var multiplier = 1
        private set
    var perfectStreak = 0
        private set

    // Orbit points
    val orbitPoints = mutableListOf<OrbitPoint>()
    var currentOrbitIndex = 0
        private set

    // Dot state
    var dotX = 0f
        private set
    var dotY = 0f
        private set
    var dotAngle = 0f  // radians, position on orbit
        private set
    var orbitSpeed = 2.5f  // radians per second
        private set

    // Traveling state
    var travelVX = 0f
        private set
    var travelVY = 0f
        private set
    private val travelSpeed = 800f  // pixels per second base speed

    // Particles (trail)
    val particles = mutableListOf<Particle>()
    private var particleTimer = 0f

    // Visual effects
    var catchFlashAlpha = 0f
        private set
    var gameOverAlpha = 0f
        private set
    var showPerfect = false
        private set
    var perfectAlpha = 0f
        private set

    // Prefs
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
        orbitSpeed = 2.5f
        orbitPoints.clear()
        particles.clear()
        currentOrbitIndex = 0
        catchFlashAlpha = 0f
        gameOverAlpha = 0f
        showPerfect = false
        perfectAlpha = 0f

        // Create initial orbit points
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        orbitPoints.add(OrbitPoint(centerX, centerY))

        // Generate a chain of orbit points
        generateNextPoints(5)

        // Position dot on first orbit
        dotAngle = 0f
        val first = orbitPoints[0]
        dotX = first.x + first.radius * cos(dotAngle)
        dotY = first.y + first.radius * sin(dotAngle)
    }

    private fun generateNextPoints(count: Int) {
        val rng = java.util.Random()
        for (i in 0 until count) {
            val last = orbitPoints.last()
            // Place next point at a reachable distance and random-ish angle
            val angle = rng.nextFloat() * PI.toFloat() * 2f
            val distance = last.radius + 100f + rng.nextFloat() * 200f

            var nx = last.x + distance * cos(angle)
            var ny = last.y + distance * sin(angle)

            // Clamp to screen with padding
            val pad = 150f
            nx = nx.coerceIn(pad, screenWidth - pad)
            ny = ny.coerceIn(pad, screenHeight - pad)

            // Vary orbit radius slightly
            val radius = 90f + rng.nextFloat() * 60f
            val captureRadius = 50f + rng.nextFloat() * 20f

            orbitPoints.add(OrbitPoint(nx, ny, radius, captureRadius))
        }
    }

    fun onTap() {
        when (phase) {
            Phase.READY -> {
                phase = Phase.ORBITING
            }
            Phase.ORBITING -> {
                // Release dot tangentially
                val current = orbitPoints[currentOrbitIndex]
                // Tangent direction (perpendicular to radius, clockwise)
                travelVX = -sin(dotAngle) * travelSpeed
                travelVY = cos(dotAngle) * travelSpeed
                phase = Phase.TRAVELING
            }
            Phase.TRAVELING -> {
                // No action while traveling
            }
            Phase.GAME_OVER -> {
                resetGame()
                phase = Phase.ORBITING
            }
        }
    }

    fun update(dt: Float) {
        // Update particles
        updateParticles(dt)

        // Fade effects
        if (catchFlashAlpha > 0f) catchFlashAlpha = (catchFlashAlpha - dt * 3f).coerceAtLeast(0f)
        if (perfectAlpha > 0f) perfectAlpha = (perfectAlpha - dt * 2f).coerceAtLeast(0f)
        if (phase == Phase.GAME_OVER && gameOverAlpha < 1f) {
            gameOverAlpha = (gameOverAlpha + dt * 3f).coerceAtMost(1f)
        }

        when (phase) {
            Phase.READY -> {
                // Idle orbit animation
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
                // Move dot
                dotX += travelVX * dt
                dotY += travelVY * dt

                // Slight deceleration
                travelVX *= (1f - 0.3f * dt)
                travelVY *= (1f - 0.3f * dt)

                spawnTrailParticle()

                // Check capture by next orbit point(s)
                val nextIndex = currentOrbitIndex + 1
                if (nextIndex < orbitPoints.size) {
                    val next = orbitPoints[nextIndex]
                    val dist = sqrt((dotX - next.x).pow(2) + (dotY - next.y).pow(2))

                    if (dist < next.captureRadius + next.radius * 0.3f) {
                        // Captured!
                        currentOrbitIndex = nextIndex
                        phase = Phase.ORBITING

                        // Calculate angle for orbit position
                        dotAngle = atan2(dotY - next.y, dotX - next.x)

                        // Score
                        val isPerfect = dist < next.captureRadius * 0.5f
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

                        // Speed up
                        orbitSpeed += 0.12f

                        // Generate more points if running low
                        if (currentOrbitIndex >= orbitPoints.size - 3) {
                            generateNextPoints(3)
                        }

                        // Update high score
                        if (score > highScore) {
                            highScore = score
                            prefs.edit().putInt("high_score", highScore).apply()
                        }
                    }
                }

                // Check if off screen
                val margin = 100f
                if (dotX < -margin || dotX > screenWidth + margin ||
                    dotY < -margin || dotY > screenHeight + margin) {
                    phase = Phase.GAME_OVER
                    gameOverAlpha = 0f
                }
            }
            Phase.GAME_OVER -> {
                // Just animate
            }
        }
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
