package com.ocellaris.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.*

@OptIn(ExperimentalTextApi::class)
@Composable
fun GameRenderer() {
    val context = LocalContext.current
    val gameState = remember { GameState(context) }
    val textMeasurer = rememberTextMeasurer()

    // Vibrator for haptic feedback
    val vibrator = remember {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

    var lastScore by remember { mutableIntStateOf(0) }

    // Game loop
    LaunchedEffect(Unit) {
        var lastFrameTime = System.nanoTime()
        while (true) {
            awaitFrame()
            val now = System.nanoTime()
            val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameTime = now

            gameState.update(dt)

            // Haptic on score change
            if (gameState.score != lastScore && gameState.score > 0) {
                lastScore = gameState.score
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (gameState.screenWidth == 0f) return@detectTapGestures
                    gameState.onTap()
                }
            }
    ) {
        // Init on first draw
        if (gameState.screenWidth == 0f) {
            gameState.initGame(size.width, size.height)
        }

        val cyanColor = Color(0xFF00E5FF)
        val dimCyan = Color(0x4400E5FF)
        val purpleColor = Color(0xFFE040FB)
        val whiteColor = Color.White
        val dimWhite = Color(0x33FFFFFF)

        // Draw orbit rings
        for ((i, point) in gameState.orbitPoints.withIndex()) {
            val isCurrent = i == gameState.currentOrbitIndex
            val isNext = i == gameState.currentOrbitIndex + 1
            val alpha = when {
                isCurrent -> 0.6f
                isNext -> 0.4f
                i > gameState.currentOrbitIndex -> 0.15f
                else -> 0.05f
            }

            // Orbit ring
            drawCircle(
                color = cyanColor.copy(alpha = alpha),
                radius = point.radius,
                center = Offset(point.x, point.y),
                style = Stroke(width = if (isCurrent) 2f else 1.5f)
            )

            // Center dot (pulsing for next)
            val centerAlpha = if (isNext) {
                0.4f + 0.3f * sin(System.currentTimeMillis() / 300f).toFloat()
            } else if (isCurrent) {
                0.5f
            } else {
                0.1f
            }

            drawCircle(
                color = cyanColor.copy(alpha = centerAlpha),
                radius = if (isNext) 8f else 5f,
                center = Offset(point.x, point.y)
            )

            // Capture zone indicator for next point
            if (isNext) {
                drawCircle(
                    color = cyanColor.copy(alpha = 0.1f),
                    radius = point.captureRadius + point.radius * 0.3f,
                    center = Offset(point.x, point.y),
                    style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                )
            }
        }

        // Draw particles (trail)
        for (p in gameState.particles) {
            drawCircle(
                color = cyanColor.copy(alpha = p.alpha * 0.7f),
                radius = p.size,
                center = Offset(p.x, p.y)
            )
        }

        // Draw dot
        if (gameState.phase != Phase.GAME_OVER) {
            // Glow
            drawCircle(
                color = whiteColor.copy(alpha = 0.3f),
                radius = 18f,
                center = Offset(gameState.dotX, gameState.dotY)
            )
            // Core
            drawCircle(
                color = whiteColor,
                radius = 10f,
                center = Offset(gameState.dotX, gameState.dotY)
            )
        }

        // Catch flash effect
        if (gameState.catchFlashAlpha > 0f) {
            val current = gameState.orbitPoints.getOrNull(gameState.currentOrbitIndex)
            if (current != null) {
                drawCircle(
                    color = cyanColor.copy(alpha = gameState.catchFlashAlpha * 0.5f),
                    radius = current.radius * (1f + (1f - gameState.catchFlashAlpha) * 0.5f),
                    center = Offset(current.x, current.y),
                    style = Stroke(width = 3f)
                )
            }
        }

        // Score display
        val scoreText = "${gameState.score}"
        val scoreStyle = TextStyle(
            color = whiteColor.copy(alpha = 0.9f),
            fontSize = 48.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Thin
        )
        val scoreLayout = textMeasurer.measure(scoreText, scoreStyle)
        drawText(
            textLayoutResult = scoreLayout,
            topLeft = Offset(40f, 60f)
        )

        // Multiplier
        if (gameState.multiplier > 1) {
            val multText = "×${gameState.multiplier}"
            val multStyle = TextStyle(
                color = purpleColor.copy(alpha = 0.8f),
                fontSize = 28.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light
            )
            val multLayout = textMeasurer.measure(multText, multStyle)
            drawText(
                textLayoutResult = multLayout,
                topLeft = Offset(40f + scoreLayout.size.width + 16f, 80f)
            )
        }

        // Perfect text
        if (gameState.showPerfect && gameState.perfectAlpha > 0f) {
            val perfText = "PERFECT"
            val perfStyle = TextStyle(
                color = purpleColor.copy(alpha = gameState.perfectAlpha),
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                letterSpacing = 4.sp
            )
            val perfLayout = textMeasurer.measure(perfText, perfStyle)
            drawText(
                textLayoutResult = perfLayout,
                topLeft = Offset(
                    (size.width - perfLayout.size.width) / 2f,
                    size.height * 0.35f
                )
            )
        }

        // Ready state
        if (gameState.phase == Phase.READY) {
            val tapText = "TAP TO START"
            val tapStyle = TextStyle(
                color = dimCyan.copy(alpha = 0.5f + 0.3f * sin(System.currentTimeMillis() / 500f).toFloat()),
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                letterSpacing = 6.sp
            )
            val tapLayout = textMeasurer.measure(tapText, tapStyle)
            drawText(
                textLayoutResult = tapLayout,
                topLeft = Offset(
                    (size.width - tapLayout.size.width) / 2f,
                    size.height * 0.7f
                )
            )
        }

        // Game over
        if (gameState.phase == Phase.GAME_OVER) {
            val alpha = gameState.gameOverAlpha

            // Score
            val goScoreText = "${gameState.score}"
            val goScoreStyle = TextStyle(
                color = whiteColor.copy(alpha = alpha),
                fontSize = 72.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Thin
            )
            val goScoreLayout = textMeasurer.measure(goScoreText, goScoreStyle)
            drawText(
                textLayoutResult = goScoreLayout,
                topLeft = Offset(
                    (size.width - goScoreLayout.size.width) / 2f,
                    size.height * 0.35f
                )
            )

            // High score
            val hiText = "BEST  ${gameState.highScore}"
            val hiStyle = TextStyle(
                color = dimCyan.copy(alpha = alpha * 0.7f),
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                letterSpacing = 3.sp
            )
            val hiLayout = textMeasurer.measure(hiText, hiStyle)
            drawText(
                textLayoutResult = hiLayout,
                topLeft = Offset(
                    (size.width - hiLayout.size.width) / 2f,
                    size.height * 0.35f + goScoreLayout.size.height + 16f
                )
            )

            // Tap to retry
            val retryText = "TAP TO RETRY"
            val retryStyle = TextStyle(
                color = dimCyan.copy(alpha = alpha * (0.4f + 0.3f * sin(System.currentTimeMillis() / 500f).toFloat())),
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                letterSpacing = 6.sp
            )
            val retryLayout = textMeasurer.measure(retryText, retryStyle)
            drawText(
                textLayoutResult = retryLayout,
                topLeft = Offset(
                    (size.width - retryLayout.size.width) / 2f,
                    size.height * 0.65f
                )
            )
        }
    }
}
