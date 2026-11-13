package com.hangge.rootpilot.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-built icons so the app needs no material-icons dependency. Stroke color is white; Icon()
 * tints the whole vector with the current content color.
 */
object RootPilotIcons {

    val Dashboard: ImageVector = filledIcon("Dashboard") {
        square(4f, 4f, 10f, 10f)
        square(14f, 4f, 20f, 10f)
        square(4f, 14f, 10f, 20f)
        square(14f, 14f, 20f, 20f)
    }

    val Apps: ImageVector = filledIcon("Apps") {
        rows()
    }

    val History: ImageVector = strokedIcon("History") {
        moveTo(5f, 5f)
        lineTo(19f, 5f)
        lineTo(19f, 19f)
        lineTo(5f, 19f)
        close()
        moveTo(12f, 8f)
        lineTo(12f, 12.5f)
        lineTo(15.5f, 14.5f)
    }

    val Agent: ImageVector = strokedIcon("Agent") {
        moveTo(4f, 5f)
        lineTo(20f, 5f)
        lineTo(20f, 19f)
        lineTo(4f, 19f)
        close()
        moveTo(8f, 9.5f)
        lineTo(11f, 12f)
        lineTo(8f, 14.5f)
        moveTo(13f, 15f)
        lineTo(17f, 15f)
    }

    val Settings: ImageVector = filledIcon("Settings") {
        square(4f, 6.2f, 20f, 7.8f)
        square(4f, 11.2f, 20f, 12.8f)
        square(4f, 16.2f, 20f, 17.8f)
        square(8f, 5f, 10f, 9f)
        square(14f, 10f, 16f, 14f)
        square(7f, 15f, 9f, 19f)
    }

    val Back: ImageVector = strokedIcon("Back") {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }

    private fun filledIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero, pathBuilder = block).build()

    private fun strokedIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    private fun PathBuilder.square(left: Float, top: Float, right: Float, bottom: Float) {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    private fun PathBuilder.rows() {
        listOf(6f, 12f, 18f).forEach { centerY ->
            square(4f, centerY - 1f, 6f, centerY + 1f)
            square(9f, centerY - 1f, 20f, centerY + 1f)
        }
    }
}
