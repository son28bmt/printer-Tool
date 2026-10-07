package com.example.xptool

import android.content.Context
import android.net.wifi.WifiManager
import net.posprinter.esc.PosUdpNet
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

data class DiagnosticStepResult(
    val stepIndex: Int,
    val title: String,
    val isOk: Boolean,
    val message: String,
    val actionText: String? = null,
    val actionType: DiagnosticActionType = DiagnosticActionType.NONE
)

enum class DiagnosticActionType {
    NONE,
    OPEN_WIFI_SETTINGS,
    CHANGE_PRINTER_IP,
    TEST_PRINT
}

object DiagnosticHelper {

    fun runDiagnostics(
        context: Context,
        udp: PosUdpNet,
        targetPrinter: SavedPrinter?,
        onStepUpdate: (DiagnosticStepResult) -> Unit,
        onFinished: (DiagnosticStepResult?) -> Unit
    ) {
        thread {
            var firstFailure: DiagnosticStepResult? = null

            fun reportStep(result: DiagnosticStepResult) {
                if (!result.isOk && firstFailure == null) {
                    firstFailure = result
                }
                onStepUpdate(result)
            }

            // Bước 1: Kiểm tra WiFi điện thoại
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = try { wm.dhcpInfo } catch (_: Exception) { null }
            val phoneIpInt = dhcp?.ipAddress ?: 0

            if (phoneIpInt == 0) {
                reportStep(
                    DiagnosticStepResult(
                        stepIndex = 1,
                        title = "1. Kiểm tra WiFi điện thoại",
                        isOk = false,
                        message = "Điện thoại chưa kết nối WiFi (hoặc đang dùng dữ liệu di động 4G/5G).",
                        actionText = "Mở Cài đặt WiFi",
                        actionType = DiagnosticActionType.OPEN_WIFI_SETTINGS
                    )
                )
                onFinished(firstFailure)
                return@thread
            }

            fun intToIpBytes(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )

            val phoneIpBytes = intToIpBytes(dhcp!!.ipAddress)
            val phoneMaskBytes = if (dhcp.netmask != 0) intToIpBytes(dhcp.netmask) else byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)
            val phoneGwBytes = intToIpBytes(dhcp.gateway)

            fun ipStr(b: ByteArray) = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 1,
                    title = "1. Kiểm tra WiFi điện thoại",
                    isOk = true,
                    message = "Điện thoại đã nối WiFi (IP ${ipStr(phoneIpBytes)}, Gateway ${ipStr(phoneGwBytes)})."
                )
            )

            // Bước 2: Ping Router / Gateway
            val gwIp = ipStr(phoneGwBytes)
            var gwOk = false
            try {
                if (InetAddress.getByName(gwIp).isReachable(1000)) {
                    gwOk = true
                } else {
                    Socket().use { s -> s.connect(InetSocketAddress(gwIp, 80), 600) }
                    gwOk = true
                }
            } catch (_: Exception) {
            }

            if (!gwOk) {
                reportStep(
                    DiagnosticStepResult(
                        stepIndex = 2,
                        title = "2. Kiểm tra Router WiFi (Gateway)",
                        isOk = false,
                        message = "Router WiFi ($gwIp) không phản hồi. Có thể Router bị đứt mạng hoặc treo.",
                        actionText = "Kiểm tra lại Router"
                    )
                )
                onFinished(firstFailure)
                return@thread
            }

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 2,
                    title = "2. Kiểm tra Router WiFi (Gateway)",
                    isOk = true,
                    message = "Router WiFi ($gwIp) hoạt động bình thường."
                )
            )

            if (targetPrinter == null) {
                onFinished(firstFailure)
                return@thread
            }

            // Bước 3: Dải IP máy in vs Điện thoại
            val pIpBytes = parseIp4(targetPrinter.ip)
            val sameSub = if (pIpBytes != null) sameSubnet(pIpBytes, phoneIpBytes, phoneMaskBytes) else false

            if (!sameSub) {
                reportStep(
                    DiagnosticStepResult(
                        stepIndex = 3,
                        title = "3. Kiểm tra dải mạng IP máy in",
                        isOk = false,
                        message = "Máy in (${targetPrinter.ip}) khác dải mạng với điện thoại (${ipStr(phoneIpBytes)}).",
                        actionText = "Đổi IP máy in về cùng dải",
                        actionType = DiagnosticActionType.CHANGE_PRINTER_IP
                    )
                )
                onFinished(firstFailure)
                return@thread
            }

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 3,
                    title = "3. Kiểm tra dải mạng IP máy in",
                    isOk = true,
                    message = "Máy in (${targetPrinter.ip}) cùng dải mạng với điện thoại."
                )
            )

            // Bước 4: TCP cổng 9100
            val tcpOk = PrinterManager.tcpCheckFast(targetPrinter.ip, targetPrinter.port, 1000)

            if (!tcpOk) {
                reportStep(
                    DiagnosticStepResult(
                        stepIndex = 4,
                        title = "4. Kết nối TCP cổng ${targetPrinter.port}",
                        isOk = false,
                        message = "Không thể mở cổng TCP ${targetPrinter.port} tới ${targetPrinter.ip}."
                    )
                )

                // Bước 5: Broadcast UDP tìm lại theo MAC
                if (targetPrinter.mac.isNotEmpty()) {
                    reportStep(
                        DiagnosticStepResult(
                            stepIndex = 5,
                            title = "5. Tìm máy in theo MAC [${targetPrinter.mac}]",
                            isOk = false,
                            message = "Đang phát UDP Broadcast dò lại địa chỉ IP..."
                        )
                    )

                    val discovered = PrinterManager.udpSearchOnce(udp, 2500)
                    val matched = discovered.find { dev ->
                        val devMac = try { dev.macStr ?: "" } catch (_: Exception) { "" }
                        devMac.isNotEmpty() && devMac.equals(targetPrinter.mac, ignoreCase = true)
                    }

                    if (matched != null) {
                        val newIp = try {
                            val b = matched.ipAddress
                            if (b != null && b.size >= 4) b.joinToString(".") { (it.toInt() and 0xFF).toString() } else matched.ipStr ?: ""
                        } catch (_: Exception) { "" }

                        if (newIp.isNotEmpty()) {
                            targetPrinter.ip = newIp
                            targetPrinter.status = PrinterStatus.ONLINE
                            PrinterManager.saveAll(context)
                            reportStep(
                                DiagnosticStepResult(
                                    stepIndex = 5,
                                    title = "5. Tìm máy in theo MAC [${targetPrinter.mac}]",
                                    isOk = true,
                                    message = "IP máy in đã thay đổi! Đã tự động cập nhật IP mới: $newIp"
                                )
                            )
                            onFinished(null)
                            return@thread
                        }
                    }

                    reportStep(
                        DiagnosticStepResult(
                            stepIndex = 5,
                            title = "5. Tìm máy in theo MAC [${targetPrinter.mac}]",
                            isOk = false,
                            message = "Máy in đang tắt nguồn, chưa cắm dây LAN, hoặc WiFi Router bật chế độ cô lập thiết bị (AP Isolation)."
                        )
                    )
                } else {
                    reportStep(
                        DiagnosticStepResult(
                            stepIndex = 5,
                            title = "5. Tìm máy in theo MAC",
                            isOk = false,
                            message = "Máy in quét cổng 9100 không có MAC. Vui lòng kiểm tra nguồn điện và cáp LAN."
                        )
                    )
                }

                onFinished(firstFailure)
                return@thread
            }

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 4,
                    title = "4. Kết nối TCP cổng ${targetPrinter.port}",
                    isOk = true,
                    message = "Kết nối TCP ${targetPrinter.ip}:${targetPrinter.port} thành công."
                )
            )

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 5,
                    title = "5. Tìm máy in theo MAC",
                    isOk = true,
                    message = "Bỏ qua (kết nối TCP ${targetPrinter.ip}:${targetPrinter.port} đã hoạt động bình thường)."
                )
            )

            // Bước 6: Trạng thái ESC/POS giấy & nắp
            var paperStateMsg: String? = null
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(targetPrinter.ip, targetPrinter.port), 1000)
                    s.getOutputStream().apply {
                        write(byteArrayOf(0x10, 0x04, 0x02))
                        flush()
                    }
                    s.soTimeout = 800
                    val buf = ByteArray(64)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) {
                        val st = buf[0].toInt()
                        if ((st and 0x20) != 0) {
                            paperStateMsg = "Máy in đang HẾT GIẤY! Vui lòng nạp thêm giấy in."
                        } else if ((st and 0x04) != 0) {
                            paperStateMsg = "Nắp máy in đang MỞ! Vui lòng đóng nắp máy in."
                        }
                    }
                }
            } catch (_: Exception) {
            }

            if (paperStateMsg != null) {
                reportStep(
                    DiagnosticStepResult(
                        stepIndex = 6,
                        title = "6. Trạng thái giấy & nắp máy in",
                        isOk = false,
                        message = paperStateMsg!!
                    )
                )
                onFinished(firstFailure)
                return@thread
            }

            reportStep(
                DiagnosticStepResult(
                    stepIndex = 6,
                    title = "6. Trạng thái giấy & nắp máy in",
                    isOk = true,
                    message = "Giấy in và nắp máy in sẵn sàng."
                )
            )

            onFinished(null)
        }
    }

    private fun parseIp4(s: String): ByteArray? {
        val p = s.trim().split(".")
        if (p.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val v = p[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, mask: ByteArray): Boolean {
        if (a.size < 4 || b.size < 4 || mask.size < 4) return false
        for (i in 0..3) {
            if ((a[i].toInt() and mask[i].toInt()) != (b[i].toInt() and mask[i].toInt())) return false
        }
        return true
    }
}
