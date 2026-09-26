package com.littletaro.vesna.update

/** GitHub Release 里的一个下载资产。 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String,
)

/**
 * 更新判定规则（纯函数，无 Android 依赖，可直接单测）。
 *
 * 资产必须同时满足三个条件才会被采用，缺一不可：
 * 1. 文件名严格等于 `vesna-v<版本>-debug.apk` 或 `-release.apk`；
 * 2. 下载地址确实指向本仓库的 releases/download 路径；
 * 3. Release 里给出了合法格式的 SHA-256。
 *
 * 这样即使仓库被投毒、或有人伪造同名资产，也不会被下载安装。
 */
object UpdatePolicy {

    /** 发布仓库。改仓库名时只改这一处。 */
    const val REPOSITORY = "littletaro97-arch/Vesna"

    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$")
    private val sha256Pattern = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** 把 `v1.2` / `1.2.0` 归一到 `1.2` / `1.2.0`；不合规则返回 null。 */
    fun normalizedVersion(tag: String): String? {
        val match = versionPattern.matchEntire(tag.trim()) ?: return null
        return listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            .filter { it.isNotEmpty() }
            .joinToString(".")
    }

    /** 候选版本是否比当前版本更新（逐段比较，缺位补 0）。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = versionParts(candidate) ?: return false
        val currentParts = versionParts(current) ?: return false
        val width = maxOf(candidateParts.size, currentParts.size)
        return (0 until width)
            .map { (candidateParts.getOrNull(it) ?: 0) - (currentParts.getOrNull(it) ?: 0) }
            .firstOrNull { it != 0 }
            ?.let { it > 0 } == true
    }

    fun acceptedAssetNames(version: String): Set<String> = setOf(
        "vesna-v$version-debug.apk",
        "vesna-v$version-release.apk",
    )

    /** 从资产清单里挑出唯一合法的 APK；不合法或存在歧义时返回 null。 */
    fun selectApk(version: String, assets: List<ReleaseAsset>): ReleaseAsset? {
        val accepted = acceptedAssetNames(version)
        val matches = assets.filter { it.name in accepted }
        return matches.singleOrNull()?.takeIf {
            it.size > 0 &&
                it.downloadUrl.startsWith(
                    "https://github.com/$REPOSITORY/releases/download/",
                ) &&
                expectedSha256(it.digest) != null
        }
    }

    /** 解析 `sha256:<64 位十六进制>`；格式不对返回 null。 */
    fun expectedSha256(digest: String): String? =
        sha256Pattern.matchEntire(digest.trim())?.groupValues?.get(1)?.lowercase()

    private fun versionParts(value: String): List<Int>? {
        val normalized = normalizedVersion(value) ?: return null
        return normalized.split('.').map { it.toInt() }
    }
}
