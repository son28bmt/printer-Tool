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
 * Lá»›p tÆ°Æ¡ng thÃ­ch ngÆ°á»£c cho PrinterManager.
 * Chuyá»ƒn tiáº¿p cÃ¡c lá»i gá»i sang PrinterStore vÃ  NetworkUtil.
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
        // PrinterStore tá»± Ä‘á»™ng lÆ°u qua persist() má»—i khi cÃ³ thay Ä‘á»•i
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
                    onLog("ÄÃ£ tÃ¬m tháº¥y mÃ¡y in! IP Ä‘Ã£ tá»± Ä‘á»•i sang $resolvedIp (theo MAC [${printer.mac}])")
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