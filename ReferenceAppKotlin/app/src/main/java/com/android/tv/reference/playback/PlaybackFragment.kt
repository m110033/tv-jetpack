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
package com.android.tv.reference.playback

import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.view.View
import androidx.fragment.app.viewModels
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.android.tv.reference.R
import com.android.tv.reference.castconnect.CastHelper
import com.android.tv.reference.shared.datamodel.Video
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ForwardingPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import com.google.android.gms.cast.tv.CastReceiverContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import timber.log.Timber
import java.time.Duration

/** Fragment that plays video content with ExoPlayer. */
class PlaybackFragment : VideoSupportFragment() {

    private lateinit var video: Video

    private var exoplayer: ExoPlayer? = null
    private val viewModel: PlaybackViewModel by viewModels()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector

    // 🔥 將 OkHttpClient 移到類別層級，避免 Socket closed 錯誤
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 🔥 將 data class 移到類別層級，避免 Moshi 序列化錯誤
    private data class GamerM3u8Response(
        val success: Boolean,
        val sn: String?,
        val m3u8Url: String?,
        val referer: String?,
        val cookies: String?,
        val origin: String?
    )

    private val uiPlaybackStateListener = object : PlaybackStateListener {
        override fun onChanged(state: VideoPlaybackState) {
            // While a video is playing, the screen should stay on and the device should not go to
            // sleep. When in any other state such as if the user pauses the video, the app should
            // not prevent the device from going to sleep.
            view?.keepScreenOn = state is VideoPlaybackState.Play

            when (state) {
                is VideoPlaybackState.Prepare -> startPlaybackFromWatchProgress(state.startPosition)
                is VideoPlaybackState.End -> {
                    // To get to playback, the user always goes through browse first. Deep links for
                    // directly playing a video also go to browse before playback. If playback
                    // finishes the entire video, the PlaybackFragment is popped off the back stack
                    // and the user returns to browse.
                    findNavController().popBackStack()
                }
                is VideoPlaybackState.Error ->
                    findNavController().navigate(
                        PlaybackFragmentDirections
                            .actionPlaybackFragmentToPlaybackErrorFragment(
                                state.video,
                                state.exception
                            )
                    )
                else -> {
                    // Do nothing.
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the video data.
        video = PlaybackFragmentArgs.fromBundle(requireArguments()).video

        // Create the MediaSession that will be used throughout the lifecycle of this Fragment.
        createMediaSession()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.addPlaybackStateListener(uiPlaybackStateListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.removePlaybackStateListener(uiPlaybackStateListener)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        destroyPlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Releasing the mediaSession due to inactive playback and setting token for cast to null.
        mediaSession.release()
        CastReceiverContext.getInstance().mediaManager.setSessionCompatToken(null)
    }

    private fun initializePlayer() {
        exoplayer = ExoPlayer.Builder(requireContext()).build().apply {
            addListener(PlayerEventListener())
            prepareGlue(this)
            mediaSessionConnector.setPlayer(object: ForwardingPlayer(this) {
                override fun stop() {
                    Timber.v("Playback stopped at $currentPosition")
                    playWhenReady = false
                }
            })
            mediaSession.isActive = true
        }
        viewModel.onStateChange(VideoPlaybackState.Load(video))

        if (isM3u8Resolver(video.videoUri)) {
            resolveM3u8(video.videoUri)
        } else {
            prepareProgressive(video.videoUri)
        }
    }

    private fun isM3u8Resolver(uri: String): Boolean {
        return uri.contains("/m3u8?url=")
    }

    private fun prepareProgressive(uri: String) {
        val dataSourceFactory = DefaultDataSource.Factory(requireContext())
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))
        exoplayer?.apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    private fun prepareHls(m3u8Url: String, referer: String?, cookies: String?, origin: String?) {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            val headers = mutableMapOf<String, String>()

            // 🔥 加入 Referer header（重要！）
            referer?.takeIf { it.isNotBlank() }?.let {
                headers["Referer"] = it
                Timber.d("【播放】設置 Referer: $it")
            }

            origin?.takeIf { it.isNotBlank() }?.let {
                headers["Origin"] = it
                Timber.d("【播放】設置 Origin: $it")
            }

            cookies?.takeIf { it.isNotBlank() }?.let {
                headers["Cookie"] = it
                Timber.d("【播放】設置 Cookie")
            }

            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
                Timber.i("【播放】已設置 HTTP Headers: ${headers.keys}")
            }

            // 設置超時和重定向
            setConnectTimeoutMs(8000)
            setReadTimeoutMs(8000)
            setAllowCrossProtocolRedirects(true)
        }

        val mediaSource = if (m3u8Url.endsWith(".mp4", ignoreCase = true) ||
                              m3u8Url.endsWith(".mkv", ignoreCase = true) ||
                              m3u8Url.endsWith(".avi", ignoreCase = true)) {
            // 如果是影片文件直連，使用 ProgressiveMediaSource
            Timber.i("【播放】檢測到影片直連 (MP4/MKV)，使用 Progressive 播放")
            val dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpFactory)
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(m3u8Url))
        } else {
            // 如果是 m3u8 播放列表，使用 HlsMediaSource
            Timber.i("【播放】檢測到 HLS 播放列表，使用 HLS 播放")
            HlsMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(m3u8Url))
        }

        exoplayer?.apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    private fun resolveM3u8(resolverUrl: String) {
        // 🔥 顯示載入中的 spinner
        progressBarManager?.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Timber.d("【播放】正在解析 m3u8: $resolverUrl")
                val request = Request.Builder().url(resolverUrl).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.e("【播放】m3u8 API 返回錯誤: HTTP ${resp.code}")
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                    Timber.d("【播放】收到 m3u8 回應: ${body.take(200)}")

                    val moshi = Moshi.Builder()
                        .add(KotlinJsonAdapterFactory())
                        .build()
                    val adapter = moshi.adapter(GamerM3u8Response::class.java)
                    val parsed = adapter.fromJson(body)
                    val m3u8 = parsed?.m3u8Url

                    Timber.d("【播放】解析結果 - success: ${parsed?.success}, m3u8Url: ${m3u8?.take(100)}, referer: ${parsed?.referer}, origin: ${parsed?.origin}")

                    if (parsed?.success == true && !m3u8.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            Timber.i("【播放】開始播放 HLS 串流，Referer: ${parsed.referer}, Origin: ${parsed.origin}")
                            prepareHls(m3u8, parsed.referer, parsed.cookies, parsed.origin)
                            // 🔥 載入成功後隱藏 spinner
                            progressBarManager?.hide()
                        }
                    } else {
                        Timber.e("【播放】無效的 m3u8 回應: success=${parsed?.success}, m3u8Url isEmpty=${m3u8.isNullOrBlank()}")
                        throw IllegalStateException("Invalid m3u8 response")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "【播放】解析 m3u8 失敗")
                withContext(Dispatchers.Main) {
                    // 🔥 發生錯誤時也要隱藏 spinner
                    progressBarManager?.hide()
                    viewModel.onStateChange(VideoPlaybackState.Error(video, e))
                }
            }
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(requireContext(), MEDIA_SESSION_TAG)

        mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
            setQueueNavigator(SingleVideoQueueNavigator(video, mediaSession))
        }
        CastReceiverContext.getInstance().mediaManager.setSessionCompatToken(
            mediaSession.sessionToken)
    }

    private fun startPlaybackFromWatchProgress(startPosition: Long) {
        Timber.v("Starting playback from $startPosition")
        exoplayer?.apply {
            seekTo(startPosition)
            playWhenReady = true
        }
    }

    private val onProgressUpdate: () -> Unit = {
        // TODO(benbaxter): Calculate when end credits are displaying and show the next episode for
        //  episodic content.
    }

    private fun prepareGlue(localExoplayer: ExoPlayer) {
        ProgressTransportControlGlue(
            requireContext(),
            LeanbackPlayerAdapter(
                requireContext(),
                localExoplayer,
                PLAYER_UPDATE_INTERVAL_MILLIS.toInt()
            ),
            onProgressUpdate
        ).apply {
            host = VideoSupportFragmentGlueHost(this@PlaybackFragment)
            title = video.name
            isSeekEnabled = true
        }
    }

    inner class PlayerEventListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error, "Playback error")
            viewModel.onStateChange(VideoPlaybackState.Error(video, error))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            when {
                isPlaying -> viewModel.onStateChange(
                    VideoPlaybackState.Play(video))
                exoplayer!!.playbackState == Player.STATE_ENDED -> viewModel.onStateChange(
                    VideoPlaybackState.End(video))
                else -> viewModel.onStateChange(
                    VideoPlaybackState.Pause(video, exoplayer!!.currentPosition))
            }
        }
    }

    private fun destroyPlayer() {
        mediaSession.isActive = false
        mediaSessionConnector.setPlayer(null)
        exoplayer?.let {
            it.pause()
            it.release()
            exoplayer = null
        }
    }

    companion object {
        // Update the player UI fairly often. The frequency of updates affects several UI components
        // such as the smoothness of the progress bar and time stamp labels updating. This value can
        // be tweaked for better performance.
        private const val PLAYER_UPDATE_INTERVAL_MILLIS = 50L

        // A short name to identify the media session when debugging.
        private const val MEDIA_SESSION_TAG = "ReferenceAppKotlin"
    }
}
