package com.appsventory.digiapps.launchpad4.ui.home

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.PrefsKeys
import com.appsventory.digiapps.launchpad4.data.WeatherRepository
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Home weather widget.
 *
 * Shares the Hero content region with the carousel (mutually exclusive via
 * [PrefsKeys] + [MainActivity.bannerEnabled]). When shown, the widget keeps
 * its layout_width=wrap_content so it sizes to the weather data, stays at the
 * top of [R.id.heroSection], and Favorite Apps remains in the lower hero band.
 */
class HomeWeatherWidgetController(private val activity: MainActivity) {

    private val repo = WeatherRepository(activity)
    private val loading = AtomicBoolean(false)

    /**
     * Re-evaluate visibility from current carousel + widget prefs and refresh data if shown.
     * Call after any change to bannerEnabled or WEATHER_WIDGET_ENABLED, and on Home resume.
     */
    fun refresh() {
        val widget = activity.findViewById<View>(R.id.homeWeatherWidget) ?: return
        val carouselOn = activity.bannerEnabled
        val userWantsWidget = activity.prefs.getBoolean(PrefsKeys.WEATHER_WIDGET_ENABLED, true)

        if (carouselOn || !userWantsWidget) {
            widget.visibility = View.GONE
            activity.adjustHomeSpacerPublic()
            return
        }

        // Content-width: never stretch to full screen
        ensureContentWidth(widget)
        widget.visibility = View.VISIBLE

        val cached = try {
            repo.readCached()
        } catch (e: Exception) {
            Log.e(TAG, "cache", e)
            null
        }

        if (cached != null) bind(cached)

        if (cached == null || !repo.isCacheFresh()) fetch()

        activity.adjustHomeSpacerPublic()
    }

    /** Backward-compatible entry used by MainActivity.loadRecommendations(). */
    fun updateForCarouselState(carouselEnabled: Boolean) {
        refresh()
    }

    private fun ensureContentWidth(widget: View) {
        val lp = widget.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            widget.layoutParams = lp
        }
    }

    private fun fetch() {
        if (!loading.compareAndSet(false, true)) return

        Thread({
            try {
                val snap = repo.getWeather(forceRefresh = false)
                activity.runOnUiThread {
                    loading.set(false)
                    bind(snap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetch fail", e)
                loading.set(false)
            }
        }, "home-weather-fetch").start()
    }

    private fun bind(snap: WeatherRepository.Snapshot) {
        val cur = snap.current
        activity.findViewById<TextView>(R.id.homeWeatherEmoji)?.text =
            WeatherRepository.weatherEmoji(cur.weatherCode, cur.isDay)
        activity.findViewById<TextView>(R.id.homeWeatherTemp)?.text =
            "${cur.tempC.toInt()}°"
        activity.findViewById<TextView>(R.id.homeWeatherCondition)?.text =
            WeatherRepository.weatherLabelEn(cur.weatherCode)
        activity.findViewById<TextView>(R.id.homeWeatherLocationText)?.text =
            "${snap.cityLabel}, ${WeatherRepository.sourceLabelEn(snap.locationSource)}"
    }

    companion object {
        private const val TAG = "HomeWeatherWidget"
    }
}
