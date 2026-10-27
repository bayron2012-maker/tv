package com.cleanyourtv

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class Vm(app: Application) : AndroidViewModel(app) {
    private val adb = TvAdb(app.filesDir)
    private val prefs = app.getSharedPreferences("cytv", 0)
    var status by mutableStateOf("Not connected"); var busy by mutableStateOf(false)
    var ip by mutableStateOf(""); var port by mutableStateOf("5555")
    var found by mutableStateOf(listOf<String>())
    var installed by mutableStateOf(listOf<String>())
    var thirdParty by mutableStateOf(setOf<String>())
    var imported by mutableStateOf(setOf<String>())
    var action by mutableStateOf(Action.SUSPEND)
    val selected = mutableStateListOf<String>()
    var candidates by mutableStateOf(listOf<String>())
    val log = mutableStateListOf<String>()

    private fun io(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        busy = true
        try { block() } catch (e: Exception) { log.add("Error: ${e.message}") }
        busy = false
    }

    private fun wifiNet(): Network? {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }
    }
    /** Force sockets through Wi-Fi: with mobile data on, Android may otherwise route local IPs the wrong way. */
    private fun bindWifi() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        wifiNet()?.let { cm.bindProcessToNetwork(it) }
    }
    private fun target(): Pair<String, Int> {
        val t = ip.trim()
        return if (t.contains(":")) t.substringBefore(":") to (t.substringAfter(":").toIntOrNull() ?: 5555)
        else t to (port.trim().toIntOrNull() ?: 5555)
    }

    fun connect() = io {
        val (host, p) = target()
        status = "Connecting to $host:$p..."
        bindWifi()
        adb.probe(host, p)?.let { status = it; log.add(it); return@io }
        status = "Port open. On the TV tap Allow and tick 'Always allow'..."
        val ex = Executors.newSingleThreadExecutor()
        try {
            val fut = ex.submit(Callable { adb.connect(host, p); adb.deviceInfo() })
            val info = try { fut.get(30, TimeUnit.SECONDS) }
            catch (e: TimeoutException) { fut.cancel(true); adb.close(); throw Exception("No answer after 30 s: the TV never accepted the key. Tap Reset keys, connect again and press Allow on the TV.") }
            catch (e: ExecutionException) { throw e.cause ?: e }
            status = "Connected: $info"
            installed = adb.installed(); thirdParty = adb.thirdParty()
        } catch (e: Throwable) {
            status = "ADB handshake failed: " + chain(e); log.add(status)
        } finally { ex.shutdownNow() }
    }
    private fun chain(e: Throwable) = generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
    fun resetKeys() = io { adb.resetKeys(); status = "Keys reset. Connect again and accept the prompt on the TV." }

    fun scanNetwork() = io {
        bindWifi(); found = emptyList(); status = "Scanning..."
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val base = wifiNet()?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress?.substringBeforeLast('.')
        if (base == null) { status = "No Wi-Fi/Ethernet IPv4 address. Connect the phone to the TV's network."; return@io }
        val p = port.trim().toIntOrNull() ?: 5555
        val pool = Executors.newFixedThreadPool(64)
        val hits = (1..254).map { i ->
            pool.submit(Callable<String?> {
                val h = "$base.$i"
                try { Socket().use { it.connect(InetSocketAddress(h, p), 600) }; h } catch (e: Exception) { null }
            })
        }
        found = hits.mapNotNull { it.get() }; pool.shutdown()
        status = if (found.isEmpty()) "Nothing with port $p open on $base.x" else "Found ${found.size}. Tap an IP, then Connect."
    }
    fun refresh() = io { installed = adb.installed(); thirdParty = adb.thirdParty() }
    fun importList(text: String) { imported = Presets.parse(text); log.add("Imported ${imported.size} package names") }

    /** AUTO: built-in + imported names that exist on this TV, minus protected ones. */
    fun scan() {
        candidates = installed.filter { (it in Presets.generic || it in imported) && !Guard.isProtected(it) }
        selected.clear(); selected.addAll(candidates)
        log.add("Auto scan: ${candidates.size} candidates found. Review, then Apply.")
    }

    fun applySelected() = io {
        val done = mutableSetOf<String>()
        selected.filter { !Guard.isProtected(it) }.forEach { p ->
            val ok = adb.apply(p, action); log.add((if (ok) "OK  " else "FAIL ") + p); if (ok) done += p
        }
        prefs.edit().putStringSet("last", done).apply()
        installed = adb.installed()
    }
    fun restoreSelected() = io { selected.forEach { log.add((if (adb.restore(it)) "RESTORED " else "FAIL ") + it) }; installed = adb.installed() }
    fun undoLast() = io {
        prefs.getStringSet("last", emptySet())!!.forEach { log.add((if (adb.restore(it)) "RESTORED " else "FAIL ") + it) }
        installed = adb.installed()
    }
    fun dns(on: Boolean) = io { adb.setAdGuardDns(on); log.add("Private DNS " + if (on) "→ AdGuard" else "off") }
}

class MainActivity : ComponentActivity() {
    private val vm: Vm by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(vm) } } }
    }
}

@Composable
fun App(vm: Vm) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.statusBarsPadding()) {
        TabRow(tab) { listOf("Connect", "Auto", "Manual").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f)) { when (tab) { 0 -> ConnectTab(vm); 1 -> AutoTab(vm); else -> ManualTab(vm) } }
        LogPane(vm)
    }
}

@Composable
fun ConnectTab(vm: Vm) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("On the TV enable ADB/Network debugging. Phone and TV must share the same Wi-Fi.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.ip, { vm.ip = it }, label = { Text("TV IP (or ip:port)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.port, { vm.port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.connect() }, enabled = vm.ip.isNotBlank() && !vm.busy) { Text("Connect") }
            OutlinedButton({ vm.scanNetwork() }, enabled = !vm.busy) { Text("Scan network") }
        }
        vm.found.forEach { h -> TextButton({ vm.ip = h }) { Text(h) } }
        Text(vm.status)
        TextButton({ vm.resetKeys() }) { Text("Reset keys") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.dns(true) }) { Text("AdGuard DNS on") }
            OutlinedButton({ vm.dns(false) }) { Text("Private DNS off") }
        }
    }
}

@Composable
fun ActionPicker(vm: Vm) {
    Column {
        Action.values().forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.action == a, { vm.action = a }); Text(a.label)
            }
        }
    }
}

@Composable
fun PackageList(vm: Vm, items: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(items) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(p in vm.selected, { c -> if (c) vm.selected.add(p) else vm.selected.remove(p) }, enabled = !Guard.isProtected(p))
                Text(p + if (Guard.isProtected(p)) "  (protected)" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AutoTab(vm: Vm) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        u?.let { vm.importList(vm.getApplication<Application>().contentResolver.openInputStream(it)!!.bufferedReader().readText()) }
    }
    Column(Modifier.padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pick.launch(arrayOf("text/*")) }) { Text("Import list") }
            Button({ vm.scan() }, enabled = vm.installed.isNotEmpty()) { Text("Scan") }
        }
        ActionPicker(vm)
        PackageList(vm, vm.candidates, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.applySelected() }, enabled = vm.selected.isNotEmpty() && !vm.busy) { Text("Apply (${vm.selected.size})") }
            OutlinedButton({ vm.undoLast() }) { Text("Undo last run") }
        }
    }
}

@Composable
fun ManualTab(vm: Vm) {
    var q by remember { mutableStateOf("") }
    var userOnly by remember { mutableStateOf(false) }
    val shown = vm.installed.filter { it.contains(q, true) && (!userOnly || it in vm.thirdParty) }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text("Search packages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(userOnly, { userOnly = it }); Text("User-installed only")
            Spacer(Modifier.weight(1f)); TextButton({ vm.refresh() }) { Text("Reload") }
        }
        ActionPicker(vm)
        PackageList(vm, shown, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.applySelected() }, enabled = vm.selected.isNotEmpty() && !vm.busy) { Text("Apply (${vm.selected.size})") }
            OutlinedButton({ vm.restoreSelected() }, enabled = vm.selected.isNotEmpty()) { Text("Restore") }
        }
    }
}

@Composable
fun LogPane(vm: Vm) {
    LazyColumn(Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 12.dp)) {
        items(vm.log.takeLast(40).reversed()) { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
