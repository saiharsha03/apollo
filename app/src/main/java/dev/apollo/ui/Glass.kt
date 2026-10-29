package dev.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass over the sky: a translucent white fill that is brighter at the top left (the light catching it), and a
 * thin edge that is bright on top and fades below. No backdrop blur: a true blur of the sky behind needs a second render
 * of it per panel; the translucency reads as glass on a moving sky without that cost.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false): Modifier =
    this
        .background(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (strong) .34f else .26f), Color.White.copy(alpha = if (strong) .2f else .13f), Color.White.copy(alpha = if (strong) .24f else .16f)),
                start = Offset.Zero, end = Offset.Infinite,
            ),
            shape,
        )
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .6f), Color.White.copy(alpha = .14f), Color.White.copy(alpha = .3f))), shape)
