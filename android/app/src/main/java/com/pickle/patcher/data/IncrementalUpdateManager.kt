package com.pickle.patcher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages incremental updates using GitHub Releases API.
 * Compares file sizes between release assets and local libs/ directory.
 * No manifest.json needed — just size comparison.
 */
object IncrementalUpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ReleaseAsset(
        val name: String = "",
        val size: Long = 0,
        val browser_download_url: String = "",
    )

    @Serializable
    data class ReleaseInfo(
        val tag_name: String = "",
        val assets: List<ReleaseAsset> = emptyList(),
    )

    @Serializable
    data class ManifestInfo(
        val version: String = "",
        val abi: String = "",
        val files: List<ManifestFile> = emptyList(),
    ) {
        @Serializable
        data class ManifestFile(
            val name: String = "",
            val asset: String = "",
            val sha256: String = "",
            val build: String = "",
            val size: Long = 0,
        )
    }

    data class AssetInfo(
        val assetName: String,
        val cleanName: String,
        val size: Long,
        val downloadUrl: String,
        val sha256: String = "",
        val build: String = "",
    )

    data class UpdateResult(
        val toDownload: List<AssetInfo>,
        val upToDate: List<AssetInfo>,
        val totalBytes: Long,
    )

    private const val REPO = "berkchy/nexora"

    /**
     * Fetch release assets for the current ABI and return AssetInfo with clean
     * names (stripped ABI prefix). Primary source is the small per-ABI
     * manifest.json that CI uploads with every release — a direct
     * releases/download URL that costs zero API quota. Falls back to the
     * GitHub API only if the manifest cannot be fetched.
     */
    suspend fun fetchReleaseAssets(tag: String, abi: String): List<AssetInfo> {
        val manifestName = if (abi == "arm64-v8a") "manifest.json" else "manifest-v7a.json"
        val manifest = try {
            fetchManifestAssets(tag, manifestName)
        } catch (_: Throwable) {
            null
        }
        if (manifest != null) return manifest
        return fetchReleaseAssetsApi(tag, abi)
    }

    private suspend fun fetchManifestAssets(tag: String, manifestName: String): List<AssetInfo>? {
        val url = ReleaseRepository.assetUrl(REPO, tag, manifestName)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "cs16-amxx-patcher")
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val manifest = json.decodeFromString<ManifestInfo>(resp.body?.string().orEmpty())
            manifest.files.map { f ->
                AssetInfo(
                    assetName = f.asset,
                    cleanName = f.name,
                    size = f.size,
                    downloadUrl = ReleaseRepository.assetUrl(REPO, tag, f.asset),
                    sha256 = f.sha256,
                    build = f.build,
                )
            }
        }
    }

    private suspend fun fetchReleaseAssetsApi(tag: String, abi: String): List<AssetInfo> {
        val url = "https://api.github.com/repos/$REPO/releases/tags/$tag"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "cs16-amxx-patcher")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val release = json.decodeFromString<ReleaseInfo>(body)
                release.assets
                    .filter { it.name.startsWith("${abi}__") && it.name.endsWith(".so") }
                    .map { asset ->
                        val cleanName = asset.name.removePrefix("${abi}__")
                        AssetInfo(
                            assetName = asset.name,
                            cleanName = cleanName,
                            size = asset.size,
                            downloadUrl = asset.browser_download_url,
                        )
                    }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Compare release assets against local files on disk.
     *
     * The comparison is the build stamp the manifest carries, looked up in
     * appliedBuilds - what this phone last wrote to disk. That is what the
     * release says produced the bytes, and comparing two short strings costs
     * nothing.
     *
     * Size is only the fallback, for files this phone has never stamped: a
     * fresh install has no history. It is not good enough on its own, and the
     * reason is worth keeping in mind: libcs_android_arm64.so rebuilt with a
     * handful of HUD instructions came out at exactly 3619064 bytes, the same
     * as the build before it, so a size check reported it as current and the
     * phone ran the old game dll for a week. Size can only prove a file is
     * stale when it also grew or shrank; a rebuild that lands on the same
     * length is invisible to it.
     */
    fun diff(
        releaseAssets: List<AssetInfo>,
        libsDir: File,
        abi: String,
        appliedBuilds: Map<String, String> = emptyMap(),
    ): UpdateResult {
        val targetDir = File(libsDir, abi)
        val toDownload = mutableListOf<AssetInfo>()
        val upToDate = mutableListOf<AssetInfo>()

        for (asset in releaseAssets) {
            val fileOnDisk = File(targetDir, asset.cleanName)
            if (isUpToDate(fileOnDisk, asset, appliedBuilds[asset.cleanName])) {
                upToDate.add(asset)
            } else {
                toDownload.add(asset)
            }
        }

        val totalBytes = toDownload.sumOf { it.size }
        return UpdateResult(toDownload = toDownload, upToDate = upToDate, totalBytes = totalBytes)
    }

    /**
     * True when the local file is the build the release is offering.
     *
     * Three tiers, cheapest first:
     *   1. build stamps, both sides known - two short strings, free
     *   2. size differs - stale, no reading needed
     *   3. size matches and the manifest has a sha256 but this phone never
     *      stamped the file: hash it once and compare
     *
     * Tier 3 is the bootstrap. A phone upgrading from the size-only build has
     * no stamp for anything, so every library would fall through to size and
     * the one that most needs replacing - the game dll, rebuilt to exactly the
     * same length - would be called current all over again. Hashing 150 MB
     * once, on the first check after the update, is the price of not shipping
     * a stale client again.
     */
    fun isUpToDate(
        fileOnDisk: File,
        asset: AssetInfo,
        appliedBuild: String? = null,
    ): Boolean {
        if (!fileOnDisk.exists()) return false

        val want = asset.build
        val have = appliedBuild
        if (!want.isNullOrEmpty() && !have.isNullOrEmpty())
            return want == have

        if (fileOnDisk.length() != asset.size) return false

        val sha = asset.sha256
        if (sha.isEmpty() || have != null) return true

        return sha256Of(fileOnDisk) == sha
    }

    /** Streaming sha256 of a local file, lowercase hex. Empty if unreadable. */
    fun sha256Of(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Download only files that need updating.
     * Saves with clean name (no ABI prefix) — files are in ABI subdirectory.
     */
    suspend fun downloadChanged(
        toDownload: List<AssetInfo>,
        libsDir: File,
        abi: String,
        onFileStart: (index: Int, asset: AssetInfo) -> Unit = { _, _ -> },
        onFileProgress: (index: Int, asset: AssetInfo, progress: Float) -> Unit = { _, _, _ -> },
        onProgress: (downloaded: Int, total: Int, bytesWritten: Long) -> Unit = { _, _, _ -> },
    ) {
        val targetDir = File(libsDir, abi)
        targetDir.mkdirs()

        for ((index, asset) in toDownload.withIndex()) {
            onFileStart(index, asset)

            val destFile = File(targetDir, asset.cleanName)
            destFile.parentFile?.mkdirs()

            val req = Request.Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", "cs16-amxx-patcher")
                .header("Accept", "application/octet-stream")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val hint = if (resp.code == 403) {
                        val wait = resp.header("Retry-After")
                            ?.trim()?.toLongOrNull()
                        if (wait != null && wait >= 60) " — wait ${wait / 60} min" else ""
                    } else ""
                    throw IllegalStateException(
                        "Download failed for ${asset.assetName}: ${resp.code}$hint"
                    )
                }
                val body = resp.body ?: throw IllegalStateException("Empty body for ${asset.assetName}")
                val expectedSize = asset.size.takeIf { it > 0 }
                    ?: body.contentLength().takeIf { it > 0 }
                    ?: 0L
                var written = 0L
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            if (expectedSize > 0) {
                                onFileProgress(index, asset, (written.toFloat() / expectedSize).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                destFile.setExecutable(true)
            }

            onProgress(index + 1, toDownload.size, toDownload.take(index + 1).sumOf { it.size })
        }
    }

    /**
     * Download a single file from the release.
     */
    suspend fun downloadSingle(
        asset: AssetInfo,
        libsDir: File,
        abi: String,
        onProgress: (Float) -> Unit = {},
    ) {
        val targetDir = File(libsDir, abi)
        targetDir.mkdirs()

        val destFile = File(targetDir, asset.cleanName)
        destFile.parentFile?.mkdirs()

        val req = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", "cs16-amxx-patcher")
            .header("Accept", "application/octet-stream")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val hint = if (resp.code == 403) {
                    val wait = resp.header("Retry-After")
                        ?.trim()?.toLongOrNull()
                    if (wait != null && wait >= 60) " — wait ${wait / 60} min" else ""
                } else ""
                throw IllegalStateException(
                    "Download failed for ${asset.assetName}: ${resp.code}$hint"
                )
            }
            val body = resp.body ?: throw IllegalStateException("Empty body for ${asset.assetName}")
            val expectedSize = asset.size.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }
                ?: 0L
            var written = 0L
            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (expectedSize > 0) {
                            onProgress((written.toFloat() / expectedSize).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            destFile.setExecutable(true)
        }
    }

    /**
     * Map downloaded .so files to their APK target paths WITHOUT reading
     * their bytes (low-RAM safe: ~19 lib .so files total 100MB+ would OOM a
     * 201MB-heap device). Callers build a disk-backed Bundle whose
     * resolveEntry() streams one file at a time from disk during patching.
     */
    fun loadBundleFileMap(libsDir: File, abi: String): Map<String, File> {
        val targetDir = File(libsDir, abi)
        if (!targetDir.exists()) return emptyMap()

        val suffix = if (abi == "arm64-v8a") "arm64" else "armv7l"
        val modSuffix = if (abi == "arm64-v8a") "amd64" else "arm"

        val files = HashMap<String, File>()
        targetDir.listFiles()?.filter {
            it.isFile && it.extension == "so"
        }?.forEach { file ->
            val name = file.name
            val targetPath = getTargetPath(name, abi, suffix, modSuffix)
            if (targetPath != null) {
                files[targetPath] = file
            }
        }
        return files
    }

    /**
     * Map a clean .so filename to its target path in the APK.
     */
    private fun getTargetPath(name: String, abi: String, suffix: String, modSuffix: String): String? {
        return when {
            name == "libmetamod.so" -> "lib/$abi/libmetamod_android_$suffix.so"
            name.startsWith("lib") && name.endsWith(".so") -> "lib/$abi/$name"
            name.endsWith(".so") -> "lib/$abi/lib$name"
            else -> null
        }
    }
}
