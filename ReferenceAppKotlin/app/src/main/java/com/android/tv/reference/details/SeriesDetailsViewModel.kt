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

    private val client = OkHttpClient()

    data class GamerEpisodesResponse(val success: Boolean, val description: String?, val count: Int?, val episodes: List<GamerEpisode>)
    data class GamerEpisode(val title: String, val originalUrl: String, val videoUri: String)

    fun loadFrom(video: Video) {
        initialVideo = video
        if (video.seriesUri.isBlank()) {
            seasonGroups.postValue(listOf(VideoGroup("Episodes", listOf(video))))
            return
        }
        fetchEpisodesGamer(video)
    }

    private lateinit var initialVideo: Video

    private fun fetchEpisodesGamer(video: Video) = viewModelScope.launch(Dispatchers.IO) {
        isLoading.postValue(true)
        errorMessage.postValue(null)
        try {
            val encoded = URLEncoder.encode(video.seriesUri, StandardCharsets.UTF_8.toString())
            val url = BuildConfig.GAMER_BASE_URL.trimEnd('/') + "/gamer/episodes?url=" + encoded
            Timber.d("【集數】正在從 $url 獲取影片集數...")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.e("【集數】Episodes API 返回錯誤: HTTP ${resp.code}")
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                Timber.d("【集數】成功獲取 Episodes 響應，長度: ${body.length} 字節")
                try {
                    // 使用 KotlinJsonAdapterFactory 來正確處理 Kotlin 類別
                    val moshi = Moshi.Builder()
                        .add(KotlinJsonAdapterFactory())
                        .build()
                    val adapter = moshi.adapter(GamerEpisodesResponse::class.java)

                    Timber.d("【集數】原始 JSON 回應：$body")
                    val parsed = adapter.fromJson(body)

                    if (parsed == null) {
                        Timber.e("【集數】解析 JSON 結果為 null")
                        throw IllegalStateException("解析 JSON 結果為 null")
                    }

                    Timber.d("【集數】解析結果 success=${parsed.success}, count=${parsed.count}")

                    val eps = parsed.episodes ?: emptyList()
                    Timber.i("【集數】成功解析 Episodes 列表，共 ${eps.size} 集")

                    if (eps.isEmpty()) {
                        Timber.w("【集數】API 回傳的集數列表為空")
                        throw IllegalStateException("API 返回的集數列表為空")
                    }

                    // 打印每一集的資訊用於調試
                    eps.forEachIndexed { index, ep ->
                        Timber.d("【集數】集數 #$index: title=${ep.title}, uri=${ep.videoUri}")
                    }

                    val videos = eps.map { ep ->
                        Video(
                            id = ep.originalUrl,
                            name = "第 ${ep.title} 集",
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
                            seasonNumber = "1"
                        )
                    }

                    Timber.d("【集數】集數映射後共有 ${videos.size} 個 Video 對象")

                    // 確保在主線程更新UI並加強日誌
                    withContext(Dispatchers.Main) {
                        Timber.d("【集數】即將發布集數到UI，標題: ${initialVideo.name}，集數: ${videos.size}")
                        seasonGroups.value = listOf(VideoGroup(initialVideo.name, videos))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "【集數】解析 Episodes JSON 失敗")
                    Timber.e("【集數】原始回應內容：${body.take(1000)}")
                    throw e
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "【集數】從遠端載入集數列表失敗")

            // 確保在主線程更新UI
            withContext(Dispatchers.Main) {
                errorMessage.value = "載入失敗：${e.message}"
                // 使用新建 Video 物件而非 copy()
                Timber.d("【集數】設置回退選項顯示原始影片")
                val fallbackVideo = Video(
                    id = video.id,
                    name = "${video.name} (無法載入其他集數)",
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
                    seasonNumber = video.seasonNumber
                )
                seasonGroups.value = listOf(VideoGroup(initialVideo.name, listOf(fallbackVideo)))
            }
        } finally {
            withContext(Dispatchers.Main) {
                isLoading.value = false
            }
        }
    }
}
