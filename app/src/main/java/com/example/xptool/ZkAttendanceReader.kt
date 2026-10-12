package com.example.xptool

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Date

internal data class AttendancePunch(
    val userId: String,
    val time: Date,
    val status: Int,
    val method: Int
)

internal object ZkAttendanceReader {
    private const val CMD_CONNECT = 1000
    private const val CMD_EXIT = 1001
    private const val CMD_ATTLOG_RRQ = 13
    private const val CMD_ACK_OK = 2000
    private const val CMD_ACK_UNAUTH = 2005
    private const val CMD_PREPARE_DATA = 1500
    private const val CMD_DATA = 1501
    private const val CMD_FREE_DATA = 1502
    private const val CMD_PREPARE_BUFFER = 1503
    private const val CMD_READ_BUFFER = 1504
    private const val TCP_MAGIC_1 = 20560
    private const val TCP_MAGIC_2 = 32130

    fun read(ip: String, port: Int, commKey: Int): List<AttendancePunch> {
        require(ip.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { "Nhập địa chỉ IPv4 hợp lệ." }
        require(ip.split('.').all { it.toInt() in 0..255 }) { "Địa chỉ IP không hợp lệ." }
        require(port in 1..65535) { "Cổng phải nằm trong khoảng 1–65535." }
        require(commKey >= 0) { "Khóa giao tiếp phải là số không âm." }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 5000)
            socket.soTimeout = 12000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            var session = 0
            var reply = 65534

            fun command(id: Int, body: ByteArray = byteArrayOf()): Frame {
                val nextReply = (reply + 1) % 65535
                val inner = packet(id, session, nextReply, body)
                val outer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(TCP_MAGIC_1.toShort()).putShort(TCP_MAGIC_2.toShort())
                    .putInt(inner.size).array()
                output.write(outer)
                output.write(inner)
                output.flush()
                val frame = readFrame(input)
                reply = frame.replyId
                return frame
            }

            val hello = command(CMD_CONNECT)
            session = hello.sessionId
            if (hello.command == CMD_ACK_UNAUTH) {
                val auth = command(1102, makeCommKey(commKey, session))
                check(auth.command == CMD_ACK_OK) { "Máy từ chối khóa giao tiếp (mã ${auth.command})." }
            } else {
                check(hello.command == CMD_ACK_OK) { "Không kết nối được máy (mã ${hello.command})." }
            }

            val request = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).putShort(CMD_ATTLOG_RRQ.toShort()).putInt(1).putInt(0).array()
            val response = command(CMD_PREPARE_BUFFER, request)
            val payload = when (response.command) {
                CMD_ACK_OK -> {
                    if (response.payload.size < 5) byteArrayOf()
                    else readBufferedData(response.payload, ::command)
                }
                CMD_DATA -> response.payload
                CMD_PREPARE_DATA -> {
                    check(response.payload.size >= 4) { "Máy trả dữ liệu chấm công không hợp lệ." }
                    val expected = leInt(response.payload, 0)
                    check(expected in 0..8_000_000) { "Kích thước dữ liệu máy trả về không hợp lệ." }
                    val transfer = readFrame(input)
                    check(transfer.command == CMD_DATA) { "Không nhận được gói dữ liệu chấm công." }
                    val collected = ByteArrayOutputStream(expected)
                    collected.write(transfer.payload)
                    if (collected.size() < expected) {
                        collected.write(readExact(input, expected - collected.size()))
                    }
                    check(collected.size() == expected) { "Dữ liệu nhận được không đủ kích thước máy đã báo." }
                    val ack = readFrame(input)
                    check(ack.command == CMD_ACK_OK) { "Máy chưa xác nhận hoàn tất truyền dữ liệu." }
                    collected.toByteArray()
                }
                else -> error("Máy từ chối đọc bản ghi (mã ${response.command}). Kiểm tra model/khóa giao tiếp.")
            }

            runCatching { command(CMD_FREE_DATA) }
            runCatching { command(CMD_EXIT) }
            return decode(payload)
        }
    }

    private data class Frame(val command: Int, val sessionId: Int, val replyId: Int, val payload: ByteArray)

    private fun readBufferedData(
        responsePayload: ByteArray,
        command: (Int, ByteArray) -> Frame
    ): ByteArray {
        val expected = leInt(responsePayload, 1)
        check(expected in 4..8_000_000) { "Kích thước dữ liệu máy trả về không hợp lệ." }
        val collected = ByteArrayOutputStream(expected)
        var offset = 0
        while (offset < expected) {
            val chunkSize = minOf(65472, expected - offset)
            val requestChunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offset).putInt(chunkSize).array()
            val chunk = command(CMD_READ_BUFFER, requestChunk)
            check(chunk.command == CMD_DATA) { "Không đọc được gói dữ liệu chấm công tại byte $offset (mã ${chunk.command})." }
            check(chunk.payload.size >= chunkSize) { "Gói dữ liệu chấm công bị thiếu byte." }
            collected.write(chunk.payload, 0, chunkSize)
            offset += chunkSize
        }
        return collected.toByteArray()
    }

    private fun packet(command: Int, session: Int, reply: Int, body: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(command.toShort()).putShort(0).putShort(session.toShort()).putShort(reply.toShort()).put(body)
        val bytes = buffer.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(2, checksum(bytes).toShort())
        return bytes
    }

    private fun checksum(bytes: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < bytes.size) {
            if (i != 2) sum += (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            i += 2
        }
        if (i < bytes.size) sum += bytes[i].toInt() and 0xff
        while (sum > 0xffff) sum -= 0xffff
        var result = sum.inv()
        while (result < 0) result += 0xffff
        return result
    }

    private fun readFrame(input: InputStream): Frame {
        val outer = readExact(input, 8)
        val header = ByteBuffer.wrap(outer).order(ByteOrder.LITTLE_ENDIAN)
        val magic1 = header.short.toInt() and 0xffff
        val magic2 = header.short.toInt() and 0xffff
        val length = header.int
        check(magic1 == TCP_MAGIC_1 && magic2 == TCP_MAGIC_2 && length in 8..8_100_000) {
            "Máy trả về gói mạng không hợp lệ."
        }
        val inner = readExact(input, length)
        val packet = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
        val command = packet.short.toInt() and 0xffff
        packet.short
        val session = packet.short.toInt() and 0xffff
        val reply = packet.short.toInt() and 0xffff
        return Frame(command, session, reply, inner.copyOfRange(8, inner.size))
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            check(read >= 0) { "Kết nối bị ngắt giữa chừng khi đang nhận dữ liệu." }
            offset += read
        }
        return result
    }

    private fun makeCommKey(key: Int, session: Int): ByteArray {
        var reversed = 0L
        for (bit in 0 until 32) if (((key.toLong() shr bit) and 1L) != 0L) reversed = (reversed shl 1) or 1L else reversed = reversed shl 1
        val value = (reversed + session) and 0xffffffffL
        val raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        val mixed = byteArrayOf(
            (raw[0].toInt() xor 'Z'.code).toByte(),
            (raw[1].toInt() xor 'K'.code).toByte(),
            (raw[2].toInt() xor 'S'.code).toByte(),
            (raw[3].toInt() xor 'O'.code).toByte()
        )
        val swapped = byteArrayOf(mixed[2], mixed[3], mixed[0], mixed[1])
        for (i in swapped.indices) swapped[i] = if (i == 2) 50 else (swapped[i].toInt() xor 50).toByte()
        return swapped
    }

    private fun decode(payload: ByteArray): List<AttendancePunch> {
        if (payload.size < 4) return emptyList()
        val dataSize = leInt(payload, 0)
        if (dataSize <= 0) return emptyList()
        check(dataSize <= payload.size - 4) { "Dữ liệu nhận được không đủ kích thước máy đã báo." }
        val bytes = payload.copyOfRange(4, minOf(payload.size, dataSize + 4))
        val recordSize = when {
            bytes.size % 40 == 0 -> 40
            bytes.size % 16 == 0 -> 16
            bytes.size % 8 == 0 -> 8
            else -> error("Không nhận diện được định dạng bản ghi chấm công (${bytes.size} byte).")
        }
        return (0 until bytes.size / recordSize).mapNotNull { index ->
            val start = index * recordSize
            when (recordSize) {
                40 -> {
                    val userId = bytes.copyOfRange(start + 2, start + 26).takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.UTF_8).trim()
                    parsePunch(userId, leInt(bytes, start + 27), bytes[start + 31].toInt() and 0xff, bytes[start + 26].toInt() and 0xff)
                }
                16 -> parsePunch(
                    leInt(bytes, start).toUInt().toString(), leInt(bytes, start + 4),
                    bytes[start + 8].toInt() and 0xff, bytes[start + 9].toInt() and 0xff
                )
                else -> parsePunch(
                    leShort(bytes, start).toString(), leInt(bytes, start + 3),
                    bytes[start + 2].toInt() and 0xff, bytes[start + 7].toInt() and 0xff
                )
            }
        }.sortedBy { it.time }
    }

    private fun parsePunch(userId: String, packedTime: Int, status: Int, method: Int): AttendancePunch? {
        if (userId.isBlank()) return null
        return runCatching {
            var value = packedTime.toLong() and 0xffffffffL
            val second = (value % 60).toInt(); value /= 60
            val minute = (value % 60).toInt(); value /= 60
            val hour = (value % 24).toInt(); value /= 24
            val day = (value % 31).toInt() + 1; value /= 31
            val month = (value % 12).toInt() + 1; value /= 12
            val year = (value + 2000).toInt()
            val calendar = Calendar.getInstance().apply {
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }
            AttendancePunch(userId, calendar.time, status, method)
        }.getOrNull()
    }

    private fun leInt(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun leShort(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
}
