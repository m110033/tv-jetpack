package com.android.tv.reference.shared.util

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 應用程式配置管理器
 * 從遠端 Google Drive 下載配置文件來獲取動態的 Base URL
 */
object AppConfig {
    private const val PREFS_NAME = "app_config"
    private const val KEY_BASE_URL = "gamer_base_url"
    private const val CONFIG_URL = "https://gist.githubusercontent.com/m110033/7ba14b6ec7005bdde53d27c5db75ba47/raw/f636ad9fb1879ac27fa92117d7e85b00f1980aa6/config.json"
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:3000"

    @JsonClass(generateAdapter = true)
    data class RemoteConfig(val url: String)

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 🔥 跟隨重定向
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * 初始化配置管理器
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Timber.d("【配置】AppConfig 已初始化")
    }

    /**
     * 獲取當前的 Base URL（同步方法）
     */
    fun getBaseUrl(): String {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    /**
     * 從遠端下載配置並更新 Base URL（異步方法）
     * @return 是否成功更新配置
     */
    suspend fun fetchAndUpdateConfig(): Boolean = withContext(Dispatchers.IO) {
        if (CONFIG_URL.isBlank()) {
            Timber.d("【配置】CONFIG_URL 為空，跳過遠端下載，使用預設值: $DEFAULT_BASE_URL")
            prefs.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
            return@withContext true
        }

        try {
            Timber.d("【配置】正在從遠端下載配置: $CONFIG_URL")

            val request = Request.Builder()
                .url(CONFIG_URL)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("【配置】下載配置失敗: HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Timber.e("【配置】配置文件內容為空")
                    return@withContext false
                }

                Timber.d("【配置】收到配置內容: $body")

                // 解析 JSON
                val adapter = moshi.adapter(RemoteConfig::class.java)
                val config = adapter.fromJson(body)

                if (config == null || config.url.isBlank()) {
                    Timber.e("【配置】解析配置失敗或 URL 為空")
                    return@withContext false
                }

                // 儲存到 SharedPreferences
                prefs.edit().putString(KEY_BASE_URL, config.url).apply()
                Timber.i("【配置】成功更新 Base URL: ${config.url}")

                return@withContext true
            }
        } catch (e: Exception) {
            Timber.e(e, "【配置】下載或解析配置時發生錯誤")
            return@withContext false
        }
    }

    /**
     * 手動設置 Base URL（用於測試或離線模式）
     */
    fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url).apply()
        Timber.d("【配置】手動設置 Base URL: $url")
    }

    /**
     * 清除配置，恢復為預設值
     */
    fun clearConfig() {
        prefs.edit().remove(KEY_BASE_URL).apply()
        Timber.d("【配置】已清除配置，恢復為預設值")
    }
}
