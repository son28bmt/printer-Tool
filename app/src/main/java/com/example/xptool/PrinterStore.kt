package com.example.xptool

import android.content.Context
import android.content.SharedPreferences
import net.posprinter.POSConnect
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * CÃ¡c háº±ng sá»‘ Ä‘á»‹nh cáº¥u hÃ¬nh kho mÃ¡y in vÃ  dÃ² tÃ¬m IP (khÃ´ng dÃ¹ng magic number).
 */
const val PREF_FILE_XP_PRINTERS = "xp_printers"
const val PREF_KEY_PRINTER_LIST = "xp_printers"
const val PREF_KEY_ACTIVE_PRINTER = "active_printer_key"

const val UDP_RESOLVE_TIMEOUT_MS = 3000L
const val UDP_SEARCH_STEP_MS = 100L
const val STATUS_CHECK_POOL_SIZE = 10

/**
 * Dá»¯ liá»‡u má»™t mÃ¡y in Ä‘Ã£ lÆ°u theo Giai Ä‘oáº¡n A.
 */
data class SavedPrinter(
    var name: String,           // "Quáº§y 1"
    var mac: String? = null,    // null náº¿u mÃ¡y khÃ´ng cÃ³ MAC (tÃ¬m báº±ng quÃ©t cá»•ng, hoáº·c nháº­p tay)
    var lastIp: String = "192.168.1.100",
    var port: Int = 9100,
    var connType: Int = POSConnect.DEVICE_TYPE_ETHERNET, // POSConnect.DEVICE_TYPE_*
    var lastSeenEpochMs: Long = System.currentTimeMillis(),
    var lastStatus: String = "unknown", // "online" | "offline" | "unknown"
    var paperWidth: Int = 58,
    var btMac: String = "",
    var usbPath: String = ""
) {
    // KhÃ³a Ä‘á»‹nh danh duy nháº¥t: theo MAC náº¿u cÃ³, náº¿u khÃ´ng thÃ¬ theo lastIp:port
    val id: String
        get() = if (!mac.isNullOrBlank()) mac!! else "$lastIp:$port"

    // CÃ¡c thuá»™c tÃ­nh tÆ°Æ¡ng thÃ­ch ngÆ°á»£c cho codebase hiá»‡n cÃ³
    var ip: String
        get() = lastIp
        set(value) { lastIp = value }

    var connectType: Int
        get() = connType
        set(value) { connType = value }

    var lastSeen: Long
        get() = lastSeenEpochMs
        set(value) { lastSeenEpochMs = value }

    var status: PrinterStatus
        get() = when (lastStatus) {
            "online" -> PrinterStatus.ONLINE
            "offline" -> PrinterStatus.OFFLINE
            else -> PrinterStatus.CHECKING
        }
        set(value) {
            lastStatus = when (value) {
                PrinterStatus.ONLINE -> "online"
                PrinterStatus.OFFLINE -> "offline"
                PrinterStatus.CHECKING -> "unknown"
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mac", if (mac.isNullOrBlank()) JSONObject.NULL else mac)
        put("lastIp", lastIp)
        put("port", port)
        put("connType", connType)
        put("lastSeenEpochMs", lastSeenEpochMs)
        put("lastStatus", lastStatus)
        put("paperWidth", paperWidth)
        put("btMac", btMac)
        put("usbPath", usbPath)
        // Key phá»¥ trá»£ tÆ°Æ¡ng thÃ­ch ngÆ°á»£c
        put("id", id)
        put("ip", lastIp)
        put("connectType", connType)
    }

    companion object {
        fun fromJson(json: JSONObject): SavedPrinter {
            val rawMac = json.optString("mac", "").trim()
            val macVal = if (rawMac.isEmpty() || rawMac.equals("null", ignoreCase = true) || rawMac == "00:00:00:00:00:00") null else rawMac
            val ipVal = json.optString("lastIp", "").ifEmpty { json.optString("ip", "192.168.1.100") }
            val portVal = json.optInt("port", 9100)
            val nameVal = json.optString("name", "MÃ¡y in $ipVal")
            val connTypeVal = json.optInt("connType", json.optInt("connectType", POSConnect.DEVICE_TYPE_ETHERNET))
            val lastSeenVal = json.optLong("lastSeenEpochMs", json.optLong("lastSeen", System.currentTimeMillis()))
            val lastStatusVal = json.optString("lastStatus", "unknown")
            val paperWidthVal = json.optInt("paperWidth", 58)
            val btMacVal = json.optString("btMac", "")
            val usbPathVal = json.optString("usbPath", "")

            return SavedPrinter(
                name = nameVal,
                mac = macVal,
                lastIp = ipVal,
                port = portVal,
                connType = connTypeVal,
                lastSeenEpochMs = lastSeenVal,
                lastStatus = lastStatusVal,
                paperWidth = paperWidthVal,
                btMac = btMacVal,
                usbPath = usbPathVal
            )
        }
    }
}

object PrinterStore {
    @Volatile
    private var appContext: Context? = null
    val printers = Collections.synchronizedList(mutableListOf<SavedPrinter>())
    var activePrinterKey: String? = null

    // Giá»¯ tham chiáº¿u máº¡nh tá»›i callback UDP theo Luáº­t 5
    @Volatile
    private var activeUdpCallback: UdpCallback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadAll()
    }

    private fun getPrefs(): SharedPreferences? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences(PREF_FILE_XP_PRINTERS, Context.MODE_PRIVATE)
    }

    fun loadAll(): List<SavedPrinter> {
        val prefs = getPrefs()
        synchronized(printers) {
            printers.clear()
            if (prefs != null) {
                val rawJson = prefs.getString(PREF_KEY_PRINTER_LIST, null)
                if (!rawJson.isNullOrEmpty()) {
                    try {
                        val arr = JSONArray(rawJson)
                        for (i in 0 until arr.length()) {
                            printers.add(SavedPrinter.fromJson(arr.getJSONObject(i)))
                        }
                    } catch (_: Exception) {
                    }
                } else {
                    // Di chuyá»ƒn dá»¯ liá»‡u cÅ© tá»« "xp_printer_manager" náº¿u cÃ³
                    val oldPrefs = appContext?.getSharedPreferences("xp_printer_manager", Context.MODE_PRIVATE)
                    val oldJson = oldPrefs?.getString("saved_printers_v2", null) 
                        ?: oldPrefs?.getString("saved_printers", null)
                    if (!oldJson.isNullOrEmpty()) {
                        try {
                            val arr = JSONArray(oldJson)
                            for (i in 0 until arr.length()) {
                                printers.add(SavedPrinter.fromJson(arr.getJSONObject(i)))
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                activePrinterKey = prefs.getString(PREF_KEY_ACTIVE_PRINTER, null)
            }
            if (activePrinterKey.isNullOrEmpty() && printers.isNotEmpty()) {
                activePrinterKey = printers[0].id
            }
            return printers.toList()
        }
    }

    private fun persist() {
        val prefs = getPrefs() ?: return
        val arr = JSONArray()
        synchronized(printers) {
            printers.forEach { arr.put(it.toJson()) }
        }
        prefs.edit()
            .putString(PREF_KEY_PRINTER_LIST, arr.toString())
            .putString(PREF_KEY_ACTIVE_PRINTER, activePrinterKey ?: "")
            .apply()
    }

    /**
     * LÆ°u hoáº·c cáº­p nháº­t mÃ¡y in:
     * - Theo mac náº¿u cÃ³ (!= null)
     * - Theo (lastIp + port) náº¿u khÃ´ng cÃ³ mac
     */
    fun saveOrUpdate(p: SavedPrinter) {
        synchronized(printers) {
            val idx = printers.indexOfFirst { existing ->
                if (!p.mac.isNullOrBlank() && !existing.mac.isNullOrBlank()) {
                    existing.mac.equals(p.mac, ignoreCase = true)
                } else {
                    existing.lastIp == p.lastIp && existing.port == p.port
                }
            }
            if (idx >= 0) {
                printers[idx] = p
            } else {
                printers.add(p)
            }
            if (activePrinterKey.isNullOrEmpty()) {
                activePrinterKey = p.id
            }
        }
        persist()
    }

    /**
     * XÃ³a mÃ¡y in:
     * - Theo mac náº¿u mac != null
     * - Theo fallbackIp náº¿u khÃ´ng cÃ³ mac
     */
    fun delete(mac: String?, fallbackIp: String) {
        synchronized(printers) {
            printers.removeAll { existing ->
                if (!mac.isNullOrBlank() && !existing.mac.isNullOrBlank()) {
                    existing.mac.equals(mac, ignoreCase = true)
                } else {
                    existing.lastIp == fallbackIp
                }
            }
            if (activePrinterKey != null && printers.none { it.id == activePrinterKey }) {
                activePrinterKey = printers.firstOrNull()?.id
            }
        }
        persist()
    }

    /**
     * Äá»•i tÃªn mÃ¡y in
     */
    fun rename(mac: String?, fallbackIp: String, newName: String) {
        synchronized(printers) {
            val found = printers.find { existing ->
                if (!mac.isNullOrBlank() && !existing.mac.isNullOrBlank()) {
                    existing.mac.equals(mac, ignoreCase = true)
                } else {
                    existing.lastIp == fallbackIp
                }
            }
            if (found != null) {
                found.name = newName
            }
        }
        persist()
    }

    fun getActivePrinter(): SavedPrinter? {
        synchronized(printers) {
            if (printers.isEmpty()) loadAll()
            val key = activePrinterKey ?: return printers.firstOrNull()
            return printers.find { it.id == key } ?: printers.firstOrNull()
        }
    }

    fun setActivePrinter(id: String) {
        activePrinterKey = id
        persist()
    }

    /**
     * LÃ m má»›i tráº¡ng thÃ¡i toÃ n bá»™ danh sÃ¡ch mÃ¡y in (Online / Offline / Checking).
     */
    fun refreshAllStatuses(onFinished: () -> Unit) {
        thread {
            val list = synchronized(printers) { printers.toList() }
            val executor = Executors.newFixedThreadPool(STATUS_CHECK_POOL_SIZE)
            val offlineMacPrinters = mutableListOf<SavedPrinter>()

            list.forEach { it.lastStatus = "unknown" }

            list.forEach { p ->
                executor.execute {
                    if (p.connType == POSConnect.DEVICE_TYPE_ETHERNET) {
                        val ok = tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS)
                        if (ok) {
                            p.lastStatus = "online"
                            p.lastSeenEpochMs = System.currentTimeMillis()
                        } else {
                            if (!p.mac.isNullOrBlank()) {
                                synchronized(offlineMacPrinters) { offlineMacPrinters.add(p) }
                            } else {
                                p.lastStatus = "offline"
                            }
                        }
                    } else {
                        p.lastStatus = "online"
                    }
                }
            }

            executor.shutdown()
            while (!executor.isTerminated) {
                try {
                    Thread.sleep(50)
                } catch (_: Exception) {
                }
            }

            // Náº¿u cÃ³ mÃ¡y in cÃ³ MAC bá»‹ offline, thá»­ quÃ©t UDP má»™t lÆ°á»£t Ä‘á»ƒ xem cÃ³ Ä‘á»•i IP khÃ´ng
            if (offlineMacPrinters.isNotEmpty()) {
                val foundDevices = Collections.synchronizedList(mutableListOf<UdpDevice>())
                val udp = PosUdpNet()
                val callback = UdpCallback { dev ->
                    if (dev != null) foundDevices.add(dev)
                }
                activeUdpCallback = callback
                try {
                    udp.searchNetDevice(callback)
                    Thread.sleep(UDP_RESOLVE_TIMEOUT_MS)
                } catch (_: Exception) {
                }

                offlineMacPrinters.forEach { p ->
                    val matched = synchronized(foundDevices) {
                        foundDevices.find { dev ->
                            val devMac = extractMac(dev)
                            devMac.isNotEmpty() && devMac.equals(p.mac, ignoreCase = true)
                        }
                    }
                    if (matched != null) {
                        val newIp = extractIp(matched)
                        if (newIp.isNotEmpty()) {
                            p.lastIp = newIp
                            p.lastStatus = "online"
                            p.lastSeenEpochMs = System.currentTimeMillis()
                        } else {
                            p.lastStatus = "offline"
                        }
                    } else {
                        p.lastStatus = "offline"
                    }
                }
            }

            persist()
            onFinished()
        }
    }

    /**
     * HÃ m lÃµi cá»§a Giai Ä‘oáº¡n A: resolvePrinter.
     * Thuáº­t toÃ¡n, cháº¡y á»Ÿ luá»“ng ná»n:
     * 1. tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS). ThÃ nh cÃ´ng -> onResolved(p.lastIp, false), cáº­p nháº­t lastSeenEpochMs, káº¿t thÃºc.
     * 2. Tháº¥t báº¡i vÃ  p.mac != null: gá»i PosUdpNet.searchNetDevice, chá» tá»‘i Ä‘a ~3 giÃ¢y, tÃ¬m thiáº¿t bá»‹ cÃ³ MAC khá»›p.
     *    - Tháº¥y, IP khÃ¡c p.lastIp -> cáº­p nháº­t lastIp trong store, onResolved(ipMá»›i, true).
     *    - Tháº¥y, IP giá»‘ng -> váº«n gá»i tcpConnectWithTimeout láº¡i 1 láº§n ná»¯a (phÃ²ng trÆ°á»ng há»£p máº¡ng cháº­p chá»n), rá»“i quyáº¿t Ä‘á»‹nh.
     *    - KhÃ´ng tháº¥y -> onFailed("KhÃ´ng tÃ¬m tháº¥y mÃ¡y in trong máº¡ng (Ä‘Ã£ thá»­ cáº£ IP cÅ© vÃ  dÃ² theo MAC). Náº¿u mÃ¡y á»Ÿ dáº£i máº¡ng khÃ¡c Ä‘Ã£ Ä‘Æ°á»£c Ä‘á»‹nh tuyáº¿n, hÃ£y kiá»ƒm tra láº¡i IP báº±ng tay.")
     * 3. Tháº¥t báº¡i vÃ  p.mac == null (mÃ¡y in khÃ´ng theo dÃµi Ä‘Æ°á»£c báº±ng MAC, vÃ­ dá»¥ tÃ¬m báº±ng quÃ©t cá»•ng hoáº·c nháº­p tay):
     *    khÃ´ng gá»i broadcast (vÃ´ Ã­ch vÃ¬ khÃ´ng cÃ³ MAC Ä‘á»ƒ so khá»›p) -> onFailed("MÃ¡y in khÃ´ng cÃ³ MAC Ä‘á»ƒ tá»± dÃ² láº¡i. IP cÃ³ thá»ƒ Ä‘Ã£ Ä‘á»•i, hÃ£y quÃ©t hoáº·c nháº­p IP má»›i.") ngay sau bÆ°á»›c 1 tháº¥t báº¡i.
     */
    /**
     * XÃ¡c Ä‘á»‹nh IP mÃ¡y in má»™t cÃ¡ch Ä‘á»“ng bá»™ (dÃ¹ng trong luá»“ng worker cá»§a PrintQueue).
     * Tráº£ vá» IP há»£p lá»‡ (IP má»›i náº¿u Ä‘á»•i), hoáº·c null náº¿u tháº¥t báº¡i.
     */
    fun resolvePrinterSync(
        p: SavedPrinter,
        onLog: ((String) -> Unit)? = null
    ): String? {
        if (p.connType != POSConnect.DEVICE_TYPE_ETHERNET) {
            return p.lastIp
        }

        // BÆ°á»›c 1: Thá»­ TCP trá»±c tiáº¿p vá»›i timeout DEFAULT_TCP_TIMEOUT_MS
        val tcpOk = tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS)
        if (tcpOk) {
            p.lastSeenEpochMs = System.currentTimeMillis()
            p.lastStatus = "online"
            saveOrUpdate(p)
            return p.lastIp
        }

        // BÆ°á»›c 3: Tháº¥t báº¡i vÃ  p.mac == null -> KhÃ´ng thá»ƒ dÃ² theo MAC
        if (p.mac.isNullOrBlank()) {
            p.lastStatus = "offline"
            saveOrUpdate(p)
            onLog?.invoke("MÃ¡y in ${p.name} (${p.lastIp}) khÃ´ng cÃ³ MAC Ä‘á»ƒ tá»± dÃ² láº¡i. HÃ£y kiá»ƒm tra káº¿t ná»‘i máº¡ng hoáº·c nháº­p IP má»›i.")
            return null
        }

        // BÆ°á»›c 2: Tháº¥t báº¡i vÃ  p.mac != null -> DÃ² UDP Broadcast theo MAC
        val targetMac = p.mac!!
        onLog?.invoke("MÃ¡y in ${p.name} (${p.lastIp}) khÃ´ng pháº£n há»“i TCP. Äang broadcast UDP dÃ² tÃ¬m theo MAC [$targetMac]...")

        val foundDevices = Collections.synchronizedList(mutableListOf<UdpDevice>())
        val udp = PosUdpNet()

        val callback = UdpCallback { dev ->
            if (dev != null) foundDevices.add(dev)
        }
        activeUdpCallback = callback

        try {
            udp.searchNetDevice(callback)
        } catch (_: Exception) {
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < UDP_RESOLVE_TIMEOUT_MS) {
            try {
                Thread.sleep(UDP_SEARCH_STEP_MS)
            } catch (_: InterruptedException) {
                break
            }
            val foundEarly = synchronized(foundDevices) {
                foundDevices.find { dev ->
                    val macStr = extractMac(dev)
                    macStr.isNotEmpty() && macStr.equals(targetMac, ignoreCase = true)
                }
            }
            if (foundEarly != null) break
        }

        val matchedDevice = synchronized(foundDevices) {
            foundDevices.find { dev ->
                val macStr = extractMac(dev)
                macStr.isNotEmpty() && macStr.equals(targetMac, ignoreCase = true)
            }
        }

        if (matchedDevice != null) {
            val newIp = extractIp(matchedDevice)
            if (newIp.isNotEmpty() && newIp != p.lastIp) {
                onLog?.invoke("TÃ¬m tháº¥y mÃ¡y in! IP Ä‘Ã£ Ä‘á»•i: ${p.lastIp} âž” $newIp")
                p.lastIp = newIp
                p.lastSeenEpochMs = System.currentTimeMillis()
                p.lastStatus = "online"
                saveOrUpdate(p)
                return newIp
            } else if (newIp.isNotEmpty() && newIp == p.lastIp) {
                val retryOk = tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS)
                if (retryOk) {
                    p.lastSeenEpochMs = System.currentTimeMillis()
                    p.lastStatus = "online"
                    saveOrUpdate(p)
                    return p.lastIp
                } else {
                    onLog?.invoke("MÃ¡y in pháº£n há»“i UDP táº¡i IP ${p.lastIp} nhÆ°ng khÃ´ng thá»ƒ káº¿t ná»‘i cá»•ng ${p.port} qua TCP.")
                    p.lastStatus = "offline"
                    saveOrUpdate(p)
                    return null
                }
            }
        }

        onLog?.invoke("KhÃ´ng tÃ¬m tháº¥y mÃ¡y in trong máº¡ng theo MAC [$targetMac].")
        p.lastStatus = "offline"
        saveOrUpdate(p)
        return null
    }

    /**
     * HÃ m lÃµi cá»§a Giai Ä‘oáº¡n A: resolvePrinter (cháº¡y báº¥t Ä‘á»“ng bá»™ qua thread).
     */
    fun resolvePrinter(
        p: SavedPrinter,
        onResolved: (ip: String, viaBroadcastUpdate: Boolean) -> Unit,
        onFailed: (reason: String) -> Unit
    ) {
        if (p.connType != POSConnect.DEVICE_TYPE_ETHERNET) {
            onResolved(p.lastIp, false)
            return
        }

        thread {
            val oldIp = p.lastIp
            val resolvedIp = resolvePrinterSync(p)
            if (resolvedIp != null) {
                val isChanged = (resolvedIp != oldIp)
                onResolved(resolvedIp, isChanged)
            } else {
                if (p.mac.isNullOrBlank()) {
                    onFailed("MÃ¡y in khÃ´ng cÃ³ MAC Ä‘á»ƒ tá»± dÃ² láº¡i. IP cÃ³ thá»ƒ Ä‘Ã£ Ä‘á»•i, hÃ£y quÃ©t hoáº·c nháº­p IP má»›i.")
                } else {
                    onFailed("KhÃ´ng tÃ¬m tháº¥y mÃ¡y in trong máº¡ng (Ä‘Ã£ thá»­ cáº£ IP cÅ© vÃ  dÃ² theo MAC). Náº¿u mÃ¡y á»Ÿ dáº£i máº¡ng khÃ¡c Ä‘Ã£ Ä‘Æ°á»£c Ä‘á»‹nh tuyáº¿n, hÃ£y kiá»ƒm tra láº¡i IP báº±ng tay.")
                }
            }
        }
    }
    private fun extractIp(d: UdpDevice): String {
        return try {
            val bytes = d.ipAddress
            if (bytes != null && bytes.size >= 4) {
                bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            } else d.ipStr ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractMac(d: UdpDevice): String {
        return try {
            val mac = d.macStr ?: ""
            if (mac == "00:00:00:00:00:00") "" else mac
        } catch (_: Exception) {
            ""
        }
    }
}