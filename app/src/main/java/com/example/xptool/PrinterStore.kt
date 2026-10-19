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
 * Các hằng số định cấu hình kho máy in và dò tìm IP (không dùng magic number).
 */
const val PREF_FILE_XP_PRINTERS = "xp_printers"
const val PREF_KEY_PRINTER_LIST = "xp_printers"
const val PREF_KEY_ACTIVE_PRINTER = "active_printer_key"

const val UDP_RESOLVE_TIMEOUT_MS = 3000L
const val UDP_SEARCH_STEP_MS = 100L
const val STATUS_CHECK_POOL_SIZE = 10

/**
 * Dữ liệu một máy in đã lưu theo Giai đoạn A.
 */
data class SavedPrinter(
    var name: String,           // "Quầy 1"
    var mac: String? = null,    // null nếu máy không có MAC (tìm bằng quét cổng, hoặc nhập tay)
    var lastIp: String = "192.168.1.100",
    var port: Int = 9100,
    var connType: Int = POSConnect.DEVICE_TYPE_ETHERNET, // POSConnect.DEVICE_TYPE_*
    var lastSeenEpochMs: Long = System.currentTimeMillis(),
    var lastStatus: String = "unknown", // "online" | "offline" | "unknown"
    var paperWidth: Int = 58,
    var printKind: Int = -1, // -1 = chưa gán, 0 = bill, 1 = tem
    var labelSize: Int = 0,
    var labelType: Int = 0,
    var btMac: String = "",
    var usbPath: String = ""
) {
    // Khóa định danh duy nhất: theo MAC nếu có, nếu không thì theo lastIp:port
    val id: String
        get() = if (!mac.isNullOrBlank()) mac!! else "$lastIp:$port"

    // Các thuộc tính tương thích ngược cho codebase hiện có
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
        put("printKind", printKind)
        put("labelSize", labelSize)
        put("labelType", labelType)
        put("btMac", btMac)
        put("usbPath", usbPath)
        // Key phụ trợ tương thích ngược
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
            val nameVal = json.optString("name", "Máy in $ipVal")
            val connTypeVal = json.optInt("connType", json.optInt("connectType", POSConnect.DEVICE_TYPE_ETHERNET))
            val lastSeenVal = json.optLong("lastSeenEpochMs", json.optLong("lastSeen", System.currentTimeMillis()))
            val lastStatusVal = json.optString("lastStatus", "unknown")
            val paperWidthVal = json.optInt("paperWidth", 58)
            val printKindVal = json.optInt("printKind", -1).coerceIn(-1, 1)
            val labelSizeVal = json.optInt("labelSize", 0).coerceIn(0, 4)
            val labelTypeVal = json.optInt("labelType", 0).coerceIn(0, 1)
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
                printKind = printKindVal,
                labelSize = labelSizeVal,
                labelType = labelTypeVal,
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

    // Giữ tham chiếu mạnh tới callback UDP theo Luật 5
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

    fun loadScanRanges(): MutableList<String> {
        val prefs = getPrefs() ?: return DefaultScanRanges.BUILTIN_RANGES.toMutableList()
        val raw = prefs.getString("scan_ranges", null) ?: return DefaultScanRanges.BUILTIN_RANGES.toMutableList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { array.optString(it, null) }
                .filter(::isValidScanRange)
                .distinct()
                .toMutableList()
        } catch (_: Exception) {
            DefaultScanRanges.BUILTIN_RANGES.toMutableList()
        }
    }

    fun saveScanRanges(ranges: List<String>) {
        val cleanRanges = ranges.map { it.trim() }.filter(::isValidScanRange).distinct()
        getPrefs()?.edit()?.putString("scan_ranges", JSONArray(cleanRanges).toString())?.apply()
    }

    private fun isValidScanRange(range: String): Boolean {
        if (!range.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\."))) return false
        return range.dropLast(1).split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
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
                    // Di chuyển dữ liệu cũ từ "xp_printer_manager" nếu có
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
     * Lưu hoặc cập nhật máy in:
     * - Theo mac nếu có (!= null)
     * - Theo (lastIp + port) nếu không có mac
     */
    fun saveOrUpdate(p: SavedPrinter) {
        synchronized(printers) {
            val idx = printers.indexOfFirst { existing ->
                when (p.connType) {
                    POSConnect.DEVICE_TYPE_BLUETOOTH -> existing.connType == POSConnect.DEVICE_TYPE_BLUETOOTH &&
                        existing.btMac.equals(p.btMac, ignoreCase = true)
                    POSConnect.DEVICE_TYPE_USB -> existing.connType == POSConnect.DEVICE_TYPE_USB &&
                        existing.usbPath == p.usbPath
                    else -> existing.connType == p.connType && if (!p.mac.isNullOrBlank() && !existing.mac.isNullOrBlank()) {
                        existing.mac.equals(p.mac, ignoreCase = true)
                    } else {
                        existing.lastIp == p.lastIp && existing.port == p.port
                    }
                }
            }
            if (idx >= 0) {
                val existing = printers[idx]
                // A rediscovery/connection save has no print assignment of its own; keep the
                // settings the user attached to this saved device.
                if (p.printKind < 0) {
                    p.printKind = existing.printKind
                    p.labelSize = existing.labelSize
                    p.labelType = existing.labelType
                    if (p.paperWidth == 58) p.paperWidth = existing.paperWidth
                }
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
     * Xóa máy in:
     * - Theo mac nếu mac != null
     * - Theo fallbackIp nếu không có mac
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
     * Đổi tên máy in
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
     * Làm mới trạng thái toàn bộ danh sách máy in (Online / Offline / Checking).
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

            // Nếu có máy in có MAC bị offline, thử quét UDP một lượt để xem có đổi IP không
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
     * Hàm lõi của Giai đoạn A: resolvePrinter.
     * Thuật toán, chạy ở luồng nền:
     * 1. tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS). Thành công -> onResolved(p.lastIp, false), cập nhật lastSeenEpochMs, kết thúc.
     * 2. Thất bại và p.mac != null: gọi PosUdpNet.searchNetDevice, chờ tối đa ~3 giây, tìm thiết bị có MAC khớp.
     *    - Thấy, IP khác p.lastIp -> cập nhật lastIp trong store, onResolved(ipMới, true).
     *    - Thấy, IP giống -> vẫn gọi tcpConnectWithTimeout lại 1 lần nữa (phòng trường hợp mạng chập chờn), rồi quyết định.
     *    - Không thấy -> onFailed("Không tìm thấy máy in trong mạng (đã thử cả IP cũ và dò theo MAC). Nếu máy ở dải mạng khác đã được định tuyến, hãy kiểm tra lại IP bằng tay.")
     * 3. Thất bại và p.mac == null (máy in không theo dõi được bằng MAC, ví dụ tìm bằng quét cổng hoặc nhập tay):
     *    không gọi broadcast (vô ích vì không có MAC để so khớp) -> onFailed("Máy in không có MAC để tự dò lại. IP có thể đã đổi, hãy quét hoặc nhập IP mới.") ngay sau bước 1 thất bại.
     */
    /**
     * Xác định IP máy in một cách đồng bộ (dùng trong luồng worker của PrintQueue).
     * Trả về IP hợp lệ (IP mới nếu đổi), hoặc null nếu thất bại.
     */
    fun resolvePrinterSync(
        p: SavedPrinter,
        onLog: ((String) -> Unit)? = null
    ): String? {
        if (p.connType != POSConnect.DEVICE_TYPE_ETHERNET) {
            return p.lastIp
        }

        // Bước 1: Thử TCP trực tiếp với timeout DEFAULT_TCP_TIMEOUT_MS
        val tcpOk = tcpConnectWithTimeout(p.lastIp, p.port, DEFAULT_TCP_TIMEOUT_MS)
        if (tcpOk) {
            p.lastSeenEpochMs = System.currentTimeMillis()
            p.lastStatus = "online"
            saveOrUpdate(p)
            return p.lastIp
        }

        // Bước 3: Thất bại và p.mac == null -> Không thể dò theo MAC
        if (p.mac.isNullOrBlank()) {
            p.lastStatus = "offline"
            saveOrUpdate(p)
            onLog?.invoke("Máy in ${p.name} (${p.lastIp}) không có MAC để tự dò lại. Hãy kiểm tra kết nối mạng hoặc nhập IP mới.")
            return null
        }

        // Bước 2: Thất bại và p.mac != null -> Dò UDP Broadcast theo MAC
        val targetMac = p.mac!!
        onLog?.invoke("Máy in ${p.name} (${p.lastIp}) không phản hồi TCP. Đang broadcast UDP dò tìm theo MAC [$targetMac]...")

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
                onLog?.invoke("Tìm thấy máy in! IP đã đổi: ${p.lastIp} ➔ $newIp")
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
                    onLog?.invoke("Máy in phản hồi UDP tại IP ${p.lastIp} nhưng không thể kết nối cổng ${p.port} qua TCP.")
                    p.lastStatus = "offline"
                    saveOrUpdate(p)
                    return null
                }
            }
        }

        onLog?.invoke("Không tìm thấy máy in trong mạng theo MAC [$targetMac].")
        p.lastStatus = "offline"
        saveOrUpdate(p)
        return null
    }

    /**
     * Hàm lõi của Giai đoạn A: resolvePrinter (chạy bất đồng bộ qua thread).
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
                    onFailed("Máy in không có MAC để tự dò lại. IP có thể đã đổi, hãy quét hoặc nhập IP mới.")
                } else {
                    onFailed("Không tìm thấy máy in trong mạng (đã thử cả IP cũ và dò theo MAC). Nếu máy ở dải mạng khác đã được định tuyến, hãy kiểm tra lại IP bằng tay.")
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
