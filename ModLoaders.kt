package com.example.launcher

import org.json.JSONObject
import java.io.File

enum class Loader { FABRIC, QUILT, FORGE, NEOFORGE }

/**
 * Mod loader installs. Run on a background thread.
 *
 * Fabric and Quilt: fully handled here (they publish ready-made version profiles).
 * Forge and NeoForge: this downloads the official installer jar; you then run it
 * with your bundled Java:  java -jar <installer> --installClient <gameDir>
 * and read the new version id with LauncherCore.installedVersions() (diff before/after).
 * Then call LauncherCore.installResolved() on that id to fetch any missing libraries.
 */
object ModLoaders {

    /** Loader versions available for one Minecraft version (newest first). */
    fun loaderVersions(loader: Loader, mc: String): List<String> = when (loader) {
        Loader.FABRIC -> metaVersions("https://meta.fabricmc.net/v2/versions/loader/$mc")
        Loader.QUILT -> metaVersions("https://meta.quiltmc.org/v3/versions/loader/$mc")
        Loader.FORGE -> forgeVersions(mc)
        Loader.NEOFORGE -> neoForgeVersions(mc)
    }

    private fun metaVersions(url: String): List<String> {
        val arr = org.json.JSONArray(LauncherCore.getText(url))
        return (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONObject("loader").getString("version")
        }
    }

    private fun forgeVersions(mc: String): List<String> {
        val promos = JSONObject(
            LauncherCore.getText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
        ).getJSONObject("promos")
        return listOf("latest", "recommended")
            .mapNotNull { promos.optString("$mc-$it").ifEmpty { null } }
            .distinct()
    }

    private fun neoForgeVersions(mc: String): List<String> {
        // MC 1.20.4 -> 20.4.x, MC 1.21 -> 21.0.x, MC 1.21.1 -> 21.1.x (1.20.2 and newer only)
        val p = mc.removePrefix("1.").split(".")
        val prefix = "${p[0]}.${p.getOrElse(1) { "0" }}."
        val arr = JSONObject(
            LauncherCore.getText("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")
        ).getJSONArray("versions")
        return (0 until arr.length()).map { arr.getString(it) }
            .filter { it.startsWith(prefix) }.reversed()
    }

    /** Fabric or Quilt. Installs vanilla first, then the loader. Returns the id to launch. */
    fun installFabricLike(
        root: File, loader: Loader, mc: McVersion, loaderVersion: String,
        log: (String) -> Unit
    ): String {
        require(loader == Loader.FABRIC || loader == Loader.QUILT)
        LauncherCore.installVanilla(root, mc, log)
        val url = when (loader) {
            Loader.FABRIC -> "https://meta.fabricmc.net/v2/versions/loader/${mc.id}/$loaderVersion/profile/json"
            else -> "https://meta.quiltmc.org/v3/versions/loader/${mc.id}/$loaderVersion/profile/json"
        }
        val text = LauncherCore.getText(url)
        val id = JSONObject(text).getString("id")
        File(root, "versions/$id/$id.json").apply { parentFile?.mkdirs(); writeText(text) }
        LauncherCore.installResolved(root, id, log)
        return id
    }

    /** Forge or NeoForge. Installs vanilla, downloads the installer, returns its file. */
    fun downloadInstaller(
        root: File, loader: Loader, mc: McVersion, loaderVersion: String,
        log: (String) -> Unit
    ): File {
        require(loader == Loader.FORGE || loader == Loader.NEOFORGE)
        LauncherCore.installVanilla(root, mc, log)
        val url = if (loader == Loader.FORGE)
            "https://maven.minecraftforge.net/net/minecraftforge/forge/${mc.id}-$loaderVersion/forge-${mc.id}-$loaderVersion-installer.jar"
        else
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar"
        val dest = File(root, "installers/${url.substringAfterLast('/')}")
        LauncherCore.download(url, dest)
        // Installers expect this file to exist in the game folder
        File(root, "launcher_profiles.json").apply { if (!exists()) writeText("{\"profiles\":{}}") }
        return dest
    }

    /** Java arguments to run the installer with your bundled JRE. */
    fun installerArgs(installer: File, root: File) =
        listOf("-jar", installer.absolutePath, "--installClient", root.absolutePath)
}

/* Usage (background thread):
 *
 *   val root = File(filesDir, "minecraft")
 *   val all = LauncherCore.listVersions()                  // every version
 *   val mc  = all.first { it.id == "1.20.1" }
 *
 *   // Fabric
 *   val lv = ModLoaders.loaderVersions(Loader.FABRIC, mc.id).first()
 *   val id = ModLoaders.installFabricLike(root, Loader.FABRIC, mc, lv) { Log.d("L", it) }
 *   val cmd = LauncherCore.buildCommand(root, id, s.username, s.uuid, s.accessToken)
 *
 *   // Forge
 *   val before = LauncherCore.installedVersions(root)
 *   val jar = ModLoaders.downloadInstaller(root, Loader.FORGE, mc, "47.2.0") { }
 *   // run: java ModLoaders.installerArgs(jar, root) with your bundled JRE, then:
 *   val newId = (LauncherCore.installedVersions(root) - before.toSet()).first()
 *   LauncherCore.installResolved(root, newId) { }
 */
