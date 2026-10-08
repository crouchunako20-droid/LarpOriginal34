package com.example.launcher

import org.json.JSONObject
import java.io.File

/** Remembers the last successful Microsoft login so singleplayer works without internet. */
object AccountStore {
    fun save(root: File, s: MicrosoftAuth.Session) {
        root.mkdirs()
        File(root, "account.json").writeText(
            JSONObject().put("name", s.username).put("uuid", s.uuid).toString()
        )
    }

    /** Returns null if nobody has logged in yet. Token is "0": offline/LAN only. */
    fun offlineSession(root: File): MicrosoftAuth.Session? {
        val f = File(root, "account.json")
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        return MicrosoftAuth.Session(j.getString("name"), j.getString("uuid"), "0")
    }
}
