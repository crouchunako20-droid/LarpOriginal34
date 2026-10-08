package com.example.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** type is one of: release, snapshot, old_beta, old_alpha */
data class McVersion(val id: String, val type: String, val url: String)

/**
 * Core for an Android Minecraft Java launcher.
 * Supports every version in Mojang's manifest (releases, snapshots, old beta/alpha)
 * and modded profiles that use "inheritsFrom" (Fabric, Quilt, Forge, NeoForge).
 *
 * Does NOT run the JVM or translate OpenGL. Use PojavLauncher's native pieces for that.
 * Run install functions on a background thread.
 */
object LauncherCore {
    private const val MANIFEST =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val ASSET_HOST = "https://resources.download.minecraft.net/"
    private const val LIB_HOST = "https://libraries.minecraft.net/"

    internal fun getText(url: String) =
        URL(url).openStream().bufferedReader().use { it.readText() }

    internal fun download(url: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        dest.parentFile?.mkdirs()
        URL(url).openStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    /** All versions, or filter with e.g. setOf("release") / setOf("snapshot", "old_beta"). */
    fun listVersions(types: Set<String>? = null): List<McVersion> {
        val arr = JSONObject(getText(MANIFEST)).getJSONArray("versions")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { types == null || it.getString("type") in types }
            .map { McVersion(it.getString("id"), it.getString("type"), it.getString("url")) }
    }

    private fun allowed(lib: JSONObject): Boolean {
        val rules = lib.optJSONArray("rules") ?: return true
        var ok = false
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val os = r.optJSONObject("os")?.optString("name")
            if (os != null && os != "linux") continue
            ok = r.getString("action") == "allow"
        }
        return ok
    }

    /** Returns (relative path, download url or null if it is built locally). */
    private fun libInfo(lib: JSONObject): Pair<String, String?>? {
        val art = lib.optJSONObject("downloads")?.optJSONObject("artifact")
        if (art != null) return art.getString("path") to art.optString("url").ifEmpty { null }
        val parts = lib.optString("name").split(":")
        if (parts.size < 3) return null
        val g = parts[0].replace('.', '/')
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        val path = "$g/${parts[1]}/${parts[2]}/${parts[1]}-${parts[2]}$classifier.jar"
        val base = lib.optString("url", LIB_HOST).let { if (it.endsWith("/")) it else "$it/" }
        return path to (base + path)
    }

    /** Loads versions/<id>/<id>.json and merges its parent chain (inheritsFrom). */
    fun resolve(root: File, id: String): JSONObject {
        val j = JSONObject(File(root, "versions/$id/$id.json").readText())
        val parentId = j.optString("inheritsFrom")
        if (parentId.isEmpty()) return j.put("clientJarId", id)

        val p = resolve(root, parentId)
        val merged = JSONObject(p.toString())
        for (k in j.keys()) {
            if (k != "libraries" && k != "arguments" && k != "inheritsFrom") merged.put(k, j.get(k))
        }
        val libs = JSONArray()
        j.optJSONArray("libraries")?.let { for (i in 0 until it.length()) libs.put(it.get(i)) }
        p.optJSONArray("libraries")?.let { for (i in 0 until it.length()) libs.put(it.get(i)) }
        merged.put("libraries", libs)

        val ca = j.optJSONObject("arguments")
        val pa = p.optJSONObject("arguments")
        if (ca != null && pa != null) {
            val args = JSONObject()
            for (key in listOf("jvm", "game")) {
                val arr = JSONArray()
                pa.optJSONArray(key)?.let { for (i in 0 until it.length()) arr.put(it.get(i)) }
                ca.optJSONArray(key)?.let { for (i in 0 until it.length()) arr.put(it.get(i)) }
                args.put(key, arr)
            }
            merged.put("arguments", args)
        } else if (ca != null) merged.put("arguments", ca)
        merged.put("clientJarId", p.getString("clientJarId"))
        return merged
    }

    /** Installs a vanilla version of any type. Returns the version id. */
    fun installVanilla(root: File, v: McVersion, log: (String) -> Unit): String {
        val f = File(root, "versions/${v.id}/${v.id}.json")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(getText(v.url))
        }
        installResolved(root, v.id, log)
        return v.id
    }

    /** Downloads client jar, libraries and assets for any (vanilla or modded) version id. */
    fun installResolved(root: File, id: String, log: (String) -> Unit): JSONObject {
        val json = resolve(root, id)
        val jarId = json.getString("clientJarId")

        log("Downloading client")
        json.optJSONObject("downloads")?.optJSONObject("client")?.let {
            download(it.getString("url"), File(root, "versions/$jarId/$jarId.jar"))
        }

        log("Downloading libraries")
        val libs = json.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val lib = libs.getJSONObject(i)
            if (!allowed(lib)) continue
            val (path, url) = libInfo(lib) ?: continue
            if (url != null) download(url, File(root, "libraries/$path"))
        }

        log("Downloading assets")
        val idx = json.getJSONObject("assetIndex")
        val idxName = idx.getString("id")
        val idxFile = File(root, "assets/indexes/$idxName.json")
        download(idx.getString("url"), idxFile)
        val idxJson = JSONObject(idxFile.readText())
        val objects = idxJson.getJSONObject("objects")
        val legacy = idxJson.optBoolean("virtual") || idxJson.optBoolean("map_to_resources")
        val pool = Executors.newFixedThreadPool(8)
        objects.keys().forEach { name ->
            val hash = objects.getJSONObject(name).getString("hash")
            val sub = hash.substring(0, 2)
            pool.execute {
                try {
                    val obj = File(root, "assets/objects/$sub/$hash")
                    download("$ASSET_HOST$sub/$hash", obj)
                    if (idxJson.optBoolean("virtual")) {
                        File(root, "assets/virtual/$idxName/$name").let {
                            if (!it.exists()) { it.parentFile?.mkdirs(); obj.copyTo(it) }
                        }
                    }
                    if (idxJson.optBoolean("map_to_resources")) {
                        File(root, "resources/$name").let {
                            if (!it.exists()) { it.parentFile?.mkdirs(); obj.copyTo(it) }
                        }
                    }
                } catch (e: Exception) {
                    log("Failed asset $name: ${e.message}")
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.HOURS)
        log("Done (legacy assets: $legacy)")
        return json
    }

    /** Java major version this game version needs (8, 16, 17, 21...). */
    fun requiredJava(json: JSONObject): Int =
        json.optJSONObject("javaVersion")?.optInt("majorVersion", 8) ?: 8

    /** Version ids installed in this game folder (vanilla and modded). */
    fun installedVersions(root: File): List<String> =
        File(root, "versions").listFiles { f -> File(f, "${f.name}.json").exists() }
            ?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Builds the argument list for the JVM, for any version id (vanilla or modded).
     * Pass the result to your native JVM launcher.
     */
    fun buildCommand(
        root: File, id: String,
        username: String, uuid: String, accessToken: String,
        ramMb: Int = 1024,
        demo: Boolean = false
    ): List<String> {
        val json = resolve(root, id)
        val jarId = json.getString("clientJarId")
        val idxName = json.getJSONObject("assetIndex").getString("id")

        val cp = LinkedHashSet<String>()
        val libs = json.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val lib = libs.getJSONObject(i)
            if (!allowed(lib)) continue
            val (path, _) = libInfo(lib) ?: continue
            if (path.contains("natives")) continue
            cp += File(root, "libraries/$path").absolutePath
        }
        cp += File(root, "versions/$jarId/$jarId.jar").absolutePath
        val classpath = cp.joinToString(":")

        val vars = mapOf(
            "auth_player_name" to username,
            "version_name" to id,
            "game_directory" to root.absolutePath,
            "assets_root" to File(root, "assets").absolutePath,
            "game_assets" to File(root, "assets/virtual/$idxName").absolutePath,
            "assets_index_name" to idxName,
            "auth_uuid" to uuid,
            "auth_access_token" to accessToken,
            "auth_session" to "token:$accessToken:$uuid",
            "user_type" to "msa",
            "user_properties" to "{}",
            "version_type" to json.optString("type", "release"),
            "classpath" to classpath,
            "classpath_separator" to ":",
            "library_directory" to File(root, "libraries").absolutePath,
            "natives_directory" to File(root, "natives").absolutePath,
            "launcher_name" to "MyLauncher",
            "launcher_version" to "1.0"
        )
        fun fill(s: String) = Regex("\\$\\{(\\w+)}").replace(s) { vars[it.groupValues[1]] ?: "" }

        val jvm = mutableListOf("-Xmx${ramMb}M")
        val game = mutableListOf<String>()

        val args = json.optJSONObject("arguments")
        if (args != null) { // 1.13+ and modern loaders
            args.optJSONArray("jvm")?.let { a ->
                for (i in 0 until a.length()) (a.get(i) as? String)?.let { jvm += fill(it) }
            }
            args.optJSONArray("game")?.let { a ->
                for (i in 0 until a.length()) (a.get(i) as? String)?.let { game += fill(it) }
            }
        } else { // 1.12 and older
            game += json.getString("minecraftArguments").split(" ").map { fill(it) }
        }
        if ("-cp" !in jvm && "-classpath" !in jvm) { jvm += "-cp"; jvm += classpath }
        if (demo) game += "--demo"

        return jvm + json.getString("mainClass") + game
    }
}
