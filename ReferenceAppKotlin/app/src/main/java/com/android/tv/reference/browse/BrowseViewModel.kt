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
import com.android.tv.reference.R
import com.android.tv.reference.auth.UserManager
import com.android.tv.reference.repository.VideoRepository
import com.android.tv.reference.repository.VideoRepositoryFactory
import com.android.tv.reference.repository.RemoteVideoRepository
import com.android.tv.reference.shared.datamodel.VideoGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val videoRepository = VideoRepositoryFactory.getVideoRepository(application)
    private val userManager = UserManager.getInstance(application.applicationContext)
    val browseContent = MutableLiveData<List<VideoGroup>>()
    val customMenuItems = MutableLiveData<List<BrowseCustomMenu>>(listOf())
    val isSignedIn = Transformations.map(userManager.userInfo) { it != null }
    val isLoading = MutableLiveData<Boolean>(false)
    val loadingMessage = MutableLiveData<String?>(null)

    // Navigation events
    val navigateToSignIn = MutableLiveData<Boolean>()

    init {
        loadContent()
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

        // 添加刷新選單
        menuItems.add(BrowseCustomMenu("操作", listOf(refreshMenuItem)))

        // 添加登入/登出選單
        menuItems.add(BrowseCustomMenu(
            getString(R.string.menu_identity),
            listOf(if (isSignedIn.value == true) signOutMenuItem else signInMenuItem)
        ))

        customMenuItems.value = menuItems
    }

    private fun getString(resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun loadContent() {
        browseContent.value = getVideoGroupList(videoRepository)
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.Main) {
            isLoading.value = true
            loadingMessage.value = "正在刷新內容..."

            launch(Dispatchers.IO) {
                if (videoRepository is RemoteVideoRepository) {
                    videoRepository.refresh()
                }

                launch(Dispatchers.Main) {
                    loadContent()
                    isLoading.value = false
                    loadingMessage.value = null
                }
            }
        }
    }

    fun getVideoGroupList(repository: VideoRepository): List<VideoGroup> {
        return if (repository is RemoteVideoRepository) {
            val videos = repository.getAllVideos()
            videos.map { vid -> VideoGroup(vid.name, listOf(vid)) }
        } else {
            val videosByCategory = repository.getAllVideos().groupBy { it.category }
            val videoGroupList = mutableListOf<VideoGroup>()
            videosByCategory.forEach { (k, v) ->
                videoGroupList.add(VideoGroup(k, v))
            }
            videoGroupList
        }
    }

    fun signOut() = viewModelScope.launch(Dispatchers.IO) {
        userManager.signOut()
    }

    fun onNavigationHandled() {
        navigateToSignIn.value = false
    }
}
