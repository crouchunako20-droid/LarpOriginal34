package com.example.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Microsoft -> Xbox Live -> XSTS -> Minecraft login using the device code flow
 * (works well on phones: show the code, user signs in on any browser).
 *
 * Setup: register a free app at https://portal.azure.com (App registrations),
 * set "Allow public client flows" to Yes, supported accounts to
 * "Personal Microsoft accounts", then paste its Application (client) ID below.
 * Run everything on a background thread.
 */
object MicrosoftAuth {
    const val CLIENT_ID = "YOUR-AZURE-APP-CLIENT-ID"
    private const val TENANT = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

    data class DeviceCode(
        val userCode: String, val verificationUri: String,
        val deviceCode: String, val interval: Int
    )

    data class Session(val username: String, val uuid: String, val accessToken: String)

    private fun request(
        url: String, method: String = "POST", body: String? = null,
        contentType: String = "application/json", bearer: String? = null
    ): Pair<Int, JSONObject> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Accept", "application/json")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return code to JSONObject(text.ifBlank { "{}" })
    }

    private fun form(vararg p: Pair<String, String>) =
        p.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

    /** Step 1: show userCode and verificationUri to the player. */
    fun startLogin(): DeviceCode {
        val (_, j) = request(
            "$TENANT/devicecode",
            body = form("client_id" to CLIENT_ID, "scope" to "XboxLive.signin offline_access"),
            contentType = "application/x-www-form-urlencoded"
        )
        return DeviceCode(
            j.getString("user_code"), j.getString("verification_uri"),
            j.getString("device_code"), j.optInt("interval", 5)
        )
    }

    /** Step 2: call after showing the code; blocks until the player signs in. */
    fun finishLogin(d: DeviceCode): Session {
        var msToken: String? = null
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        while (msToken == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(d.interval * 1000L)
            val (_, j) = request(
                "$TENANT/token",
                body = form(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to CLIENT_ID,
                    "device_code" to d.deviceCode
                ),
                contentType = "application/x-www-form-urlencoded"
            )
            when (j.optString("error")) {
                "" -> msToken = j.getString("access_token")
                "authorization_pending", "slow_down" -> {}
                else -> error("Login failed: ${j.optString("error_description")}")
            }
        }
        if (msToken == null) error("Login timed out")
        return minecraftSession(msToken)
    }

    private fun minecraftSession(msToken: String): Session {
        // Xbox Live
        val xbl = JSONObject()
            .put("Properties", JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=$msToken"))
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType", "JWT")
        val (_, xblRes) = request("https://user.auth.xboxlive.com/user/authenticate", body = xbl.toString())
        val xblToken = xblRes.getString("Token")

        // XSTS
        val xsts = JSONObject()
            .put("Properties", JSONObject()
                .put("SandboxId", "RETAIL")
                .put("UserTokens", JSONArray().put(xblToken)))
            .put("RelyingParty", "rp://api.minecraftservices.com/")
            .put("TokenType", "JWT")
        val (xCode, xstsRes) = request("https://xsts.auth.xboxlive.com/xsts/authorize", body = xsts.toString())
        if (xCode != 200) error("Xbox sign-in failed (XErr ${xstsRes.optLong("XErr")}). The account may have no Xbox profile or be a child account.")
        val xstsToken = xstsRes.getString("Token")
        val uhs = xstsRes.getJSONObject("DisplayClaims").getJSONArray("xui")
            .getJSONObject(0).getString("uhs")

        // Minecraft
        val mc = JSONObject().put("identityToken", "XBL3.0 x=$uhs;$xstsToken")
        val (_, mcRes) = request("https://api.minecraftservices.com/authentication/login_with_xbox", body = mc.toString())
        val mcToken = mcRes.getString("access_token")

        // Profile (404 means the account doesn't own Minecraft Java)
        val (pCode, profile) = request(
            "https://api.minecraftservices.com/minecraft/profile",
            method = "GET", bearer = mcToken
        )
        if (pCode != 200) error("This account doesn't own Minecraft: Java Edition.")
        return Session(profile.getString("name"), profile.getString("id"), mcToken)
    }
}

/* Usage (background thread):
 *   val code = MicrosoftAuth.startLogin()
 *   // show: "Go to ${code.verificationUri} and enter ${code.userCode}"
 *   val s = MicrosoftAuth.finishLogin(code)
 *   val cmd = LauncherCore.buildCommand(root, id, s.username, s.uuid, s.accessToken)
 *
 * Demo mode (no account needed):
 *   LauncherCore.buildCommand(root, id, "Player", "0", "0", demo = true)
 */
