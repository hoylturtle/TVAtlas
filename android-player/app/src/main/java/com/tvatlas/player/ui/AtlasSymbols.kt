package com.tvatlas.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AtlasSymbols {
    val Route = ImageVector.Builder("Route", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(16f, 3f); lineTo(20f, 7f); lineTo(16f, 11f)
            moveTo(20f, 17f); lineTo(4f, 17f); moveTo(8f, 13f); lineTo(4f, 17f); lineTo(8f, 21f)
        }
    }.build()
    val Network = ImageVector.Builder("Network", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 9f); curveTo(8f, 4f, 16f, 4f, 21f, 9f)
            moveTo(6f, 12f); curveTo(9f, 9f, 15f, 9f, 18f, 12f)
            moveTo(9f, 15f); curveTo(10.5f, 13.5f, 13.5f, 13.5f, 15f, 15f)
            moveTo(12f, 18f); lineTo(12.01f, 18f)
        }
    }.build()
    val Screen = ImageVector.Builder("Screen", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 16f); lineTo(3f, 16f); close()
            moveTo(12f, 16f); lineTo(12f, 21f); moveTo(7f, 21f); lineTo(17f, 21f)
        }
    }.build()
    val Privacy = ImageVector.Builder("Privacy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3f); lineTo(21f, 7f); curveTo(21f, 15f, 17f, 19f, 12f, 22f)
            curveTo(7f, 19f, 3f, 15f, 3f, 7f); close()
            moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
        }
    }.build()
}
