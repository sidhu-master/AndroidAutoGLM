package com.sidhu.androidautoglm.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb

// ==========================================
// Voice Orb Component (Smart Palette)
// ==========================================

data class OrbPalette(
    val innerTint: Color,
    val mainColor: Color,
    val deepShade: Color
)

fun getOrbPalette(baseColor: Color): OrbPalette {
    val colorInt = baseColor.toArgb()
    
    return when (colorInt) {
        0xFFE53935.toInt() -> OrbPalette(
            innerTint = Color(0xFFFF8A80),
            mainColor = baseColor,
            deepShade = Color(0xFFB71C1C)
        )
        0xFFFB8C00.toInt() -> OrbPalette(
            innerTint = Color(0xFFFFD180),
            mainColor = baseColor,
            deepShade = Color(0xFFE65100)
        )
        0xFFFFA000.toInt() -> OrbPalette(
            innerTint = Color(0xFFFFE082),
            mainColor = baseColor,
            deepShade = Color(0xFFFF6F00)
        )
        0xFF43A047.toInt() -> OrbPalette(
            innerTint = Color(0xFFA5D6A7),
            mainColor = baseColor,
            deepShade = Color(0xFF1B5E20)
        )
        0xFF00ACC1.toInt() -> OrbPalette(
            innerTint = Color(0xFF80DEEA),
            mainColor = baseColor,
            deepShade = Color(0xFF006064)
        )
        0xFF2196F3.toInt() -> OrbPalette(
            innerTint = Color(0xFF40C4FF), 
            mainColor = Color(0xFF2962FF), 
            deepShade = Color(0xFF000051)  
        )
        0xFF8E24AA.toInt() -> OrbPalette(
            innerTint = Color(0xFFCE93D8),
            mainColor = baseColor,
            deepShade = Color(0xFF4A148C)
        )
        else -> OrbPalette(
            innerTint = baseColor.copy(alpha = 0.6f),
            mainColor = baseColor,
            deepShade = baseColor.copy(red = baseColor.red * 0.4f, green = baseColor.green * 0.4f, blue = baseColor.blue * 0.4f)
        )
    }
}

@Composable
fun VoiceOrb(
    isListening: Boolean,
    soundLevel: Float,
    baseColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_breathing")
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    val targetScale = if (isListening) {
        val normalized = ((soundLevel + 60f) / 60f).coerceIn(0f, 1f)
        1.0f + (normalized * 0.4f)
    } else {
        1.0f
    }

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "soundScale"
    )

    val finalScale = if (isListening) animatedScale else idleScale

    val palette = getOrbPalette(baseColor)
    
    val coreWhite = Color(0xFFF0F8FF)
    val glowColor = baseColor

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = finalScale
                scaleY = finalScale
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if(isListening) 0.6f else 0.3f }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.3f),
                        glowColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    radius = size.width / 1.5f
                )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize(0.9f)) {
            val radius = size.width / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreWhite,
                        palette.innerTint,
                        palette.mainColor,
                        palette.deepShade
                    ),
                    center = Offset(radius * 0.6f, radius * 0.6f),
                    radius = radius * 2.5f
                )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize(0.9f)) {
            val width = size.width
            val height = size.height
            
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    start = Offset(width * 0.2f, height * 0.2f),
                    end = Offset(width * 0.5f, height * 0.5f)
                ),
                topLeft = Offset(width * 0.15f, height * 0.15f),
                size = androidx.compose.ui.geometry.Size(width * 0.35f, height * 0.25f)
            )

            drawArc(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.innerTint.copy(alpha = 0.7f)
                    )
                ),
                startAngle = 30f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(width * 0.05f, height * 0.05f),
                size = androidx.compose.ui.geometry.Size(width * 0.9f, height * 0.9f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }
    }
}

@Composable
fun MiniVoiceOrb(
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = getOrbPalette(baseColor)
    val coreWhite = Color(0xFFF0F8FF)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreWhite,
                        palette.innerTint,
                        palette.mainColor,
                        palette.deepShade
                    ),
                    center = Offset(radius * 0.6f, radius * 0.6f),
                    radius = radius * 2.5f
                )
            )
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    start = Offset(size.width * 0.2f, size.height * 0.2f),
                    end = Offset(size.width * 0.5f, size.height * 0.5f)
                ),
                topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.35f, size.height * 0.25f)
            )
        }
    }
}
