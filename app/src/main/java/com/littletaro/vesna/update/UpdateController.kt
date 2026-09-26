package com.littletaro.vesna.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.littletaro.vesna.BuildConfig
import com.littletaro.vesna.core.OperationLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 应用内自动更新：检查 GitHub Latest Release → DownloadManager 后台下载 →
 * 四重校验 → 交给系统安装器。
 *
 * 四重校验依次是：SHA-256、包名、versionCode 必须严格大于当前版本、签名与已装应用一致。
 * 任何一项不通过都不会调起安装器，并会把原因写进运行记录。
 *
 * 设计上不与界面耦合：状态通过 [onStatus] / [onDownloadActive] 回调给调用方，
 * 因此主页与设置页都能复用同一个控制器实例的语义。
 */
class UpdateController(
    private val activity: Activity,
    private val onStatus: (String) -> Unit = {},
    private val onDownloadActive: (Boolean) -> Unit = {},
) {

    private data class LatestRelease(
        val version: String,
        val notes: String,
        val asset: ReleaseAsset,
    )

    private var attached = false
    private var installPromptShown = false
    private var validationInProgress = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val pendingId = preferences().getLong(KEY_DOWNLOAD_ID, -1L)
            if (completedId == -1L || completedId != pendingId) return
            verifyDownloadedApk()
        }
    }

    fun attach() {
        if (attached) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(downloadReceiver, filter)
        }
        attached = true
    }

    fun detach() {
        if (!attached) return
        runCatching { activity.unregisterReceiver(downloadReceiver) }
        attached = false
    }

    fun updatesEnabled(): Boolean = preferences().getBoolean(KEY_UPDATES_ENABLED, true)

    fun setUpdatesEnabled(enabled: Boolean) {
        preferences().edit().putBoolean(KEY_UPDATES_ENABLED, enabled).apply()
        OperationLog.record(activity, if (enabled) "已开启自动检查更新" else "已关闭自动检查更新")
    }

    /** 应用启动时调用：限频（默认 6 小时）检查一次，避免每次进前台都打 GitHub。 */
    fun checkOnLaunch() {
        if (!updatesEnabled()) return
        val lastCheck = preferences().getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return
        checkForUpdates(manual = false)
    }

    /** 设置页「检查更新」按钮：不受限频影响。 */
    fun checkNow() = checkForUpdates(manual = true)

    /**
     * 回到前台时调用：如果上次留下了「已下载待安装」的包且权限已就绪，
     * 直接接着走安装流程，不让用户卡在「正在下载」的假象里。
     */
    fun resumePendingInstallIfAllowed() {
        val prefs = preferences()
        if (!prefs.getBoolean(KEY_READY_TO_INSTALL, false)) return
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) == -1L) return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        val uri = prefs.getString(KEY_DOWNLOAD_URI, null)?.let(Uri::parse) ?: return
        launchInstaller(uri)
    }

    // ------------------------------------------------------------ 检查

    private fun checkForUpdates(manual: Boolean) {
        if (validationInProgress) return
        updateStatus(if (manual) "正在检查更新…" else "")
        Thread {
            val result = runCatching { fetchLatestRelease() }
            activity.runOnUiThread {
                result
                    .onSuccess { handleRelease(it, manual) }
                    .onFailure { error ->
                        OperationLog.record(
                            activity,
                            "检查更新失败",
                            error.javaClass.simpleName,
                        )
                        if (manual) updateStatus("检查更新失败，请检查网络后重试")
                    }
            }
        }.start()
    }

    private fun fetchLatestRelease(): LatestRelease {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Vesna-Android")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub 返回 HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: String): LatestRelease {
        val root = JSONObject(json)
        val tag = root.getString("tag_name")
        val version = UpdatePolicy.normalizedVersion(tag)
            ?: throw IllegalStateException("版本号格式无法识别：$tag")
        val assetsJson = root.getJSONArray("assets")
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                val item = assetsJson.getJSONObject(index)
                add(
                    ReleaseAsset(
                        name = item.getString("name"),
                        downloadUrl = item.getString("browser_download_url"),
                        size = item.optLong("size", 0L),
                        digest = assetDigest(item),
                    ),
                )
            }
        }
        val asset = UpdatePolicy.selectApk(version, assets)
            ?: throw IllegalStateException("Release 缺少合法命名的 APK 资产或 SHA-256")
        return LatestRelease(
            version = version,
            notes = root.optString("body", "").take(MAX_RELEASE_NOTES_CHARS),
            asset = asset,
        )
    }

    /**
     * GitHub 从 2025 年起在 assets 上直接给出 `digest` 字段（形如 `sha256:...`）。
     * 老接口没有这个字段时返回空串，后续资产校验会因为「格式不合法」而拒绝下载。
     */
    private fun assetDigest(item: JSONObject): String =
        if (item.has("digest") && !item.isNull("digest")) item.getString("digest") else ""

    private fun handleRelease(release: LatestRelease, manual: Boolean) {
        preferences().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        if (!UpdatePolicy.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            if (manual) updateStatus("已是最新版本（${BuildConfig.VERSION_NAME}）")
            return
        }

        OperationLog.record(activity, "发现新版本", "v${release.version}")
        updateStatus("发现新版本 v${release.version}")

        val prefs = preferences()
        val storedUri = prefs.getString(KEY_DOWNLOAD_URI, null)?.let(Uri::parse)
        val storedVersion = prefs.getString(KEY_DOWNLOAD_VERSION, null)
        if (storedUri != null && storedVersion == release.version) {
            // 同一个版本已经下好了，直接问是否安装。
            showInstallPrompt(storedVersion, storedUri)
            return
        }

        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != -1L) {
            updateStatus("更新包正在后台下载")
            onDownloadActive(true)
            return
        }

        showDownloadPrompt(release)
    }

    private fun showDownloadPrompt(release: LatestRelease) {
        if (activity.isFinishing || activity.isDestroyed) return
        val notes = release.notes.ifEmpty { "该版本未填写更新说明。" }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${release.version}")
            .setMessage(notes)
            .setNegativeButton("稍后") { _, _ -> updateStatus("") }
            .setPositiveButton("下载更新") { _, _ -> enqueueDownload(release) }
            .show()
    }

    // ------------------------------------------------------------ 下载

    private fun enqueueDownload(release: LatestRelease) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (directory == null) {
            updateStatus("无法获取下载目录")
            return
        }
        val destination = File(directory, release.asset.name)
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(release.asset.downloadUrl))
            .setTitle("Vesna v${release.version}")
            .setDescription("正在下载更新包")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                release.asset.name,
            )

        val id = runCatching { manager.enqueue(request) }.getOrElse { error ->
            OperationLog.record(activity, "更新包入队失败", error.javaClass.simpleName)
            updateStatus("下载启动失败")
            return
        }

        preferences().edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_DOWNLOAD_VERSION, release.version)
            .putString(KEY_EXPECTED_SHA256, UpdatePolicy.expectedSha256(release.asset.digest))
            .putString(KEY_ASSET_NAME, release.asset.name)
            .putBoolean(KEY_READY_TO_INSTALL, false)
            .apply()

        OperationLog.record(activity, "开始下载更新包", release.asset.name)
        updateStatus("正在后台下载 v${release.version}")
        onDownloadActive(true)
    }

    // ------------------------------------------------------------ 校验与安装

    private fun verifyDownloadedApk() {
        if (validationInProgress) return
        validationInProgress = true
        val prefs = preferences()
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val expectedSha = prefs.getString(KEY_EXPECTED_SHA256, null)
        val expectedVersion = prefs.getString(KEY_DOWNLOAD_VERSION, null)
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        Thread {
            val uri = runCatching { manager.getUriForDownloadedFile(downloadId) }.getOrNull()
            if (uri == null || expectedSha == null || expectedVersion == null) {
                activity.runOnUiThread {
                    validationInProgress = false
                    reportValidationFailure("下载未完成或记录已丢失")
                }
                return@Thread
            }

            val file = File(
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                prefs.getString(KEY_ASSET_NAME, "") ?: "",
            )
            val error = validateApk(file, expectedSha)

            activity.runOnUiThread {
                validationInProgress = false
                if (error != null) {
                    reportValidationFailure(error)
                    return@runOnUiThread
                }
                prefs.edit()
                    .putString(KEY_DOWNLOAD_URI, uri.toString())
                    .putBoolean(KEY_READY_TO_INSTALL, true)
                    .apply()
                onDownloadActive(false)
                OperationLog.record(activity, "更新包校验通过", "v$expectedVersion")
                showInstallPrompt(expectedVersion, uri)
            }
        }.start()
    }

    /** 返回 null 表示校验全部通过；否则返回给用户看的原因。 */
    private fun validateApk(file: File, expectedSha: String): String? {
        if (!file.exists() || file.length() <= 0L) return "下载文件不存在或为空"
        if (!sha256(file).equals(expectedSha, ignoreCase = true)) return "SHA-256 不匹配"

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val candidate = runCatching {
            activity.packageManager.getPackageInfo(file.absolutePath, flags)
        }.getOrNull() ?: return "无法解析下载的安装包"

        if (candidate.packageName != activity.packageName) return "包名不匹配"
        if (longVersionCode(candidate) <= BuildConfig.VERSION_CODE.toLong()) {
            return "versionCode 没有升级"
        }
        val installed = installedPackageInfo() ?: return "无法读取当前应用签名"
        if (signerDigests(candidate) != signerDigests(installed)) return "签名与当前应用不一致"
        return null
    }

    private fun showInstallPrompt(version: String, uri: Uri) {
        if (installPromptShown || activity.isFinishing || activity.isDestroyed) return
        installPromptShown = true
        AlertDialog.Builder(activity)
            .setTitle("更新包已就绪")
            .setMessage("Vesna v$version 已通过校验，可以安装了。")
            .setNegativeButton("稍后") { _, _ ->
                installPromptShown = false
                updateStatus("更新包已就绪，可稍后安装")
            }
            .setPositiveButton("安装") { _, _ ->
                requestInstall(uri)
            }
            .setOnDismissListener { installPromptShown = false }
            .show()
    }

    private fun requestInstall(uri: Uri) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            preferences().edit().putBoolean(KEY_READY_TO_INSTALL, true).apply()
            AlertDialog.Builder(activity)
                .setTitle("需要允许安装未知应用")
                .setMessage("请先允许 Vesna 安装更新包，授权后返回即可继续。")
                .setNegativeButton("取消") { _, _ ->
                    preferences().edit().putBoolean(KEY_READY_TO_INSTALL, false).apply()
                }
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"),
                            ),
                        )
                    }.onFailure { error ->
                        preferences().edit().putBoolean(KEY_READY_TO_INSTALL, false).apply()
                        OperationLog.record(
                            activity,
                            "打开「安装未知应用」设置失败",
                            error.javaClass.simpleName,
                        )
                        updateStatus("无法打开安装授权设置")
                    }
                }
                .show()
            return
        }
        launchInstaller(uri)
    }

    private fun launchInstaller(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { error ->
                OperationLog.record(activity, "打开系统安装器失败", error.javaClass.simpleName)
                updateStatus("无法打开系统安装器")
            }
    }

    private fun reportValidationFailure(message: String) {
        OperationLog.record(activity, "更新包校验失败", message)
        clearPendingDownload()
        if (activity.isFinishing || activity.isDestroyed) return
        updateStatus("更新包校验失败：$message")
        AlertDialog.Builder(activity)
            .setTitle("更新包校验失败")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun clearPendingDownload() {
        onDownloadActive(false)
        preferences().edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_VERSION)
            .remove(KEY_EXPECTED_SHA256)
            .remove(KEY_ASSET_NAME)
            .remove(KEY_DOWNLOAD_URI)
            .remove(KEY_READY_TO_INSTALL)
            .apply()
    }

    // ------------------------------------------------------------ 工具

    private fun installedPackageInfo(): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        activity.packageManager.getPackageInfo(activity.packageName, flags)
    }.getOrNull()

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners.orEmpty()
            } else {
                signingInfo.signingCertificateHistory.orEmpty()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun preferences() =
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun updateStatus(value: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        onStatus(value)
    }

    companion object {
        private const val PREFS_NAME = "vesna_updates"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_VERSION = "download_version"
        private const val KEY_EXPECTED_SHA256 = "expected_sha256"
        private const val KEY_ASSET_NAME = "asset_name"
        private const val KEY_DOWNLOAD_URI = "download_uri"
        private const val KEY_READY_TO_INSTALL = "ready_to_install"
        private const val KEY_UPDATES_ENABLED = "updates_enabled"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_RELEASE_NOTES_CHARS = 1_200
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1_000L

        private val LATEST_RELEASE_API =
            "https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases/latest"
    }
}
