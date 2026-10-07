package com.example.xptool

import android.content.Context
import net.posprinter.POSConnect
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class PrinterStatus {
    CHECKING,
    ONLINE,
    OFFLINE
}

data class SavedPrinter(
    val id: String,
    var name: String,
    val mac: String,
    var ip: String,
    var port: Int = 9100,
    var connectType: Int = POSConnect.DEVICE_TYPE_ETHERNET,
    var btMac: String = "",
    var usbPath: String = "",
    var paperWidth: Int = 58,
    var lastSeen: Long = System.currentTimeMillis(),
    var status: PrinterStatus = PrinterStatus.CHECKING
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mac", mac)
        put("ip", ip)
        put("port", port)
        put("connectType", connectType)
        put("btMac", btMac)
        put("usbPath", usbPath)
        put("paperWidth", paperWidth)
        put("lastSeen", lastSeen)
    }

    companion object {
        fun fromJson(json: JSONObject): SavedPrinter {
            val mac = json.optString("mac", "")
            val ip = json.optString("ip", "192.168.1.100")
            val id = json.optString("id", if (mac.isNotEmpty()) mac else ip)
            return SavedPrinter(
                id = id,
                name = json.optString("name", "Máy in $ip"),
                mac = mac,
                ip = ip,
                port = json.optInt("port", 9100),
                connectType = json.optInt("connectType", POSConnect.DEVICE_TYPE_ETHERNET),
                btMac = json.optString("btMac", ""),
                usbPath = json.optString("usbPath", ""),
                paperWidth = json.optInt("paperWidth", 58),
                lastSeen = json.optLong("lastSeen", System.currentTimeMillis())
            )
        }
    }
}

object PrinterManager {
    private const val PREF_KEY_PRINTERS = "saved_printers_v2"
    private const val PREF_KEY_ACTIVE_ID = "active_printer_id"

    private val isResolvingOrBroadcasting = AtomicBoolean(false)
    val savedPrinters = Collections.synchronizedList(mutableListOf<SavedPrinter>())
    var activePrinterId: String? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("xp_printers", Context.MODE_PRIVATE)
        savedPrinters.clear()
        val jsonStr = prefs.getString(PREF_KEY_PRINTERS, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    savedPrinters.add(SavedPrinter.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) {
            }
        }
        activePrinterId = prefs.getString(PREF_KEY_ACTIVE_ID, null)
    }

    fun saveAll(context: Context) {
        val prefs = context.getSharedPreferences("xp_printers", Context.MODE_PRIVATE)
        val array = JSONArray()
        synchronized(savedPrinters) {
            savedPrinters.forEach { array.put(it.toJson()) }
        }
        prefs.edit()
            .putString(PREF_KEY_PRINTERS, array.toString())
            .putString(PREF_KEY_ACTIVE_ID, activePrinterId ?: "")
            .apply()
    }

    fun addOrUpdatePrinter(context: Context, printer: SavedPrinter) {
        synchronized(savedPrinters) {
            val idx = savedPrinters.indexOfFirst {
                it.id == printer.id || (printer.mac.isNotEmpty() && it.mac.equals(printer.mac, ignoreCase = true))
            }
            if (idx >= 0) {
                savedPrinters[idx] = printer
            } else {
                savedPrinters.add(printer)
            }
            if (activePrinterId.isNullOrEmpty()) {
                activePrinterId = printer.id
            }
        }
        saveAll(context)
    }

    fun removePrinter(context: Context, id: String) {
        synchronized(savedPrinters) {
            savedPrinters.removeAll { it.id == id }
            if (activePrinterId == id) {
                activePrinterId = savedPrinters.firstOrNull()?.id
            }
        }
        saveAll(context)
    }

    fun getActivePrinter(): SavedPrinter? {
        val id = activePrinterId ?: return savedPrinters.firstOrNull()
        return savedPrinters.find { it.id == id } ?: savedPrinters.firstOrNull()
    }

    fun setActivePrinter(context: Context, id: String) {
        activePrinterId = id
        saveAll(context)
    }

    fun tcpCheckFast(ip: String, port: Int = 9100, timeoutMs: Int = 600): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun udpSearchOnce(udp: PosUdpNet, timeoutMs: Long = 3000): List<UdpDevice> {
        val foundDevices = Collections.synchronizedList(mutableListOf<UdpDevice>())
        try {
            udp.searchNetDevice { dev ->
                if (dev != null) foundDevices.add(dev)
            }
            Thread.sleep(timeoutMs)
        } catch (_: Exception) {
        }
        return synchronized(foundDevices) { foundDevices.toList() }
    }

    fun refreshAllStatuses(context: Context, udp: PosUdpNet, onFinished: () -> Unit) {
        thread {
            val executor = Executors.newFixedThreadPool(10)
            val list = synchronized(savedPrinters) { savedPrinters.toList() }
            val offlineMacPrinters = mutableListOf<SavedPrinter>()

            list.forEach { p -> p.status = PrinterStatus.CHECKING }

            list.forEach { p ->
                executor.execute {
                    if (p.connectType == POSConnect.DEVICE_TYPE_ETHERNET) {
                        val ok = tcpCheckFast(p.ip, p.port, 600)
                        if (ok) {
                            p.status = PrinterStatus.ONLINE
                            p.lastSeen = System.currentTimeMillis()
                        } else {
                            if (p.mac.isNotEmpty()) {
                                synchronized(offlineMacPrinters) { offlineMacPrinters.add(p) }
                            } else {
                                p.status = PrinterStatus.OFFLINE
                            }
                        }
                    } else {
                        p.status = PrinterStatus.ONLINE
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

            if (offlineMacPrinters.isNotEmpty() && !isResolvingOrBroadcasting.get()) {
                isResolvingOrBroadcasting.set(true)
                try {
                    val discovered = udpSearchOnce(udp, 2500)
                    offlineMacPrinters.forEach { p ->
                        val matched = discovered.find { dev ->
                            val devMac = try { dev.macStr ?: "" } catch (_: Exception) { "" }
                            devMac.isNotEmpty() && devMac.equals(p.mac, ignoreCase = true)
                        }
                        if (matched != null) {
                            val newIp = devIpStr(matched)
                            if (newIp.isNotEmpty()) {
                                p.ip = newIp
                                p.status = PrinterStatus.ONLINE
                                p.lastSeen = System.currentTimeMillis()
                            } else {
                                p.status = PrinterStatus.OFFLINE
                            }
                        } else {
                            p.status = PrinterStatus.OFFLINE
                        }
                    }
                    saveAll(context)
                } finally {
                    isResolvingOrBroadcasting.set(false)
                }
            } else {
                offlineMacPrinters.forEach { it.status = PrinterStatus.OFFLINE }
            }

            onFinished()
        }
    }

    fun resolvePrinter(
        context: Context,
        udp: PosUdpNet,
        printer: SavedPrinter,
        onLog: (String) -> Unit,
        onResult: (String?) -> Unit
    ) {
        if (printer.connectType != POSConnect.DEVICE_TYPE_ETHERNET) {
            onResult(printer.ip)
            return
        }

        thread {
            // Bước 1: Thử TCP 600ms nhanh
            if (tcpCheckFast(printer.ip, printer.port, 600)) {
                printer.status = PrinterStatus.ONLINE
                printer.lastSeen = System.currentTimeMillis()
                onResult(printer.ip)
                return@thread
            }

            // Bước 2: Thất bại & có MAC -> broadcast UDP tìm lại IP
            if (printer.mac.isNotEmpty()) {
                if (isResolvingOrBroadcasting.get()) {
                    onLog("Đang có tiến trình tìm kiếm UDP khác chạy, thử lại sau...")
                    onResult(null)
                    return@thread
                }

                isResolvingOrBroadcasting.set(true)
                onLog("Máy in ${printer.name} (${printer.ip}) không phản hồi TCP. Đang broadcast UDP tìm theo MAC [${printer.mac}]...")
                try {
                    val discovered = udpSearchOnce(udp, 2500)
                    val matched = discovered.find { dev ->
                        val devMac = try { dev.macStr ?: "" } catch (_: Exception) { "" }
                        devMac.isNotEmpty() && devMac.equals(printer.mac, ignoreCase = true)
                    }

                    if (matched != null) {
                        val newIp = devIpStr(matched)
                        if (newIp.isNotEmpty()) {
                            onLog("✔ Tìm thấy máy in! IP đã tự đổi: ${printer.ip} → $newIp")
                            printer.ip = newIp
                            printer.status = PrinterStatus.ONLINE
                            printer.lastSeen = System.currentTimeMillis()
                            saveAll(context)
                            onResult(newIp)
                            return@thread
                        }
                    }
                } finally {
                    isResolvingOrBroadcasting.set(false)
                }
            }

            // Bước 3: Thất bại
            printer.status = PrinterStatus.OFFLINE
            onLog("✘ Không kết nối được tới máy in ${printer.name} (${printer.ip}). Hãy kiểm tra nguồn điện hoặc WiFi.")
            onResult(null)
        }
    }

    private fun devIpStr(d: UdpDevice): String {
        return try {
            val bytes = d.ipAddress
            if (bytes != null && bytes.size >= 4) {
                bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            } else d.ipStr ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
