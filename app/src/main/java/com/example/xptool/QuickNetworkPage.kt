package com.example.xptool

/** Read-only network overview that links to the existing diagnostic and printer discovery flows. */
internal fun MainActivity.pageQuickNetwork() = showPage("Kiểm tra mạng nhanh", false) { body ->
    val network = phoneNet()
    val ssid = getConnectedSsid()
    val activePrinter = PrinterManager.getActivePrinter()

    body.addView(label("Thông tin kết nối hiện tại", 15f, true))
    body.addView(label("Wi-Fi: ${ssid ?: "Không đọc được tên Wi-Fi"}"))
    if (network == null) {
        body.addView(label("Chưa đọc được IP nội bộ. Hãy kết nối điện thoại với Wi-Fi rồi làm mới."))
    } else {
        body.addView(label("IP điện thoại: ${ipStr(network.ip)}"))
        body.addView(label("Subnet mask: ${ipStr(network.mask)}"))
        body.addView(label("Gateway: ${ipStr(network.gw)}"))

        body.addView(label("Máy in đang chọn", 15f, true))
        if (activePrinter == null) {
            body.addView(label("Chưa chọn máy in đã lưu."))
        } else {
            val printerIp = activePrinter.ip.split(".").takeIf { it.size == 4 }
                ?.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 }?.toByte() }
                ?.takeIf { octets -> octets.all { it != null } }
                ?.map { it!! }
                ?.toByteArray()
            val sameNetwork = sameSubnet(printerIp, network.ip, network.mask)
            body.addView(label("${activePrinter.name} · ${activePrinter.ip}:${activePrinter.port}"))
            body.addView(label(if (sameNetwork) "Máy in cùng dải mạng với điện thoại." else "Máy in khác dải mạng hoặc IP không hợp lệ."))
        }
    }

    body.addView(btn("Dò máy in trong mạng") { openFromQuickNetwork { pageNetwork() } })
    body.addView(btn("Chẩn đoán kết nối máy in") { openFromQuickNetwork { pageDiagnostics() } })
    body.addView(label("Dò máy in dùng luồng tìm máy in LAN hiện có. Chẩn đoán sẽ kiểm tra Wi-Fi, gateway, dải IP và cổng kết nối.", 12f).apply {
        setTextColor(colorMuted)
    })
}
