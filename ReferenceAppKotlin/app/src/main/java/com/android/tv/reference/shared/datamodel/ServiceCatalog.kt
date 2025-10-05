package com.android.tv.reference.shared.datamodel

import com.squareup.moshi.JsonClass

/**
 * 系統目錄 API 的回應格式
 */
@JsonClass(generateAdapter = true)
data class ServiceCatalog(
    val updated: String,
    val services: List<ServiceInfo>
)

/**
 * 單個服務的資訊
 */
@JsonClass(generateAdapter = true)
data class ServiceInfo(
    val site: String,
    val listUri: String,
    val episodesEntry: String?,
    val m3u8Entry: String?
)

