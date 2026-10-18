package com.tvatlas.player.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp

/** Focus only invalidates drawing; no snapshot state, recomposition or color animation. */
internal data class ImmediateFocusPaint(
    val color: Color, val markerColor: Color, val selected: Boolean? = null, val buttonBackground: Color? = null,
) : ModifierNodeElement<ImmediateFocusPaintNode>() {
    override fun create() = ImmediateFocusPaintNode(color, markerColor, selected, buttonBackground)
    override fun update(node: ImmediateFocusPaintNode) {
        node.color = color; node.markerColor = markerColor; node.selected = selected; node.buttonBackground = buttonBackground
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "immediateFocusPaint" }
}

internal class ImmediateFocusPaintNode(var color: Color, var markerColor: Color, var selected: Boolean?, var buttonBackground: Color?) :
    Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private var focused = false
    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.hasFocus) { focused = focusState.hasFocus; invalidateDraw() }
    }
    override fun onDetach() { focused = false }
    override fun onReset() { focused = false; invalidateDraw() }
    override fun ContentDrawScope.draw() {
        buttonBackground?.let { background ->
            drawRoundRect(if (focused) color else background, cornerRadius = CornerRadius(12.dp.toPx()))
        }
        drawContent()
        if (focused && buttonBackground == null) {
            drawRoundRect(color.copy(alpha = .34f), cornerRadius = CornerRadius(12.dp.toPx()))
            if (selected == null) {
                val stroke = 2.dp.toPx()
                drawRoundRect(color, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(stroke))
            }
        }
        if (selected == true) drawRoundRect(if (focused) markerColor else color,
            size = Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
    }
}
