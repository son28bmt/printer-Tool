package com.example.xptool

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Read-only adapters for attendance/access-control devices with documented HTTP APIs. */
internal object OtherAttendanceReaders {
    fun hikvision(ip: String, port: Int, user: String, password: String): List<AttendancePunch> {
        val endpoint = "http://${ip}:${port}/ISAPI/AccessControl/AcsEvent?format=json"
        val start = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000))
        val end = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val searchId = System.currentTimeMillis().toString()
        val found = mutableListOf<AttendancePunch>()
        var position = 0
        var total = 1
        while (position < total && position < 5000) {
            val request = JSONObject().put("AcsEventCond", JSONObject()
                .put("searchID", searchId)
                .put("searchResultPosition", position)
                .put("maxResults", 100)
                .put("major", 0)
                .put("minor", 0)
                .put("startTime", start)
                .put("endTime", end))
            val response = request(endpoint, user, password, "POST", request.toString(), "application/json")
            val events = JSONObject(response).optJSONObject("AcsEvent")
                ?: error("Thiết bị không trả dữ liệu ISAPI AcsEvent.")
            total = events.optInt("totalMatches", 0)
            val list = events.optJSONArray("InfoList")
            if (list != null) for (i in 0 until list.length()) {
                val event = list.optJSONObject(i) ?: continue
                val timestamp = event.optString("time")
                val time = parseDeviceTime(timestamp) ?: continue
                val id = event.optString("employeeNoString").ifBlank { event.optString("cardNo") }
                if (id.isNotBlank()) found += AttendancePunch(id, time, event.optInt("attendanceStatus"), 0)
            }
            val count = list?.length() ?: 0
            if (count == 0) break
            position += count
        }
        return found.sortedBy { it.time }
    }

    fun dahua(ip: String, port: Int, user: String, password: String): List<AttendancePunch> {
        val query = "http://${ip}:${port}/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCardRec&count=1024"
        val body = request(query, user, password, "GET")
        val fields = linkedMapOf<Int, MutableMap<String, String>>()
        val linePattern = Regex("(?:records\\[(\\d+)\\]\\.)?([A-Za-z0-9_]+)=(.*)")
        body.lineSequence().forEach { line ->
            val match = linePattern.matchEntire(line.trim()) ?: return@forEach
            val index = match.groupValues[1].toIntOrNull() ?: 0
            fields.getOrPut(index) { mutableMapOf() }[match.groupValues[2]] = match.groupValues[3].trim().trim('"')
        }
        val dateFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
        return fields.values.mapNotNull { record ->
            val id = record["UserID"].orEmpty().ifBlank { record["CardNo"].orEmpty() }
            val timeText = record["CreateTime"].orEmpty().ifBlank { record["Time"].orEmpty() }
            val time = dateFormats.firstNotNullOfOrNull { pattern ->
                runCatching { SimpleDateFormat(pattern, Locale.US).parse(timeText) }.getOrNull()
            } ?: timeText.toLongOrNull()?.let { Date(if (it < 10_000_000_000L) it * 1000 else it) }
            if (id.isBlank() || time == null) null else AttendancePunch(id, time, record["Status"]?.toIntOrNull() ?: 0, record["Method"]?.toIntOrNull() ?: 0)
        }.sortedBy { it.time }
    }

    private fun parseDeviceTime(value: String): Date? = runCatching {
        val normalized = if (Regex(".*[+-]\\d\\d:\\d\\d$").matches(value) || value.endsWith("Z")) value else "${value}+07:00"
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(normalized)
    }.getOrNull()

    private fun request(url: String, user: String, password: String, method: String, body: String? = null, contentType: String? = null): String {
        require(user.isNotBlank() && password.isNotBlank()) { "Nhập tài khoản và mật khẩu của máy." }
        var authorization: String? = null
        repeat(2) { attempt ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, text/plain, */*")
                if (authorization != null) setRequestProperty("Authorization", authorization)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status == 401 && attempt == 0) {
                    val challenge = connection.getHeaderField("WWW-Authenticate")
                    authorization = digestAuthorization(challenge, method, URL(url).file, user, password)
                        ?: error("Thiết bị yêu cầu xác thực không hỗ trợ Digest.")
                    return@repeat
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                check(status in 200..299) { "HTTP $status${if (response.isBlank()) "" else ": ${response.take(240)}"}" }
                return response
            } finally { connection.disconnect() }
        }
        error("Xác thực thất bại. Kiểm tra tài khoản/mật khẩu và quyền API trên thiết bị.")
    }

    private fun digestAuthorization(challenge: String?, method: String, uri: String, username: String, password: String): String? {
        if (challenge == null || !challenge.startsWith("Digest", true)) return null
        val params = Regex("([A-Za-z]+)=\"?([^,\"]+)\"?").findAll(challenge)
            .associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(',')?.firstOrNull { it.trim() == "auth" }
        val algorithm = params["algorithm"] ?: "MD5"
        if (!algorithm.equals("MD5", true) && !algorithm.equals("MD5-sess", true)) return null
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val nc = "00000001"
        fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        var ha1 = md5("$username:$realm:$password")
        if (algorithm.equals("MD5-sess", true)) ha1 = md5("$ha1:$nonce:$cnonce")
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"").append(username).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri)
                .append("\", response=\"").append(response).append("\", algorithm=").append(algorithm)
            if (qop != null) append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('\"')
            params["opaque"]?.let { append(", opaque=\"").append(it).append('\"') }
        }
    }
}
