package com.ocellaris.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.*

@OptIn(ExperimentalTextApi::class)
@Composable
fun GameRenderer() {
    val context = LocalContext.current
    val gameState = remember { GameState(context) }
    val leaderboard = remember { Leaderboard(context) }
    val textMeasurer = rememberTextMeasurer()

    val vibrator = remember {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

    var lastScore by remember { mutableIntStateOf(0) }
    var playerName by remember { mutableStateOf(leaderboard.getLastName()) }

    // Game loop
    LaunchedEffect(Unit) {
        var lastFrameTime = System.nanoTime()
        while (true) {
            awaitFrame()
            val now = System.nanoTime()
            val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameTime = now

            gameState.update(dt)

            if (gameState.score != lastScore && gameState.score > 0) {
                lastScore = gameState.score
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        }
    }

    // Read reactive state
    val frame = gameState.frameCount
    val phaseState = gameState.phase

    Box(modifier = Modifier.fillMaxSize()) {
        // Game canvas
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
            if (gameState.screenWidth == 0f) {
                gameState.initGame(size.width, size.height)
            }

            val cyanColor = Color(0xFF00E5FF)
            val dimCyan = Color(0x4400E5FF)
            val purpleColor = Color(0xFFE040FB)
            val whiteColor = Color.White

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

                drawCircle(
                    color = cyanColor.copy(alpha = alpha),
                    radius = point.radius,
                    center = Offset(point.x, point.y),
                    style = Stroke(width = if (isCurrent) 2f else 1.5f)
                )

                val centerAlpha = if (isNext) {
                    0.4f + 0.3f * sin(System.currentTimeMillis() / 300f).toFloat()
                } else if (isCurrent) 0.5f else 0.1f

                drawCircle(
                    color = cyanColor.copy(alpha = centerAlpha),
                    radius = if (isNext) 8f else 5f,
                    center = Offset(point.x, point.y)
                )

                if (isNext) {
                    // Capture zone — subtle dashed ring only (no fill)
                    drawCircle(
                        color = cyanColor.copy(alpha = 0.12f),
                        radius = point.captureRadius + point.radius,
                        center = Offset(point.x, point.y),
                        style = Stroke(
                            width = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f))
                        )
                    )
                }
            }

            // Draw trajectory preview while orbiting — thick glowing line
            if (gameState.phase == Phase.ORBITING || gameState.phase == Phase.READY) {
                val tangentX = -sin(gameState.dotAngle)
                val tangentY = cos(gameState.dotAngle)
                val lineLength = 500f

                // Outer glow (wide, visible)
                drawLine(
                    color = cyanColor.copy(alpha = 0.25f),
                    start = Offset(gameState.dotX, gameState.dotY),
                    end = Offset(
                        gameState.dotX + tangentX * lineLength,
                        gameState.dotY + tangentY * lineLength
                    ),
                    strokeWidth = 16f,
                    cap = StrokeCap.Round
                )

                // Mid glow
                drawLine(
                    color = cyanColor.copy(alpha = 0.45f),
                    start = Offset(gameState.dotX, gameState.dotY),
                    end = Offset(
                        gameState.dotX + tangentX * lineLength,
                        gameState.dotY + tangentY * lineLength
                    ),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )

                // Core line (bright)
                drawLine(
                    color = whiteColor.copy(alpha = 0.7f),
                    start = Offset(gameState.dotX, gameState.dotY),
                    end = Offset(
                        gameState.dotX + tangentX * lineLength * 0.85f,
                        gameState.dotY + tangentY * lineLength * 0.85f
                    ),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )

                // Arrow dots at the tip
                for (i in 1..10) {
                    val t = 0.7f + i * 0.03f
                    val dist = t * lineLength
                    val dotAlpha = (1f - t) * 0.8f
                    drawCircle(
                        color = cyanColor.copy(alpha = dotAlpha),
                        radius = 3.5f,
                        center = Offset(
                            gameState.dotX + tangentX * dist,
                            gameState.dotY + tangentY * dist
                        )
                    )
                }
            }

            // Draw persistent travel trail (the path the dot actually flew)
            for (p in gameState.travelTrail) {
                drawCircle(
                    color = cyanColor.copy(alpha = p.alpha * 0.5f),
                    radius = p.size + 2f,
                    center = Offset(p.x, p.y)
                )
                drawCircle(
                    color = whiteColor.copy(alpha = p.alpha * 0.3f),
                    radius = p.size,
                    center = Offset(p.x, p.y)
                )
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
            if (gameState.phase != Phase.GAME_OVER && gameState.phase != Phase.NAME_ENTRY) {
                drawCircle(
                    color = whiteColor.copy(alpha = 0.3f),
                    radius = 18f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
                drawCircle(
                    color = whiteColor,
                    radius = 10f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
            }

            // Catch flash
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

            // Score (top-left)
            if (gameState.phase != Phase.GAME_OVER && gameState.phase != Phase.NAME_ENTRY) {
                val scoreText = "${gameState.score}"
                val scoreStyle = TextStyle(
                    color = whiteColor.copy(alpha = 0.9f),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Thin
                )
                val scoreLayout = textMeasurer.measure(scoreText, scoreStyle)
                drawText(textLayoutResult = scoreLayout, topLeft = Offset(40f, 60f))

                if (gameState.multiplier > 1) {
                    val multText = "×${gameState.multiplier}"
                    val multStyle = TextStyle(
                        color = purpleColor.copy(alpha = 0.8f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light
                    )
                    val multLayout = textMeasurer.measure(multText, multStyle)
                    drawText(
                        textLayoutResult = multLayout,
                        topLeft = Offset(40f + scoreLayout.size.width + 16f, 80f)
                    )
                }
            }

            // Perfect text
            if (gameState.showPerfect && gameState.perfectAlpha > 0f) {
                val perfStyle = TextStyle(
                    color = purpleColor.copy(alpha = gameState.perfectAlpha),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 4.sp
                )
                val perfLayout = textMeasurer.measure("PERFECT", perfStyle)
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
                val tapStyle = TextStyle(
                    color = dimCyan.copy(
                        alpha = 0.5f + 0.3f * sin(System.currentTimeMillis() / 500f).toFloat()
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp
                )
                val tapLayout = textMeasurer.measure("TAP TO START", tapStyle)
                drawText(
                    textLayoutResult = tapLayout,
                    topLeft = Offset(
                        (size.width - tapLayout.size.width) / 2f,
                        size.height * 0.7f
                    )
                )
            }
        }

        // Name entry overlay
        if (phaseState == Phase.NAME_ENTRY) {
            NameEntryOverlay(
                score = gameState.score,
                initialName = playerName,
                onSubmit = { name ->
                    val trimmed = name.uppercase().take(3).ifEmpty { "???" }
                    playerName = trimmed
                    leaderboard.addEntry(trimmed, gameState.score)
                    leaderboard.setLastName(trimmed)
                    gameState.submitName()
                }
            )
        }

        // Game over overlay with leaderboard
        if (phaseState == Phase.GAME_OVER) {
            GameOverOverlay(
                score = gameState.score,
                leaderboard = leaderboard,
                onRetry = { gameState.onTap() }
            )
        }
    }
}

@Composable
fun NameEntryOverlay(
    score: Int,
    initialName: String,
    onSubmit: (String) -> Unit
) {
    val letters = ('A'..'Z').toList()
    // Parse initial name into 3 letter indices
    val initial = initialName.uppercase().padEnd(3, 'A').take(3)
    var char0 by remember { mutableIntStateOf((initial[0] - 'A').coerceIn(0, 25)) }
    var char1 by remember { mutableIntStateOf((initial[1] - 'A').coerceIn(0, 25)) }
    var char2 by remember { mutableIntStateOf((initial[2] - 'A').coerceIn(0, 25)) }
    var activeSlot by remember { mutableIntStateOf(0) }

    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFFE040FB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Score
            Text(
                text = "$score",
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Thin
            )

            Text(
                text = "NEW HIGH SCORE",
                color = purpleColor.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ENTER YOUR INITIALS",
                color = cyanColor.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3-character arcade picker
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val chars = listOf(char0, char1, char2)
                for (slot in 0..2) {
                    val isActive = slot == activeSlot
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Up arrow
                        Text(
                            text = "▲",
                            color = if (isActive) cyanColor.copy(alpha = 0.8f) else cyanColor.copy(alpha = 0.2f),
                            fontSize = 20.sp,
                            modifier = Modifier
                                .pointerInput(slot) {
                                    detectTapGestures {
                                        activeSlot = slot
                                        val newVal = (chars[slot] + 1) % 26
                                        when (slot) { 0 -> char0 = newVal; 1 -> char1 = newVal; 2 -> char2 = newVal }
                                    }
                                }
                                .padding(8.dp)
                        )

                        // Letter
                        Text(
                            text = "${letters[chars[slot]]}",
                            color = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp,
                            modifier = Modifier
                                .pointerInput(slot) {
                                    detectTapGestures {
                                        activeSlot = slot
                                    }
                                }
                        )

                        // Underline for active slot
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(2.dp)
                                .background(
                                    if (isActive) cyanColor.copy(alpha = 0.8f)
                                    else cyanColor.copy(alpha = 0.15f)
                                )
                        )

                        // Down arrow
                        Text(
                            text = "▼",
                            color = if (isActive) cyanColor.copy(alpha = 0.8f) else cyanColor.copy(alpha = 0.2f),
                            fontSize = 20.sp,
                            modifier = Modifier
                                .pointerInput(slot) {
                                    detectTapGestures {
                                        activeSlot = slot
                                        val newVal = (chars[slot] - 1 + 26) % 26
                                        when (slot) { 0 -> char0 = newVal; 1 -> char1 = newVal; 2 -> char2 = newVal }
                                    }
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // OK button
            Text(
                text = "▸  OK  ◂",
                color = cyanColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures {
                            val name = "${letters[char0]}${letters[char1]}${letters[char2]}"
                            onSubmit(name)
                        }
                    }
                    .border(
                        width = 1.dp,
                        color = cyanColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun GameOverOverlay(
    score: Int,
    leaderboard: Leaderboard,
    onRetry: () -> Unit
) {
    val entries = remember(score) { leaderboard.getEntries() }
    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFFE040FB)
    val yellowColor = Color(0xFFFFD600)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                detectTapGestures { onRetry() }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // GAME OVER
            Text(
                text = "GAME OVER",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp
            )

            // Score
            Text(
                text = String.format("%06d", score),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // HIGH SCORES header
            if (entries.isNotEmpty()) {
                Text(
                    text = "─── HIGH SCORES ───",
                    color = cyanColor.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Rank  NAME  SCORE — arcade style
                entries.forEachIndexed { index, entry ->
                    val isLatest = entry.score == score &&
                        entry.timestamp == entries.filter { it.score == score }.maxByOrNull { it.timestamp }?.timestamp
                    val rankColor = when (index) {
                        0 -> yellowColor   // Gold
                        1 -> Color(0xFFB0BEC5) // Silver
                        2 -> Color(0xFFFF8A65) // Bronze
                        else -> Color.White
                    }
                    val nameColor = if (isLatest) purpleColor else rankColor
                    val alpha = if (isLatest) 1f else (0.7f - index * 0.04f).coerceAtLeast(0.3f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Rank
                        Text(
                            text = String.format("%2d", index + 1),
                            color = cyanColor.copy(alpha = alpha * 0.5f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.width(36.dp)
                        )
                        // Name — 3 chars, spaced
                        Text(
                            text = entry.name.take(3).padEnd(3),
                            color = nameColor.copy(alpha = alpha),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 6.sp,
                            modifier = Modifier.weight(1f)
                        )
                        // Score — zero-padded
                        Text(
                            text = String.format("%06d", entry.score),
                            color = nameColor.copy(alpha = alpha),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // INSERT COIN / TAP TO RETRY
            val pulseAlpha = 0.3f + 0.4f * sin(System.currentTimeMillis() / 400f).toFloat()
            Text(
                text = "INSERT COIN",
                color = cyanColor.copy(alpha = pulseAlpha),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp
            )
        }
    }
}
