package com.example.xptool

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class PrintSetupTemplate(
    val id: String,
    var name: String,
    var config: JSONObject
)

internal data class PrintSetupTemplateSet(
    val templates: MutableList<PrintSetupTemplate>,
    var selectedId: String
)

internal object PrintSetupTemplateStore {
    fun load(preferences: SharedPreferences, printerId: String, defaultPaperWidth: Int): PrintSetupTemplateSet {
        val raw = runCatching { JSONObject(preferences.getString(printerId, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val templates = mutableListOf<PrintSetupTemplate>()
        val array = raw.optJSONArray("templates")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val config = item.optJSONObject("config") ?: JSONObject()
                templates += PrintSetupTemplate(id, item.optString("name", "Mẫu ${index + 1}"), JSONObject(config.toString()))
            }
        } else if (raw.length() > 0) {
            // Migrate the former single-template object without discarding any saved fixed fields.
            templates += PrintSetupTemplate(UUID.randomUUID().toString(), "Mẫu chính", JSONObject(raw.toString()))
        }
        if (templates.isEmpty()) {
            templates += PrintSetupTemplate(UUID.randomUUID().toString(), "Hóa đơn chính", defaultConfig(defaultPaperWidth))
        }
        val selectedId = raw.optString("selectedTemplateId").takeIf { id -> templates.any { it.id == id } } ?: templates.first().id
        return PrintSetupTemplateSet(templates, selectedId).also { save(preferences, printerId, it) }
    }

    fun save(preferences: SharedPreferences, printerId: String, set: PrintSetupTemplateSet) {
        val array = JSONArray()
        set.templates.forEach { template ->
            array.put(JSONObject()
                .put("id", template.id)
                .put("name", template.name)
                .put("config", JSONObject(template.config.toString())))
        }
        preferences.edit().putString(printerId, JSONObject()
            .put("selectedTemplateId", set.selectedId)
            .put("templates", array)
            .toString()).apply()
    }

    fun newTemplate(name: String, source: PrintSetupTemplate): PrintSetupTemplate =
        PrintSetupTemplate(UUID.randomUUID().toString(), name, JSONObject(source.config.toString()))

    private fun defaultConfig(paperWidth: Int) = JSONObject()
        .put("shopName", "CỬA HÀNG")
        .put("shopPhone", "0900 000 000")
        .put("shopAddress", "Địa chỉ cửa hàng")
        .put("documentTitle", "HÓA ĐƠN BÁN HÀNG")
        .put("footer", "Cảm ơn quý khách!")
        .put("labelTitle", "SẢN PHẨM MẪU")
        .put("labelNote", "Giá: 35.000đ")
        .put("labelCode", "SP-001")
        .put("logoUri", "")
        .put("kind", 0)
        .put("paperWidth", if (paperWidth >= 80) 80 else 58)
        .put("labelSize", 0)
        .put("labelType", 0)
}
