package com.tvatlas.player.update
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
class ApkVerifierTest {
    @Test fun packageVersionAndCertificateAreRequiredBeforeInstallation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = MessageDigest.getInstance("SHA-256").digest(info.signingInfo!!.apkContentsSigners.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val release = PublishedUpdate(info.longVersionCode.toInt(), info.versionName!!, 23, "", "")
        val apk = File(context.applicationInfo.sourceDir)
        verifyDownloadedApk(context, apk, release, cert)
        try { verifyDownloadedApk(context, apk, release, "0".repeat(64)); fail("Wrong signer accepted") }
        catch (_: UpdateException) { }
        try { verifyDownloadedApk(context, apk, release.copy(versionCode = release.versionCode + 1), cert); fail("Wrong version accepted") }
        catch (_: UpdateException) { }
    }
}
