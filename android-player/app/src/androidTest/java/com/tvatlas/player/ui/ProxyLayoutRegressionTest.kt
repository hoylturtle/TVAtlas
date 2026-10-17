package com.tvatlas.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.proxy.NodeTestProgress
import com.tvatlas.player.storage.Library
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProxyLayoutRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()

    private fun show(width: Int, tv: Boolean) {
        val profiles = (0..7).map { ProxyProfile("p$it", "香港节点 $it", ProxyType.HTTP, "localhost", 7890) }
        val library = Library(emptyList(), emptyList(), profiles, RuleConfig())
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides tv) {
            Box(Modifier.requiredSize(width.dp, 800.dp)) {
                RoutingPage(library, {}, {}, {}, {}, emptyList(), false, {}, {}, {}, {},
                    nodeTesting = true, nodeTestProgress = NodeTestProgress(total = 8, completed = 2, activeIds = setOf("p2"), running = true))
            }
        } } }
    }
    @Test fun phoneToolbarFiltersAndFirstCardDoNotOverlap() {
        show(360, false)
        val actions = compose.onNodeWithTag("node-actions").getUnclippedBoundsInRoot()
        val regions = compose.onNodeWithTag("node-regions").getUnclippedBoundsInRoot()
        val toolbar = compose.onNodeWithTag("node-toolbar").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("proxy-node:p0").getUnclippedBoundsInRoot()
        assertTrue(actions.bottom <= regions.top)
        assertTrue(toolbar.bottom <= card.top)
    }
    @Test fun phoneRuleExplanationStartsAfterBuiltinCard() {
        show(360, false)
        compose.onNodeWithText("规则", useUnmergedTree = true).performClick()
        val builtin = compose.onNodeWithTag("builtin-rules").getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithTag("custom-rule-heading").getUnclippedBoundsInRoot()
        assertTrue(builtin.bottom <= heading.top)
    }
    @Test fun televisionRuleExplanationStartsAfterBuiltinCard() {
        show(1280, true)
        compose.onNodeWithTag("routing-section:2").performClick()
        val builtin = compose.onNodeWithTag("builtin-rules").getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithTag("custom-rule-heading").getUnclippedBoundsInRoot()
        assertTrue(builtin.bottom <= heading.top)
    }
}
