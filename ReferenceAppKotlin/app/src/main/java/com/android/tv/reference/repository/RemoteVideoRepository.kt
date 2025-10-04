package com.android.tv.reference.repository

import android.app.Application
import com.android.tv.reference.shared.datamodel.ApiResponse
import com.android.tv.reference.shared.datamodel.Video
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote repository hitting the gamer list endpoint: {baseUrl}/gamer/list
 */
class RemoteVideoRepository(override val application: Application, private val baseUrl: String) : VideoRepository {
    private val client = OkHttpClient()
    private val moshi = Moshi.Builder().build()
    private val type = Types.newParameterizedType(ApiResponse::class.java, Video::class.java)
    private val adapter = moshi.adapter<ApiResponse<Video>>(type)

    // Cached list (lazy loaded)
    @Volatile private var cache: List<Video>? = null
    private val loading = AtomicBoolean(false)

    private fun ensureLoaded() {
        if (cache != null || loading.get()) return
        synchronized(this) {
            if (cache != null) return
            loading.set(true)
            try {
                // Use runBlocking to ensure load completes before returning
                // but with IO dispatcher to avoid NetworkOnMainThreadException
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val url = baseUrl.trimEnd('/') + "/gamer/list"
                        Timber.d("正在從 $url 獲取影片列表...")
                        val req = Request.Builder().url(url).build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                Timber.e("API 返回錯誤: HTTP ${resp.code}")
                                throw IllegalStateException("HTTP ${resp.code}")
                            }
                            val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                            Timber.d("成功獲取 API 響應，長度: ${body.length} 字節")
                            try {
                                val parsed = adapter.fromJson(body)
                                val videoCount = parsed?.content?.size ?: 0
                                Timber.i("成功解析影片列表，共 $videoCount 部影片")
                                cache = parsed?.content ?: emptyList()
                            } catch (e: Exception) {
                                Timber.e(e, "解析 JSON 失敗，原始內容：${body.take(500)}...")
                                throw e
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "從遠端載入影片列表失敗")
                cache = emptyList()
            } finally {
                loading.set(false)
            }
        }
    }

    /**
     * 清除快取並強制重新載入資料
     * @return 新載入的影片列表
     */
    fun refresh(): List<Video> {
        synchronized(this) {
            cache = null
            loading.set(false)
        }
        return getAllVideos()
    }

    override fun getAllVideos(): List<Video> {
        ensureLoaded()
        return cache ?: emptyList()
    }

    override fun getVideoById(id: String): Video? = getAllVideos().firstOrNull { it.id == id }

    override fun getVideoByVideoUri(uri: String): Video? = getAllVideos().firstOrNull { it.videoUri == uri }

    override fun getAllVideosFromSeries(seriesUri: String): List<Video> = getAllVideos().filter { it.seriesUri == seriesUri }
}
