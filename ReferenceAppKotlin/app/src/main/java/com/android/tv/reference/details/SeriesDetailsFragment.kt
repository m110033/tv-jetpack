package com.android.tv.reference.details

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.android.tv.reference.R
import com.android.tv.reference.browse.BrowseAdapter
import com.android.tv.reference.shared.datamodel.Video
import com.android.tv.reference.shared.image.BlurImageTransformation
import com.android.tv.reference.shared.image.OverlayImageTransformation
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import timber.log.Timber

/**
 * Fragment displaying the list of episodes for a selected series. User selects an episode -> playback.
 */
class SeriesDetailsFragment : BrowseSupportFragment(), Target {

    private lateinit var viewModel: SeriesDetailsViewModel
    private lateinit var backgroundManager: BackgroundManager
    private lateinit var handler: Handler

    private val overlayImageTransformation = OverlayImageTransformation()
    private lateinit var blurImageTransformation: BlurImageTransformation
    private val displayMetrics = DisplayMetrics()

    private var backgroundUri = ""
    private val backgroundRunnable: Runnable = Runnable { updateBackgroundImmediate() }

    private lateinit var initialVideo: Video

    companion object {
        private const val BACKGROUND_UPDATE_DELAY_MILLIS = 500L
        private val BACKGROUND_RESOURCE_ID = R.drawable.image_placeholder
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialVideo = SeriesDetailsFragmentArgs.fromBundle(requireArguments()).video

        displayMetrics.setTo(resources.displayMetrics)
        blurImageTransformation = BlurImageTransformation(requireContext())
        handler = Handler(Looper.getMainLooper())
        backgroundManager = BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            setThemeDrawableResourceId(BACKGROUND_RESOURCE_ID)
        }

        viewModel = ViewModelProvider(this).get(SeriesDetailsViewModel::class.java)
        viewModel.seasonGroups.observe(this) { groups ->
            adapter = BrowseAdapter(groups, listOf())
        }
        viewModel.loadFrom(initialVideo)

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Video) {
                findNavController().navigate(
                    SeriesDetailsFragmentDirections.actionSeriesDetailsFragmentToPlaybackFragment(item)
                )
            }
        }

        setOnItemViewSelectedListener { _, item, _, _ ->
            if (item is Video) updateBackgroundDelayed(item)
        }

        title = initialVideo.name
    }

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
        if (activity == null) return
        Picasso.get()
            .load(backgroundUri)
            .centerCrop()
            .resize(displayMetrics.widthPixels, displayMetrics.heightPixels)
            .onlyScaleDown()
            .transform(overlayImageTransformation).transform(blurImageTransformation)
            .into(this)
    }

    override fun onPrepareLoad(placeHolderDrawable: Drawable?) { }

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
