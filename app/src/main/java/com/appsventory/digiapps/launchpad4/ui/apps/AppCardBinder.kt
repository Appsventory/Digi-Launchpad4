package com.appsventory.digiapps.launchpad4.ui.apps

import android.content.pm.ApplicationInfo
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.AppRepository

/**
 * Logika bind tampilan satu "kartu app"
 */
class AppCardBinder(
    private val appRepository: AppRepository,
    private val cardCornerPx: Float
) {

    fun bind(view: View, application: ApplicationInfo, onClick: () -> Unit) {
        val bannerView = view.findViewById<ImageView>(R.id.appBanner)
        val iconMode = view.findViewById<View>(R.id.appIconMode)
        val icon = view.findViewById<ImageView>(R.id.appIcon)
        val nameBelow = view.findViewById<TextView>(R.id.appNameBelow)

        val (banner, iconDrawable, label) = appRepository.visualsFor(application)

        // Isi teks di kedua tempat
        nameBelow?.text = label

        if (banner != null) {
            bannerView.setImageDrawable(banner)
            bannerView.visibility = View.VISIBLE
            iconMode.visibility = View.GONE
        } else {
            bannerView.visibility = View.GONE
            iconMode.visibility = View.VISIBLE
            icon.setImageDrawable(iconDrawable)
        }
        view.setOnClickListener { onClick() }
    }

    fun applyRoundClip(view: View) {
        val target = view.findViewById(R.id.appCard) ?: view
        target.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cardCornerPx)
            }
        }
        target.clipToOutline = true
        target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
    }

    fun setupScaleFocusAnimation(view: View, pivotFractionX: Float = 0.5f) {
        fun applyPivot() {
            view.pivotX = view.width * pivotFractionX
            view.pivotY = view.height / 2f
        }
        view.post { applyPivot() }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPivot() }

        val nameBelow = view.findViewById<TextView>(R.id.appNameBelow)
        nameBelow?.translationY = 0f
        nameBelow?.alpha = 0f

        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            nameBelow?.animate()?.cancel()

            val density = v.resources.displayMetrics.density
            val offset = 20f * density

            if (hasFocus) {
                v.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .alpha(1f)
                    .setDuration(130)
                    .start()
                v.elevation = 8f

                nameBelow?.animate()
                    ?.translationY(offset)
                    ?.alpha(1f)
                    ?.setDuration(130)
                    ?.start()
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0.92f)
                    .setDuration(130)
                    .start()
                v.elevation = 0f

                nameBelow?.animate()
                    ?.translationY(0f)
                    ?.alpha(0f)
                    ?.setDuration(100)
                    ?.start()
            }
        }
    }
}