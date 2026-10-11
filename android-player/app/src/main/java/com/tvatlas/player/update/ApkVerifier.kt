package com.tvatlas.player.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

@Suppress("DEPRECATION")
internal fun verifyDownloadedApk(context: Context, file: File, release: PublishedUpdate,
    certificate: String = RELEASE_CERTIFICATE_SHA256) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw UpdateException("下载文件不是有效的 Android 安装包")
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    if (info.packageName != context.packageName || code != release.versionCode.toLong() || info.versionName != release.versionName)
        throw UpdateException("安装包应用或版本与发布信息不符")
    val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
    if (signers.size != 1) throw UpdateException("安装包签名不匹配，已阻止安装")
    val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()).joinToString("") { "%02x".format(it) }
    if (digest != certificate) throw UpdateException("安装包签名不匹配，已阻止安装")
}
