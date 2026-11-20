package com.yuno.desktop.account

import com.yuno.desktop.security.DesktopKeyring
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object DesktopAccount {
    private const val COOKIE_KEY = "youtube-session"
    private const val ORIGIN = "https://www.youtube.com"
    private val cookies = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    fun isSignedIn(): Boolean {
        load()
        return cookies.containsKey("SAPISID") && cookies.containsKey("LOGIN_INFO")
    }

    fun cookieHeader(): String {
        load()
        return cookies.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun authorization(): String? {
        load()
        val timestamp = System.currentTimeMillis() / 1000
        fun hash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$timestamp $secret $ORIGIN".toByteArray(Charsets.UTF_8))
            return timestamp.toString() + "_" + digest.joinToString("") { "%02x".format(it) }
        }
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
        return buildString {
            append("SAPISIDHASH ").append(hash(sapisid))
            cookies["__Secure-1PAPISID"]?.let { append(" SAPISID1PHASH ").append(hash(it)) }
            cookies["__Secure-3PAPISID"]?.let { append(" SAPISID3PHASH ").append(hash(it)) }
        }
    }

    fun importCookieHeader(header: String) {
        val parsed = parse(header)
        require("SAPISID" in parsed || "__Secure-3PAPISID" in parsed) {
            "The cookie header does not contain a SAPISID cookie."
        }
        require("LOGIN_INFO" in parsed) {
            "The cookie header does not contain LOGIN_INFO. Make sure it came from a signed-in YouTube session."
        }
        DesktopKeyring.put(COOKIE_KEY, header.trim())
        cookies.clear()
        cookies.putAll(parsed)
        loaded = true
    }

    fun signOut() {
        DesktopKeyring.delete(COOKIE_KEY)
        cookies.clear()
        loaded = true
    }

    fun absorbSetCookies(headers: List<String>) {
        load()
        var changed = false
        headers.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty() && cookies.put(name, value) != value) changed = true
        }
        if (changed) DesktopKeyring.put(COOKIE_KEY, cookieHeader())
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            DesktopKeyring.get(COOKIE_KEY)?.let { cookies.putAll(parse(it)) }
            loaded = true
        }
    }

    private fun parse(header: String): Map<String, String> = buildMap {
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
        }
    }
}
