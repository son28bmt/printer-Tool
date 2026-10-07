package com.example.xptool

/**
 * Nơi chứa các lệnh byte cấu hình máy in Xprinter bắt gói từ Tool PC V3.6C.
 * TUYỆT ĐỐI KHÔNG BỊA BYTE LỆNH. Mọi lệnh chưa bắt gói đều để null và verified = false.
 */
object CapturedCommands {

    data class CommandItem(
        val name: String,
        val verified: Boolean,
        val notes: String,
        val getBytes: ((param: Any?) -> ByteArray?)?
    )

    // 1. Density (Mật độ in)
    val DENSITY = CommandItem(
        name = "Density (Mật độ in)",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 2. Paper Width (Độ rộng giấy)
    val PAPER_WIDTH = CommandItem(
        name = "Width (Độ rộng giấy)",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 3. Cut with Beep (Cắt giấy kèm tiếng bíp)
    val CUT_WITH_BEEP = CommandItem(
        name = "Cut with beep",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 4. Sound and Light Alarm (Cảnh báo chuông & đèn)
    val SOUND_LIGHT_ALARM = CommandItem(
        name = "Sound and light alarm",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 5. Replay (In lại khi lỗi)
    val REPLAY_ON_ERROR = CommandItem(
        name = "Replay (In lại khi lỗi)",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 6. Print Config Page (In trang cấu hình)
    val PRINT_CONFIG_PAGE = CommandItem(
        name = "Print H / PrintCodePage (In trang cấu hình)",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )

    // 7. Restore Factory (Khôi phục cài đặt gốc)
    val RESTORE_FACTORY = CommandItem(
        name = "Restore factory (Khôi phục cài đặt gốc)",
        verified = false,
        notes = "Chờ dữ liệu bắt gói từ Tool PC V3.6C",
        getBytes = null
    )
}
