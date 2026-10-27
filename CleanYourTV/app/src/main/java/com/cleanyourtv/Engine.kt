package com.cleanyourtv

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Action(val label: String) {
    SUSPEND("Suspend (reversible)"),
    DISABLE("Disable (reversible)"),
    UNINSTALL("Uninstall for user 0 (restorable)")
}

/** Packages that must never be touched, on any TV. */
object Guard {
    private val exact = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.shell",
        "com.android.vending", "com.android.tv.settings", "com.android.inputdevices",
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.tv",
        "com.google.android.katniss", "com.amazon.tv.launcher", "com.amazon.firelauncher",
        "com.cleanyourtv"
    )
    private val prefixes = listOf(
        "com.android.providers.", "com.android.bluetooth", "com.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.inputmethod",
        "com.google.android.inputmethod", "com.android.networkstack", "com.android.wifi",
        "com.android.server.", "com.android.phone", "com.android.keychain"
    )
    fun isProtected(p: String) = p in exact || prefixes.any { p.startsWith(it) }
}

/** Conservative built-in list. Extend it with your own file via "Import list". */
object Presets {
    val generic = setOf(
        "com.google.android.videos",
        "com.google.android.youtube.tvmusic",
        "com.google.android.play.games",
        "com.google.android.feedback"
    )
    val packageRegex = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    fun parse(text: String): Set<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
        .filter { packageRegex.matches(it) }.toSet()
}

class TvAdb(private val keyDir: File) {
    private var dadb: Dadb? = null

    private fun keys(): AdbKeyPair {
        val priv = File(keyDir, "adbkey"); val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    fun connect(host: String, port: Int = 5555) {
        close(); dadb = Dadb.create(host, port, keys())
    }
    fun close() { runCatching { dadb?.close() }; dadb = null }
    fun resetKeys() { close(); File(keyDir, "adbkey").delete(); File(keyDir, "adbkey.pub").delete() }

    /** Plain TCP check first, so failures are explained instead of silent. Returns null when the port is open. */
    fun probe(host: String, port: Int): String? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 4000) }; null
    } catch (e: SocketTimeoutException) {
        "Timeout: no answer from $host:$port. Check the IP, that phone and TV are on the same Wi-Fi (not mobile data), and that the router has no client isolation."
    } catch (e: ConnectException) {
        "Refused on $host:$port. Network ADB is off or uses another port. Enable ADB/Network debugging on the TV; if there is no such switch, run once from a PC: adb tcpip 5555"
    } catch (e: UnknownHostException) {
        "Invalid IP address: $host"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    fun sh(cmd: String): Pair<Boolean, String> {
        val r = (dadb ?: error("Not connected")).shell(cmd)
        val out = r.allOutput.trim()
        val bad = out.contains("Exception") || out.contains("Failure") || out.contains("Error")
        return (r.exitCode == 0 && !bad) to out
    }

    fun deviceInfo(): String {
        val m = sh("getprop ro.product.manufacturer").second
        val d = sh("getprop ro.product.model").second
        return "$m $d"
    }

    fun installed(): List<String> = sh("pm list packages --user 0").second.lines()
        .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.sorted()

    fun thirdParty(): Set<String> = sh("pm list packages -3 --user 0").second.lines()
        .map { it.removePrefix("package:").trim() }.toSet()

    fun apply(p: String, a: Action): Boolean = when (a) {
        Action.SUSPEND -> sh("pm suspend --user 0 $p").first || sh("pm disable-user --user 0 $p").first
        Action.DISABLE -> sh("pm disable-user --user 0 $p").first
        Action.UNINSTALL -> sh("pm uninstall -k --user 0 $p").first
    }

    fun restore(p: String): Boolean {
        val a = sh("pm unsuspend --user 0 $p").first
        val b = sh("pm enable --user 0 $p").first
        val c = sh("cmd package install-existing $p").first
        return a || b || c
    }

    fun setAdGuardDns(on: Boolean) {
        if (on) {
            sh("settings put global private_dns_mode hostname")
            sh("settings put global private_dns_specifier dns.adguard-dns.com")
        } else sh("settings put global private_dns_mode off")
    }
}
