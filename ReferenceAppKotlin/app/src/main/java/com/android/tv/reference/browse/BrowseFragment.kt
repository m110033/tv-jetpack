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

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.android.tv.reference.R
import com.android.tv.reference.shared.datamodel.Video
import com.android.tv.reference.shared.datamodel.VideoType
import com.android.tv.reference.shared.image.BlurImageTransformation
import com.android.tv.reference.shared.image.OverlayImageTransformation
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import timber.log.Timber

/**
 * Fragment displaying the main content browsing UI
 *
 * This shows a menu of categories and a row for each category. Users can pick which content to
 * play by navigating and pressing the main select button.
 */
class BrowseFragment : BrowseSupportFragment(), Target {

    companion object {
        private const val BACKGROUND_UPDATE_DELAY_MILLIS = 500L
        private val BACKGROUND_RESOURCE_ID = R.drawable.image_placeholder
    }

    private lateinit var viewModel: BrowseViewModel
    private lateinit var backgroundManager: BackgroundManager
    private lateinit var handler: Handler

    private val overlayImageTransformation =
        OverlayImageTransformation()
    private lateinit var blurImageTransformation: BlurImageTransformation

    // The DisplayMetrics instance is used to get the screen dimensions
    private val displayMetrics = DisplayMetrics()

    // The URI of the background we are currently displaying to avoid reloading the same one
    private var backgroundUri = ""

    private val backgroundRunnable: Runnable = Runnable {
        updateBackgroundImmediate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayMetrics.setTo(resources.displayMetrics)
        blurImageTransformation = BlurImageTransformation(requireContext())

        handler = Handler(Looper.getMainLooper())
        backgroundManager = BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) {
                attach(requireActivity().window)
            }
            setThemeDrawableResourceId(BACKGROUND_RESOURCE_ID)
        }

        val signInMenuItem = BrowseCustomMenu.MenuItem(getString(R.string.sign_in)) {
            findNavController().navigate(R.id.action_global_signInFragment)
        }
        val signOutMenuItem = BrowseCustomMenu.MenuItem(getString(R.string.sign_out)) {
            viewModel.signOut()
        }

        viewModel = ViewModelProvider(this).get(BrowseViewModel::class.java)
        viewModel.browseContent.observe(
            this,
            {
                adapter = BrowseAdapter(
                    it,
                    viewModel.customMenuItems.value ?: listOf()
                ) { categoryName ->
                    // 🔥 當使用者選擇左側分類時觸發
                    Timber.d("【分類】使用者選擇了分類: $categoryName")
                    viewModel.loadVideosByCategory(categoryName)
                }
            }
        )
        viewModel.customMenuItems.observe(
            this,
            {
                adapter = BrowseAdapter(
                    viewModel.browseContent.value ?: listOf(),
                    it
                ) { categoryName ->
                    // 🔥 當使用者選擇左側分類時觸發
                    Timber.d("【分類】使用者選擇了分類: $categoryName")
                    viewModel.loadVideosByCategory(categoryName)
                }
            }
        )
        viewModel.isSignedIn.observe(
            this,
            {
                // 不再需要在此處處理選單更新，已在 ViewModel 中處理
            }
        )

        // 處理導航到登入頁面的事件
        viewModel.navigateToSignIn.observe(this, { shouldNavigate ->
            if (shouldNavigate == true) {
                findNavController().navigate(R.id.action_global_signInFragment)
                viewModel.onNavigationHandled()
            }
        })

        // 觀察載入狀態並顯示提示
        viewModel.isLoading.observe(this, { isLoading ->
            if (isLoading) {
                // 顯示加載狀態
                val message = viewModel.loadingMessage.value ?: "載入中..."
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        })

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Video -> {
                    if (item.videoType == VideoType.EPISODE) {
                        // Navigate to series details screen for episode selection
                        findNavController().navigate(
                            BrowseFragmentDirections.actionBrowseFragmentToSeriesDetailsFragment(item)
                        )
                    } else {
                        findNavController().navigate(
                            BrowseFragmentDirections.actionBrowseFragmentToPlaybackFragment(item)
                        )
                    }
                }
                is BrowseCustomMenu.MenuItem -> item.handler()
            }
        }

        setOnItemViewSelectedListener { _, item, _, _ ->
            if (item is Video) {
                updateBackgroundDelayed(item)
            }
        }

        // 🔥 監聽 Row（分類行）選擇事件，當使用者切換左側分類時載入對應影片
        setOnItemViewSelectedListener { itemViewHolder, item, rowViewHolder, row ->
            // 處理影片選擇時的背景更新
            if (item is Video) {
                updateBackgroundDelayed(item)
            }

            // 🔥 當選擇了新的 Row（分類）時，載入該分類的影片
            if (row is androidx.leanback.widget.ListRow) {
                val headerItem = row.headerItem
                val categoryName = headerItem.name
                // 只在分類 row 時載入（排除自訂選單）
                if (categoryName != null && !categoryName.contains("操作") && !categoryName.contains("帳號")) {
                    Timber.d("【分類】Row 被選中: $categoryName")
                    viewModel.loadVideosByCategory(categoryName)
                }
            }
        }

        // BrowseSupportFragment allows for adding either text (with setTitle) or a Drawable
        // (with setBadgeDrawable) to the top right of the screen. Since we don't have a suitable
        // Drawable, we just display the app name in text.
        title = getString(R.string.app_name)

        // 🔥 啟用左側的分類列表（Headers）
        headersState = HEADERS_ENABLED
        // 🔥 預設進入時聚焦在內容區域，而不是分類列表
        isHeadersTransitionOnBackEnabled = true
    }

    /**
     * Updates the main fragment background after a delay
     *
     * This delay allows the user to quickly scroll through content without flashing a changing
     * background with every item that is passed.
     */
    private fun updateBackgroundDelayed(video: Video) {
        if (backgroundUri != video.backgroundImageUri) {
            handler.removeCallbacks(backgroundRunnable)
            backgroundUri = video.backgroundImageUri

            if (backgroundUri.isEmpty()) {
                backgroundManager.setThemeDrawableResourceId(BACKGROUND_RESOURCE_ID)
            } else {
                handler.postDelayed(backgroundRunnable, BACKGROUND_UPDATE_DELAY_MILLIS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Picasso.get().cancelRequest(this)
    }

    private fun updateBackgroundImmediate() {
        if (activity == null) {
            // Triggered after fragment detached from activity, ignore
            return
        }

        Picasso.get()
            .load(backgroundUri)
            .centerCrop()
            .resize(displayMetrics.widthPixels, displayMetrics.heightPixels)
            .onlyScaleDown()
            .transform(overlayImageTransformation).transform(blurImageTransformation)
            .into(this)
    }

    override fun onPrepareLoad(placeHolderDrawable: Drawable?) {
        // Keep displaying the previous background
    }

    override fun onBitmapFailed(e: Exception?, errorDrawable: Drawable?) {
        Timber.w(e, "Failed to load background")
        showDefaultBackground()
    }

    override fun onBitmapLoaded(bitmap: Bitmap?, from: Picasso.LoadedFrom?) {
        if (bitmap == null) {
            Timber.w("Background loaded but was null")
            showDefaultBackground()
        } else {
            backgroundManager.setBitmap(bitmap)
        }
    }

    private fun showDefaultBackground() {
        backgroundUri = ""
        backgroundManager.setThemeDrawableResourceId(BACKGROUND_RESOURCE_ID)
    }
}
