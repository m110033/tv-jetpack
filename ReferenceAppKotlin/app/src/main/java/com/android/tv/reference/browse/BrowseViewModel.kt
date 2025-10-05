/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tv.reference.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.viewModelScope
import com.android.tv.reference.BuildConfig
import com.android.tv.reference.R
import com.android.tv.reference.auth.UserManager
import com.android.tv.reference.repository.VideoRepository
import com.android.tv.reference.repository.VideoRepositoryFactory
import com.android.tv.reference.repository.RemoteVideoRepository
import com.android.tv.reference.shared.datamodel.ApiResponse
import com.android.tv.reference.shared.datamodel.ServiceCatalog
import com.android.tv.reference.shared.datamodel.ServiceInfo
import com.android.tv.reference.shared.datamodel.Video
import com.android.tv.reference.shared.datamodel.VideoGroup
import com.android.tv.reference.shared.util.AppConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val videoRepository = VideoRepositoryFactory.getVideoRepository(application)
    private val userManager = UserManager.getInstance(application.applicationContext)

    val browseContent = MutableLiveData<List<VideoGroup>>()
    val customMenuItems = MutableLiveData<List<BrowseCustomMenu>>(listOf())
    val isSignedIn = Transformations.map(userManager.userInfo) { it != null }
    val isLoading = MutableLiveData<Boolean>(false)
    val loadingMessage = MutableLiveData<String?>(null)
    val navigateToSignIn = MutableLiveData<Boolean>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 儲存當前載入的服務列表
    private var services: List<ServiceInfo> = emptyList()

    init {
        loadCatalog()
        updateMenuItems()

        // 觀察登入狀態變化，更新選單
        isSignedIn.observeForever {
            updateMenuItems()
        }
    }

    private fun updateMenuItems() {
        val refreshMenuItem = BrowseCustomMenu.MenuItem("刷新") {
            refresh()
        }

        val signInMenuItem = BrowseCustomMenu.MenuItem(getString(R.string.sign_in)) {
            navigateToSignIn.value = true
        }

        val signOutMenuItem = BrowseCustomMenu.MenuItem(getString(R.string.sign_out)) {
            signOut()
        }

        val menuItems = mutableListOf<BrowseCustomMenu>()
        menuItems.add(BrowseCustomMenu("操作", listOf(refreshMenuItem)))
        menuItems.add(BrowseCustomMenu(
            getString(R.string.menu_identity),
            listOf(if (isSignedIn.value == true) signOutMenuItem else signInMenuItem)
        ))

        customMenuItems.value = menuItems
    }

    private fun getString(resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    /**
     * 載入系統目錄，獲取所有可用的服務
     */
    private fun loadCatalog() = viewModelScope.launch(Dispatchers.IO) {
        try {
            Timber.d("【目錄】正在載入系統目錄...")
            isLoading.postValue(true)
            loadingMessage.postValue("正在載入服務列表...")

            // 🔥 使用動態配置的 Base URL
            val catalogUrl = AppConfig.getBaseUrl().trimEnd('/') + "/system/catalog"
            Timber.d("【目錄】使用 Base URL: ${AppConfig.getBaseUrl()}")
            val request = Request.Builder().url(catalogUrl).build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.e("【目錄】Catalog API 返回錯誤: HTTP ${resp.code}")
                    throw IllegalStateException("HTTP ${resp.code}")
                }

                val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                Timber.d("【目錄】收到 catalog 回應: ${body.take(200)}")

                val moshi = Moshi.Builder()
                    .add(KotlinJsonAdapterFactory())
                    .build()
                val adapter = moshi.adapter(ServiceCatalog::class.java)
                val catalog = adapter.fromJson(body)

                services = catalog?.services ?: emptyList()
                Timber.i("【目錄】成功載入 ${services.size} 個服務")

                // 🔥 先建立空的分類列表，讓 UI 立即顯示分類名稱
                withContext(Dispatchers.Main) {
                    val videoGroups = services.map { service ->
                        VideoGroup(service.site.uppercase(), emptyList())
                    }
                    browseContent.value = videoGroups
                }

                // 🔥 非同步載入所有服務的影片列表
                loadAllServiceVideos()
            }
        } catch (e: Exception) {
            Timber.e(e, "【目錄】載入系統目錄失敗")
            withContext(Dispatchers.Main) {
                browseContent.value = listOf(VideoGroup("錯誤", emptyList()))
            }
        } finally {
            isLoading.postValue(false)
            loadingMessage.postValue(null)
        }
    }

    /**
     * 🔥 非同步載入所有服務的影片列表
     */
    private fun loadAllServiceVideos() = viewModelScope.launch(Dispatchers.IO) {
        val allVideosMap = mutableMapOf<String, List<Video>>()

        // 依序載入每個服務的影片（先載入第一個，再載入第二個）
        services.forEach { service ->
            try {
                Timber.d("【影片列表】正在載入 ${service.site} 的影片列表...")

                val request = Request.Builder().url(service.listUri).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.e("【影片列表】${service.site} API 返回錯誤: HTTP ${resp.code}")
                        allVideosMap[service.site] = emptyList()
                        return@use
                    }

                    val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                    Timber.d("【影片列表】${service.site} 收到回應，長度: ${body.length}")

                    val moshi = Moshi.Builder()
                        .add(KotlinJsonAdapterFactory())
                        .build()
                    val type = Types.newParameterizedType(ApiResponse::class.java, Video::class.java)
                    val adapter = moshi.adapter<ApiResponse<Video>>(type)
                    val parsed = adapter.fromJson(body)

                    val videos = parsed?.content ?: emptyList()
                    Timber.i("【影片列表】${service.site} 成功載入 ${videos.size} 部影片")
                    allVideosMap[service.site] = videos

                    // 🔥 每載入完一個服務就立即更新 UI
                    withContext(Dispatchers.Main) {
                        updateBrowseContentWithVideos(allVideosMap)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "【影片列表】載入 ${service.site} 失敗")
                allVideosMap[service.site] = emptyList()
            }
        }
    }

    /**
     * 🔥 更新瀏覽內容，包含已載入的影片
     */
    private fun updateBrowseContentWithVideos(videosMap: Map<String, List<Video>>) {
        val updatedGroups = services.map { service ->
            val videos = videosMap[service.site] ?: emptyList()
            VideoGroup(service.site.uppercase(), videos)
        }
        browseContent.value = updatedGroups
    }

    /**
     * 載入特定服務的影片列表（保留此方法以備刷新使用）
     */
    fun loadVideosForService(service: ServiceInfo) = viewModelScope.launch(Dispatchers.IO) {
        try {
            Timber.d("【影片列表】正在載入 ${service.site} 的影片列表...")
            isLoading.postValue(true)
            loadingMessage.postValue("正在載入 ${service.site} 影片...")

            val request = Request.Builder().url(service.listUri).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.e("【影片列表】List API 返回錯誤: HTTP ${resp.code}")
                    throw IllegalStateException("HTTP ${resp.code}")
                }

                val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                Timber.d("【影片列表】收到 list 回應，長度: ${body.length}")

                val moshi = Moshi.Builder()
                    .add(KotlinJsonAdapterFactory())
                    .build()
                val type = Types.newParameterizedType(ApiResponse::class.java, Video::class.java)
                val adapter = moshi.adapter<ApiResponse<Video>>(type)
                val parsed = adapter.fromJson(body)

                val videos = parsed?.content ?: emptyList()
                Timber.i("【影片列表】成功載入 ${videos.size} 部影片")

                withContext(Dispatchers.Main) {
                    // 更新對應服務的影片列表
                    val updatedGroups = services.map { s ->
                        if (s.site == service.site) {
                            VideoGroup(s.site.uppercase(), videos)
                        } else {
                            VideoGroup(s.site.uppercase(), emptyList())
                        }
                    }
                    browseContent.value = updatedGroups
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "【影片列表】載入影片列表失敗")
        } finally {
            isLoading.postValue(false)
            loadingMessage.postValue(null)
        }
    }

    /**
     * 根據分類名稱載入影片（現在不需要了，因為已經預載）
     */
    fun loadVideosByCategory(categoryName: String) {
        // 🔥 不再需要載入，因為所有影片已經預載完成
        Timber.d("【分類】切換到分類: $categoryName（影片已預載）")
    }

    fun refresh() {
        loadCatalog()
    }

    fun signOut() = viewModelScope.launch(Dispatchers.IO) {
        userManager.signOut()
    }

    fun onNavigationHandled() {
        navigateToSignIn.value = false
    }
}
