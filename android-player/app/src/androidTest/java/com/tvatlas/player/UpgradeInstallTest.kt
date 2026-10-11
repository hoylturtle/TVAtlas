package com.tvatlas.player

import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Used twice around adb install -r; verifies private files and preferences survive a newer APK. */
class UpgradeInstallTest {
    @Test fun privateDataSurvivesSignedUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase") ?: "seed"
        val version = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val marker = java.io.File(context.filesDir, "upgrade-install-marker.txt")
        val prefs = context.getSharedPreferences("upgrade-install-check", android.content.Context.MODE_PRIVATE)
        if (phase == "seed") {
            assertTrue(version > 0)
            marker.writeText(version.toString())
            assertTrue(prefs.edit().putString("selected_node", "fixture-hong-kong").putLong("previous_version", version).commit())
            assertEquals(version.toString(), marker.readText())
        } else {
            assertEquals("verify", phase)
            assertTrue("Private file was lost during install", marker.isFile)
            val previous = marker.readText().toLong()
            assertTrue("Expected newer versionCode after overwrite", version > previous)
            assertEquals(previous, prefs.getLong("previous_version", -1))
            assertEquals("fixture-hong-kong", prefs.getString("selected_node", null))
            assertTrue(marker.delete())
            assertTrue(prefs.edit().clear().commit())
        }
    }
}
