package com.example.xptool

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Read-only SNMPv1 walk of IF-MIB::ifPhysAddress for one host. */
internal object SnmpMacProbe {
    private const val IF_PHYS_ADDRESS_PREFIX = "1.3.6.1.2.1.2.2.1.6"
    private val requestIds = AtomicInteger(1)

    fun readMacAddresses(ip: String, community: String, timeoutMs: Int = 400): List<String> {
        val cleanCommunity = community.trim()
        if (cleanCommunity.isEmpty()) return emptyList()

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(InetAddress.getByName(ip), 161))
                var currentOid = "$IF_PHYS_ADDRESS_PREFIX.0"
                val found = linkedSetOf<String>()

                repeat(32) {
                    val requestId = requestIds.getAndIncrement()
                    val request = createGetNextRequest(requestId, cleanCommunity, currentOid)
                    socket.send(DatagramPacket(request, request.size))
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.port != 161 || packet.address.hostAddress != ip) return@use found.toList()

                    val binding = parseFirstBinding(packet.data.copyOf(packet.length)) ?: return@use found.toList()
                    if (!binding.oid.startsWith("$IF_PHYS_ADDRESS_PREFIX.")) return@use found.toList()
                    currentOid = binding.oid

                    val mac = binding.value
                        .takeIf { binding.valueTag == 0x04 && it.size in 6..8 && it.any { byte -> byte != 0.toByte() } }
                        ?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
                    if (mac != null) found.add(mac)
                }
                found.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class Binding(val oid: String, val valueTag: Int, val value: ByteArray)
    private data class Tlv(val tag: Int, val value: ByteArray, val next: Int)

    private fun createGetNextRequest(requestId: Int, community: String, oid: String): ByteArray {
        val varBind = sequence(oidTlv(oid), tlv(0x05, byteArrayOf()))
        val varBindList = sequence(varBind)
        val pdu = tlv(0xA1, sequence(
            integer(requestId),
            integer(0),
            integer(0),
            varBindList
        ))
        return sequence(integer(0), tlv(0x04, community.toByteArray(Charsets.US_ASCII)), pdu)
    }

    private fun parseFirstBinding(bytes: ByteArray): Binding? {
        val message = readTlv(bytes, 0)?.takeIf { it.tag == 0x30 } ?: return null
        val version = readTlv(bytes, message.valueStart()) ?: return null
        val community = readTlv(bytes, version.next) ?: return null
        val pdu = readTlv(bytes, community.next)?.takeIf { it.tag == 0xA2 } ?: return null
        var cursor = pdu.valueStart()
        val requestId = readTlv(bytes, cursor) ?: return null
        cursor = requestId.next
        val errorStatus = readTlv(bytes, cursor) ?: return null
        if (decodeInt(errorStatus.value) != 0) return null
        cursor = errorStatus.next
        val errorIndex = readTlv(bytes, cursor) ?: return null
        cursor = errorIndex.next
        val bindings = readTlv(bytes, cursor)?.takeIf { it.tag == 0x30 } ?: return null
        val binding = readTlv(bytes, bindings.valueStart())?.takeIf { it.tag == 0x30 } ?: return null
        val oid = readTlv(bytes, binding.valueStart())?.takeIf { it.tag == 0x06 } ?: return null
        val value = readTlv(bytes, oid.next) ?: return null
        return Binding(decodeOid(oid.value) ?: return null, value.tag, value.value)
    }

    private fun Tlv.valueStart(): Int = next - value.size

    private fun decodeInt(bytes: ByteArray): Int = BigInteger(bytes).toInt()

    private fun decodeOid(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val subIds = mutableListOf<Long>()
        var value = 0L
        bytes.forEach { byte ->
            val part = byte.toInt() and 0xFF
            value = (value shl 7) or (part and 0x7F).toLong()
            if ((part and 0x80) == 0) {
                subIds.add(value)
                value = 0
            }
        }
        if (subIds.isEmpty()) return null
        val first = subIds.removeAt(0)
        val firstArc = when {
            first < 40 -> 0
            first < 80 -> 1
            else -> 2
        }
        val secondArc = first - firstArc * 40
        return (listOf(firstArc.toLong(), secondArc) + subIds).joinToString(".")
    }

    private fun readTlv(bytes: ByteArray, start: Int): Tlv? {
        if (start < 0 || start + 2 > bytes.size) return null
        val tag = bytes[start].toInt() and 0xFF
        var cursor = start + 1
        val firstLength = bytes[cursor++].toInt() and 0xFF
        val length = if ((firstLength and 0x80) == 0) {
            firstLength
        } else {
            val byteCount = firstLength and 0x7F
            if (byteCount == 0 || byteCount > 4 || cursor + byteCount > bytes.size) return null
            var parsed = 0
            repeat(byteCount) { parsed = (parsed shl 8) or (bytes[cursor++].toInt() and 0xFF) }
            parsed
        }
        if (length < 0 || cursor + length > bytes.size) return null
        return Tlv(tag, bytes.copyOfRange(cursor, cursor + length), cursor + length)
    }

    private fun integer(value: Int): ByteArray = tlv(0x02, BigInteger.valueOf(value.toLong()).toByteArray())

    private fun oidTlv(oid: String): ByteArray {
        val arcs = oid.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs[1] >= 0)
        val encoded = ByteArrayOutputStream()
        encodeBase128(arcs[0] * 40 + arcs[1], encoded)
        arcs.drop(2).forEach { encodeBase128(it, encoded) }
        return tlv(0x06, encoded.toByteArray())
    }

    private fun encodeBase128(value: Long, output: ByteArrayOutputStream) {
        require(value >= 0)
        var remaining = value
        val groups = mutableListOf<Int>((remaining and 0x7F).toInt())
        remaining = remaining ushr 7
        while (remaining > 0) {
            groups.add((remaining and 0x7F).toInt())
            remaining = remaining ushr 7
        }
        for (index in groups.indices.reversed()) {
            val continuation = if (index > 0) 0x80 else 0
            output.write(groups[index] or continuation)
        }
    }

    private fun sequence(vararg values: ByteArray): ByteArray = tlv(0x30, values.fold(ByteArrayOutputStream()) { out, bytes ->
        out.write(bytes)
        out
    }.toByteArray())

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(tag)
        if (value.size < 128) {
            output.write(value.size)
        } else {
            var length = value.size
            val bytes = mutableListOf<Int>()
            while (length > 0) {
                bytes.add(length and 0xFF)
                length = length ushr 8
            }
            output.write(0x80 or bytes.size)
            bytes.asReversed().forEach { byte -> output.write(byte) }
        }
        output.write(value)
        return output.toByteArray()
    }
}
