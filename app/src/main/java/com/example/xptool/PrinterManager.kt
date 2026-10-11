package com.example.xptool

import android.content.Context
import net.posprinter.POSConnect
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import java.util.Collections

enum class PrinterStatus {
    CHECKING,
    ONLINE,
    OFFLINE
}

/**
 * Lớp tương thích ngược cho PrinterManager.
 * Chuyển tiếp các lời gọi sang PrinterStore và NetworkUtil.
 */
object PrinterManager {
    val savedPrinters: MutableList<SavedPrinter>
        get() = PrinterStore.printers

    var activePrinterId: String?
        get() = PrinterStore.activePrinterKey
        set(value) {
            PrinterStore.activePrinterKey = value
        }

    fun init(context: Context) {
        PrinterStore.init(context)
    }

    fun loadAll(context: Context) {
        PrinterStore.init(context)
    }

    fun saveAll(context: Context) {
        // PrinterStore tự động lưu qua persist() mỗi khi có thay đổi
    }

    fun addOrUpdatePrinter(context: Context, printer: SavedPrinter) {
        PrinterStore.saveOrUpdate(printer)
    }

    fun removePrinter(context: Context, id: String) {
        PrinterStore.delete(id, id)
    }

    fun getActivePrinter(): SavedPrinter? {
        return PrinterStore.getActivePrinter()
    }

    fun setActivePrinter(context: Context, id: String) {
        PrinterStore.setActivePrinter(id)
    }

    fun tcpCheckFast(ip: String, port: Int = 9100, timeoutMs: Int = 600): Boolean {
        return NetworkUtil.tcpConnectWithTimeout(ip, port, timeoutMs)
    }

    fun udpSearchOnce(udp: PosUdpNet, timeoutMs: Long = 3000): List<UdpDevice> {
        val foundDevices = Collections.synchronizedList(mutableListOf<UdpDevice>())
        try {
            val callback = net.posprinter.posprinterface.UdpCallback { dev ->
                if (dev != null) foundDevices.add(dev)
            }
            udp.searchNetDevice(callback)
            Thread.sleep(timeoutMs)
        } catch (_: Exception) {
        }
        return synchronized(foundDevices) { foundDevices.toList() }
    }

    fun refreshAllStatuses(context: Context, udp: PosUdpNet, onFinished: () -> Unit) {
        PrinterStore.refreshAllStatuses(onFinished)
    }

    fun resolvePrinter(
        context: Context,
        udp: PosUdpNet,
        printer: SavedPrinter,
        onLog: (String) -> Unit,
        onResult: (String?) -> Unit
    ) {
        PrinterStore.resolvePrinter(
            printer,
            onResolved = { resolvedIp, viaBroadcastUpdate ->
                if (viaBroadcastUpdate) {
                    onLog("Đã tìm thấy máy in! IP đã tự đổi sang $resolvedIp (theo MAC [${printer.mac}])")
                }
                onResult(resolvedIp)
            },
            onFailed = { reason ->
                onLog(reason)
                onResult(null)
            }
        )
    }
}