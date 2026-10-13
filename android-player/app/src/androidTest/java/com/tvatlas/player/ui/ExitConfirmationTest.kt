package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExitConfirmationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun exitRequiresAnExplicitDecision() {
        var exited = false; var cancelled = false
        compose.setContent { MaterialTheme(colorScheme = colors) { ExitConfirmation({ cancelled = true }, { exited = true }) } }
        compose.onNodeWithText("继续观看").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(exited) }
        compose.onNodeWithText("退出 APP").performClick()
        compose.runOnIdle { assertTrue(exited) }
    }
}
