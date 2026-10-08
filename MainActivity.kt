package com.example.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File

/** Replace the body with a call into PojavLauncher's native JVM launcher. */
object NativeLauncher {
    fun start(command: List<String>, javaMajor: Int, log: (String) -> Unit) {
        // TODO: start the bundled Java $javaMajor JRE with `command` (JNI / separate process).
        log("Ready to launch with Java $javaMajor:\n" + command.joinToString(" "))
    }
}

/**
 * Whole UI built in code, so there is no XML to set up.
 * Manifest needs: <uses-permission android:name="android.permission.INTERNET"/>
 * and this class declared as the launcher <activity>.
 */
class MainActivity : Activity() {
    private lateinit var root: File
    private lateinit var logView: TextView
    private lateinit var accountText: TextView
    private lateinit var versionSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var loaderSpinner: Spinner
    private lateinit var playBtn: Button

    private var allVersions = listOf<McVersion>()
    private var shown = listOf<McVersion>()
    private var session: MicrosoftAuth.Session? = null

    private val typeNames = listOf("Release", "Snapshot", "Old beta", "Old alpha", "All")
    private val typeFilters: List<Set<String>?> = listOf(
        setOf("release"), setOf("snapshot"), setOf("old_beta"), setOf("old_alpha"), null
    )
    private val loaderNames = listOf("Vanilla", "Fabric", "Quilt", "Forge", "NeoForge")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = File(filesDir, "minecraft").apply { mkdirs() }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }
        fun spinner(items: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items
            )
        }

        accountText = TextView(this).apply {
            text = AccountStore.offlineSession(root)?.let { "Saved account: ${it.username}" }
                ?: "Not signed in (Play will start Demo mode)"
        }
        val loginBtn = Button(this).apply { text = "Sign in with Microsoft"; setOnClickListener { login() } }

        typeSpinner = spinner(typeNames)
        versionSpinner = spinner(emptyList())
        loaderSpinner = spinner(loaderNames)
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshVersions()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        playBtn = Button(this).apply { text = "Install & Play"; setOnClickListener { play() } }
        logView = TextView(this).apply { textSize = 12f }

        col.addView(accountText)
        col.addView(loginBtn)
        col.addView(label("Version type")); col.addView(typeSpinner)
        col.addView(label("Minecraft version")); col.addView(versionSpinner)
        col.addView(label("Mod loader")); col.addView(loaderSpinner)
        col.addView(playBtn)
        col.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)

        loadVersions()
    }

    private fun logLine(s: String) = runOnUiThread { logView.append(s + "\n") }

    private fun loadVersions() = Thread {
        allVersions = try {
            LauncherCore.listVersions()
        } catch (e: Exception) {
            logLine("No internet. Showing installed versions only.")
            LauncherCore.installedVersions(root).map { McVersion(it, "installed", "") }
        }
        runOnUiThread { refreshVersions() }
    }.start()

    private fun refreshVersions() {
        val filter = typeFilters[typeSpinner.selectedItemPosition]
        shown = allVersions.filter { filter == null || it.type in filter || it.type == "installed" }
        versionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, shown.map { it.id }
        )
    }

    private fun login() = Thread {
        try {
            val d = MicrosoftAuth.startLogin()
            logLine("Open ${d.verificationUri} and enter code: ${d.userCode}")
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.verificationUri))) }
            val s = MicrosoftAuth.finishLogin(d)
            AccountStore.save(root, s)
            session = s
            runOnUiThread { accountText.text = "Signed in: ${s.username}" }
        } catch (e: Exception) {
            logLine("Login failed: ${e.message}")
        }
    }.start()

    private fun play() {
        val v = shown.getOrNull(versionSpinner.selectedItemPosition) ?: return
        val loaderIdx = loaderSpinner.selectedItemPosition
        playBtn.isEnabled = false

        Thread {
            try {
                // Modded: reuse an already installed profile (works offline), else install it
                val existing = if (loaderIdx == 0) null else
                    LauncherCore.installedVersions(root).firstOrNull {
                        it.contains(v.id) && it.lowercase().contains(loaderNames[loaderIdx].lowercase())
                    }

                val id: String = when {
                    existing != null -> existing
                    loaderIdx == 0 -> LauncherCore.installVanilla(root, v) { logLine(it) }
                    loaderIdx <= 2 -> {
                        val l = if (loaderIdx == 1) Loader.FABRIC else Loader.QUILT
                        val lv = ModLoaders.loaderVersions(l, v.id).firstOrNull()
                            ?: throw IllegalStateException("${loaderNames[loaderIdx]} has no build for ${v.id}")
                        ModLoaders.installFabricLike(root, l, v, lv) { logLine(it) }
                    }
                    else -> {
                        val l = if (loaderIdx == 3) Loader.FORGE else Loader.NEOFORGE
                        val lv = ModLoaders.loaderVersions(l, v.id).firstOrNull()
                            ?: throw IllegalStateException("${loaderNames[loaderIdx]} has no build for ${v.id}")
                        val jar = ModLoaders.downloadInstaller(root, l, v, lv) { logLine(it) }
                        logLine("Installer downloaded. Run it with your bundled Java:")
                        logLine("java " + ModLoaders.installerArgs(jar, root).joinToString(" "))
                        logLine("Then press Install & Play again.")
                        return@Thread
                    }
                }

                val json = LauncherCore.resolve(root, id)
                val acc = session ?: AccountStore.offlineSession(root)
                val cmd = LauncherCore.buildCommand(
                    root, id,
                    acc?.username ?: "Player", acc?.uuid ?: "0", acc?.accessToken ?: "0",
                    demo = acc == null
                )
                if (acc == null) logLine("No account: starting Demo mode.")
                else if (session == null) logLine("Offline play as ${acc.username}.")
                NativeLauncher.start(cmd, LauncherCore.requiredJava(json)) { logLine(it) }
            } catch (e: Exception) {
                logLine("Error: ${e.message}")
            } finally {
                runOnUiThread { playBtn.isEnabled = true }
            }
        }.start()
    }
}
