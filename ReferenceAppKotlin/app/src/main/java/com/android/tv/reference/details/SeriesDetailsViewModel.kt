/*
 * ViewModel for SeriesDetailsFragment: loads all episodes for a given series and groups them by season.
 */
package com.android.tv.reference.details

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.android.tv.reference.BuildConfig
import com.android.tv.reference.repository.VideoRepositoryFactory
import com.android.tv.reference.shared.datamodel.Video
import com.android.tv.reference.shared.datamodel.VideoGroup
import com.android.tv.reference.shared.util.AppConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class SeriesDetailsViewModel(application: Application) : AndroidViewModel(application) {
    private val videoRepository = VideoRepositoryFactory.getVideoRepository(application)

    val seasonGroups = MutableLiveData<List<VideoGroup>>()
    val isLoading = MutableLiveData<Boolean>(false)
    val errorMessage = MutableLiveData<String?>(null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class GamerEpisodesResponse(val success: Boolean, val description: String?, val count: Int?, val episodes: List<GamerEpisode>)
    data class GamerEpisode(val title: String, val originalUrl: String, val videoUri: String)

    fun loadFrom(video: Video) {
        initialVideo = video

        // 🔥 增加詳細日誌
        Timber.d("【集數】loadFrom 被呼叫")
        Timber.d("【集數】影片名稱: ${video.name}")
        Timber.d("【集數】category: ${video.category}")
        Timber.d("【集數】seriesUri: ${video.seriesUri}")
        Timber.d("【集數】episodeUrl: ${video.episodeUrl}")
        Timber.d("【集數】videoUri: ${video.videoUri}")

        if (video.seriesUri.isBlank()) {
            Timber.d("【集數】seriesUri 為空，直接顯示單集")
            seasonGroups.postValue(listOf(VideoGroup("Episodes", listOf(video))))
            return
        }
        fetchEpisodes(video)
    }

    private lateinit var initialVideo: Video

    private fun fetchEpisodes(video: Video) = viewModelScope.launch(Dispatchers.IO) {
        isLoading.postValue(true)
        errorMessage.postValue(null)
        try {
            // 🔥 優先使用 API 提供的 episodeUrl
            val episodeApiUrl = if (video.episodeUrl.isNotBlank()) {
                Timber.d("【集數】使用 API 提供的 episodeUrl: ${video.episodeUrl}")
                video.episodeUrl
            } else {
                // 如果沒有 episodeUrl，則自己構建（向後兼容）
                val encoded = URLEncoder.encode(video.seriesUri, StandardCharsets.UTF_8.toString())
                val serviceName = detectServiceName(video)
                // 🔥 使用動態配置的 Base URL
                val constructedUrl = AppConfig.getBaseUrl().trimEnd('/') + "/$serviceName/episodes?url=" + encoded
                Timber.d("【集數】自己構建 episodeUrl: $constructedUrl")
                constructedUrl
            }

            Timber.d("【集數】正在從 $episodeApiUrl 獲取影片集數...")
            val request = Request.Builder().url(episodeApiUrl).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.e("【集數】Episodes API 返回錯誤: HTTP ${resp.code}")
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                Timber.d("【集數】成功獲取 Episodes 響應，長度: ${body.length} 字節")

                // 🔥 Gamer 和 Anime1 都使用相同的格式，統一解析
                parseEpisodes(body, video)
            }
        } catch (e: Exception) {
            Timber.e(e, "【集數】從遠端載入集數列表失敗")
            withContext(Dispatchers.Main) {
                errorMessage.value = "載入失敗：${e.message}"
                showFallbackVideo(video)
            }
        } finally {
            withContext(Dispatchers.Main) {
                isLoading.value = false
            }
        }
    }

    /**
     * 🔥 解析集數列表格式（Gamer 和 Anime1 格式相同）
     */
    private suspend fun parseEpisodes(body: String, video: Video) {
        try {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(GamerEpisodesResponse::class.java)

            Timber.d("【集數】解析集數列表")
            val parsed = adapter.fromJson(body)

            if (parsed == null) {
                Timber.e("【集數】解析 JSON 結果為 null")
                throw IllegalStateException("解析 JSON 結果為 null")
            }

            val eps = parsed.episodes ?: emptyList()
            Timber.i("【集數】成功解析 Episodes 列表，共 ${eps.size} 集")

            if (eps.isEmpty()) {
                Timber.w("【集數】API 回傳的集數列表為空")
                throw IllegalStateException("API 返回的集數列表為空")
            }

            val videos = eps.map { ep ->
                Video(
                    id = ep.originalUrl,
                    name = ep.title,  // 🔥 直接使用 API 返回的 title（例如："怪獸 8 號 第二季 [23]"）
                    description = parsed.description ?: "",
                    uri = ep.originalUrl,
                    videoUri = ep.videoUri,
                    thumbnailUri = initialVideo.thumbnailUri,
                    backgroundImageUri = initialVideo.backgroundImageUri,
                    category = initialVideo.category,
                    videoType = initialVideo.videoType,
                    duration = "PT00H00M",
                    seriesUri = initialVideo.seriesUri,
                    seasonUri = initialVideo.seasonUri,
                    episodeNumber = ep.title,
                    seasonNumber = "1",
                    episodeUrl = ""
                )
            }

            withContext(Dispatchers.Main) {
                Timber.d("【集數】發布 ${videos.size} 集到 UI")
                seasonGroups.value = listOf(VideoGroup(initialVideo.name, videos))
            }
        } catch (e: Exception) {
            Timber.e(e, "【集數】解析 Episodes JSON 失敗")
            Timber.e("【集數】原始回應：${body.take(1000)}")
            throw e
        }
    }

    /**
     * 🔥 顯示回退影片（發生錯誤時）
     */
    private suspend fun showFallbackVideo(video: Video) {
        val fallbackVideo = Video(
            id = video.id,
            name = "${video.name} (無法載入集數)",
            description = video.description,
            uri = video.uri,
            videoUri = video.videoUri,
            thumbnailUri = video.thumbnailUri,
            backgroundImageUri = video.backgroundImageUri,
            category = video.category,
            videoType = video.videoType,
            duration = video.duration,
            seriesUri = video.seriesUri,
            seasonUri = video.seasonUri,
            episodeNumber = video.episodeNumber,
            seasonNumber = video.seasonNumber,
            episodeUrl = video.episodeUrl
        )
        seasonGroups.value = listOf(VideoGroup(initialVideo.name, listOf(fallbackVideo)))
    }

    /**
     * 🔥 檢測影片來自哪個服務 (gamer, anime1, 等)
     */
    private fun detectServiceName(video: Video): String {
        // 方法 1: 從 category 中檢測 (例如: "gamer - 影片名稱")
        val categoryLower = video.category.lowercase()
        if (categoryLower.startsWith("gamer")) return "gamer"
        if (categoryLower.startsWith("anime1")) return "anime1"

        // 方法 2: 從 videoUri 中檢測 (例如: "http://...3000/gamer/m3u8?url=...")
        val videoUri = video.videoUri.lowercase()
        if (videoUri.contains("/gamer/")) return "gamer"
        if (videoUri.contains("/anime1/")) return "anime1"

        // 方法 3: 從 seriesUri 中檢測
        val seriesUri = video.seriesUri.lowercase()
        if (seriesUri.contains("gamer.com") || seriesUri.contains("ani.gamer")) return "gamer"
        if (seriesUri.contains("anime1.me")) return "anime1"

        // 預設使用 gamer
        Timber.w("【集數】無法檢測服務來源，使用預設值 gamer。影片: ${video.name}, category: ${video.category}")
        return "gamer"
    }
}
