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
    val leaderboard = gameState.leaderboard
    val textMeasurer = rememberTextMeasurer()

    val vibrator = remember {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

    val soundEngine = remember { SoundEngine() }

    var lastScore by remember { mutableIntStateOf(0) }
    var lastPhase by remember { mutableStateOf(Phase.READY) }
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

            // Sound effects based on state transitions
            val currentPhase = gameState.phase

            if (gameState.score != lastScore && gameState.score > 0) {
                lastScore = gameState.score
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
                if (gameState.showPerfect) {
                    soundEngine.playPerfect()
                } else {
                    soundEngine.playCatch()
                }
            }

            if (currentPhase != lastPhase) {
                when {
                    currentPhase == Phase.TRAVELING && lastPhase == Phase.ORBITING -> soundEngine.playRelease()
                    currentPhase == Phase.ORBITING && lastPhase == Phase.ATTRACT -> soundEngine.playStart()
                    currentPhase == Phase.ORBITING && lastPhase == Phase.READY -> soundEngine.playStart()
                    (currentPhase == Phase.GAME_OVER || currentPhase == Phase.NAME_ENTRY) &&
                        lastPhase == Phase.TRAVELING -> soundEngine.playGameOver()
                }
                lastPhase = currentPhase
            }
        }
    }

    // Cleanup sound engine
    DisposableEffect(Unit) {
        onDispose { soundEngine.release() }
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
            val yellowColor = Color(0xFFFFD600)
            val whiteColor = Color.White

            // CRT scanlines overlay
            val scanlineSpacing = 4f
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.12f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += scanlineSpacing
            }

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

                // Outer glow
                drawCircle(
                    color = cyanColor.copy(alpha = alpha * 0.3f),
                    radius = point.radius,
                    center = Offset(point.x, point.y),
                    style = Stroke(width = if (isCurrent) 8f else 5f)
                )
                // Core ring
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
                    // Capture zone indicator — matches actual capture radius
                    drawCircle(
                        color = cyanColor.copy(alpha = 0.1f),
                        radius = point.radius + 30f,
                        center = Offset(point.x, point.y),
                        style = Stroke(
                            width = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))
                        )
                    )
                }
            }

            // No trajectory preview — the skill is in the timing

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

            // Draw dot — layered glow for bloom effect
            if (gameState.phase == Phase.ATTRACT || gameState.phase == Phase.READY ||
                gameState.phase == Phase.ORBITING || gameState.phase == Phase.TRAVELING) {
                // Wide faint glow
                drawCircle(
                    color = cyanColor.copy(alpha = 0.1f),
                    radius = 32f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
                // Mid glow
                drawCircle(
                    color = whiteColor.copy(alpha = 0.25f),
                    radius = 20f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
                // Bright core
                drawCircle(
                    color = whiteColor.copy(alpha = 0.8f),
                    radius = 12f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
                // Hot center
                drawCircle(
                    color = whiteColor,
                    radius = 6f,
                    center = Offset(gameState.dotX, gameState.dotY)
                )
            }

            // Catch flash — big expanding ring + center burst
            if (gameState.catchFlashAlpha > 0f) {
                val current = gameState.orbitPoints.getOrNull(gameState.currentOrbitIndex)
                if (current != null) {
                    val expand = 1f + (1f - gameState.catchFlashAlpha) * 1.5f
                    // Expanding ring
                    drawCircle(
                        color = cyanColor.copy(alpha = gameState.catchFlashAlpha * 0.6f),
                        radius = current.radius * expand,
                        center = Offset(current.x, current.y),
                        style = Stroke(width = 4f)
                    )
                    // Center burst
                    drawCircle(
                        color = whiteColor.copy(alpha = gameState.catchFlashAlpha * 0.4f),
                        radius = 30f * gameState.catchFlashAlpha,
                        center = Offset(current.x, current.y)
                    )
                }
            }

            // Score (top-left) — arcade zero-padded (only during active play)
            if (gameState.phase == Phase.ORBITING || gameState.phase == Phase.TRAVELING) {
                val scoreText = String.format("%06d", gameState.score)
                val scoreStyle = TextStyle(
                    color = whiteColor.copy(alpha = 0.85f),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp
                )
                val scoreY = 140f  // below status bar + notch area
                val scoreLayout = textMeasurer.measure(scoreText, scoreStyle)
                drawText(textLayoutResult = scoreLayout, topLeft = Offset(40f, scoreY))

                // HI label + high score
                val hiText = "HI ${String.format("%06d", gameState.highScore)}"
                val hiStyle = TextStyle(
                    color = yellowColor.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                )
                val hiLayout = textMeasurer.measure(hiText, hiStyle)
                drawText(
                    textLayoutResult = hiLayout,
                    topLeft = Offset(size.width - hiLayout.size.width - 40f, scoreY + 8f)
                )

                if (gameState.multiplier > 1) {
                    val multText = "×${gameState.multiplier}"
                    val multStyle = TextStyle(
                        color = purpleColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                    val multLayout = textMeasurer.measure(multText, multStyle)
                    // Persistent multiplier badge — always visible when active
                    drawText(
                        textLayoutResult = multLayout,
                        topLeft = Offset(40f, scoreY + scoreLayout.size.height + 8f)
                    )
                }
            }

            // Perfect text + multiplier flash
            if (gameState.showPerfect && gameState.perfectAlpha > 0f) {
                val perfText = if (gameState.multiplier > 1) {
                    "PERFECT ×${gameState.multiplier}"
                } else {
                    "PERFECT"
                }
                // Glow behind text
                val glowStyle = TextStyle(
                    color = purpleColor.copy(alpha = gameState.perfectAlpha * 0.3f),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp
                )
                val glowLayout = textMeasurer.measure(perfText, glowStyle)
                drawText(
                    textLayoutResult = glowLayout,
                    topLeft = Offset(
                        (size.width - glowLayout.size.width) / 2f,
                        size.height * 0.33f
                    )
                )
                // Core text
                val perfStyle = TextStyle(
                    color = purpleColor.copy(alpha = gameState.perfectAlpha),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp
                )
                val perfLayout = textMeasurer.measure(perfText, perfStyle)
                drawText(
                    textLayoutResult = perfLayout,
                    topLeft = Offset(
                        (size.width - perfLayout.size.width) / 2f,
                        size.height * 0.33f
                    )
                )
            }

            // No ready state text — attract overlay handles INSERT COIN
        }

        // Attract mode — leaderboard + INSERT COIN over demo gameplay
        if (phaseState == Phase.ATTRACT) {
            AttractOverlay(
                leaderboard = leaderboard,
                lastGameScore = gameState.lastGameScore,
                onInsertCoin = { gameState.onTap() }
            )
        }

        // Name entry — proper phase, no state hacks
        if (phaseState == Phase.NAME_ENTRY) {
            NameEntryOverlay(
                score = gameState.score,
                initialName = playerName,
                soundEngine = soundEngine,
                onSubmit = { name ->
                    val trimmed = name.uppercase().take(3).ifEmpty { "???" }
                    playerName = trimmed
                    leaderboard.addEntry(trimmed, gameState.score)
                    leaderboard.setLastName(trimmed)
                    gameState.nameSubmitted()
                }
            )
        }

        // Game over — show leaderboard, tap goes to attract
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
fun AttractOverlay(
    leaderboard: Leaderboard,
    lastGameScore: Int,  // -1 = no game played
    onInsertCoin: () -> Unit
) {
    // Re-read entries each time attract is shown
    val entries = leaderboard.getEntries()
    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFFE040FB)
    val yellowColor = Color(0xFFFFD600)

    // Force recomposition for pulsing animation
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(50)
            tick = System.currentTimeMillis()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))  // lighter so demo is visible
            .pointerInput(Unit) {
                detectTapGestures { onInsertCoin() }
            }
    ) {
        // Main content — centered
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 32.dp)
                .padding(top = 120.dp)
        ) {
            // Title
            Text(
                text = "ORBIT",
                color = cyanColor,
                fontSize = 48.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = 16.sp
            )

            // Last game score
            if (lastGameScore >= 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "LAST GAME  ${String.format("%06d", lastGameScore)}",
                    color = purpleColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Leaderboard
            if (entries.isNotEmpty()) {
                Text(
                    text = "─── HIGH SCORES ───",
                    color = cyanColor.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                entries.forEachIndexed { index, entry ->
                    val rankColor = when (index) {
                        0 -> yellowColor
                        1 -> Color(0xFFB0BEC5)
                        2 -> Color(0xFFFF8A65)
                        else -> Color.White
                    }
                    val alpha = (0.8f - index * 0.05f).coerceAtLeast(0.3f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("%2d", index + 1),
                            color = cyanColor.copy(alpha = alpha * 0.5f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.width(32.dp)
                        )
                        Text(
                            text = entry.name.take(3).padEnd(3),
                            color = rankColor.copy(alpha = alpha),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 6.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format("%06d", entry.score),
                            color = rankColor.copy(alpha = alpha),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }

        // INSERT COIN — pinned to bottom, always visible
        @Suppress("UNUSED_EXPRESSION")
        tick
        val pulseAlpha = 0.3f + 0.5f * sin(System.currentTimeMillis() / 400f).toFloat()
        Text(
            text = "INSERT COIN",
            color = cyanColor.copy(alpha = pulseAlpha),
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }
}

@Composable
fun NameEntryOverlay(
    score: Int,
    initialName: String,
    soundEngine: SoundEngine,
    onSubmit: (String) -> Unit
) {
    val letters = ('A'..'Z').toList()
    val initial = initialName.uppercase().padEnd(3, 'A').take(3)
    var char0 by remember { mutableIntStateOf((initial[0] - 'A').coerceIn(0, 25)) }
    var char1 by remember { mutableIntStateOf((initial[1] - 'A').coerceIn(0, 25)) }
    var char2 by remember { mutableIntStateOf((initial[2] - 'A').coerceIn(0, 25)) }
    var activeSlot by remember { mutableIntStateOf(0) }

    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFFE040FB)
    var submitted by remember { mutableStateOf(false) }

    fun cycleUp(slot: Int) {
        soundEngine.playTick()
        val newVal = when (slot) {
            0 -> { char0 = (char0 + 1) % 26; char0 }
            1 -> { char1 = (char1 + 1) % 26; char1 }
            else -> { char2 = (char2 + 1) % 26; char2 }
        }
    }

    fun cycleDown(slot: Int) {
        soundEngine.playTick()
        when (slot) {
            0 -> char0 = (char0 - 1 + 26) % 26
            1 -> char1 = (char1 - 1 + 26) % 26
            else -> char2 = (char2 - 1 + 26) % 26
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = String.format("%06d", score),
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = 4.sp
            )

            Text(
                text = "NEW HIGH SCORE",
                color = purpleColor.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "ENTER YOUR INITIALS",
                color = cyanColor.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 3-character arcade picker — BIG touch targets
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val chars = listOf(char0, char1, char2)
                for (slot in 0..2) {
                    val isActive = slot == activeSlot
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(72.dp)
                    ) {
                        // UP — big tap area
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .pointerInput(slot, chars[slot]) {
                                    detectTapGestures {
                                        activeSlot = slot
                                        cycleUp(slot)
                                    }
                                }
                        ) {
                            Text(
                                text = "▲",
                                color = if (isActive) cyanColor else cyanColor.copy(alpha = 0.25f),
                                fontSize = 28.sp
                            )
                        }

                        // LETTER — tap to select slot
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .pointerInput(slot) {
                                    detectTapGestures { activeSlot = slot }
                                }
                        ) {
                            Text(
                                text = "${letters[chars[slot]]}",
                                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                                fontSize = 52.sp,
                                fontWeight = FontWeight.Light
                            )
                        }

                        // Underline
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(if (isActive) 3.dp else 1.dp)
                                .background(
                                    if (isActive) cyanColor else cyanColor.copy(alpha = 0.15f)
                                )
                        )

                        // DOWN — big tap area
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .pointerInput(slot, chars[slot]) {
                                    detectTapGestures {
                                        activeSlot = slot
                                        cycleDown(slot)
                                    }
                                }
                        ) {
                            Text(
                                text = "▼",
                                color = if (isActive) cyanColor else cyanColor.copy(alpha = 0.25f),
                                fontSize = 28.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OK button — big and obvious
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (!submitted) {
                                submitted = true
                                soundEngine.playConfirm()
                                val name = "${letters[char0]}${letters[char1]}${letters[char2]}"
                                onSubmit(name)
                            }
                        }
                    }
                    .border(
                        width = 1.dp,
                        color = cyanColor.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "▸  OK  ◂",
                    color = cyanColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 8.sp
                )
            }
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
