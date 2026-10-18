package com.tvatlas.player.transfer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

internal fun inspectUploadedApk(context: Context, file: File): UploadedApk {
    ZipFile(file).use { archive ->
        val manifest = archive.getEntry("AndroidManifest.xml") ?: error("缺少应用清单")
        require(manifest.size in 1..4L * 1024 * 1024) { "应用清单无效" }
    }
    val manager = context.packageManager
    @Suppress("DEPRECATION")
    val info = manager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("无法识别安装包")
    val app = info.applicationInfo ?: error("无法识别应用")
    app.sourceDir = file.absolutePath
    app.publicSourceDir = file.absolutePath
    val label = runCatching { app.loadLabel(manager).toString().take(120) }.getOrDefault(info.packageName)
    return UploadedApk(file, label, info.packageName, info.versionName.orEmpty().take(64))
}

/** The platform installer performs signature, compatibility and user approval checks. */
internal fun openUploadedApk(context: Context, apk: UploadedApk): String? {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "请允许 TVAtlas 安装应用，返回后再次点击安装"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk.file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .apply { clipData = ClipData.newRawUri("APK", uri) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    return null
}
