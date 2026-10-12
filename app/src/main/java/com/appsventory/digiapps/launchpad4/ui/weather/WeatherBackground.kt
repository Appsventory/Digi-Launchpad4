package com.appsventory.digiapps.launchpad4.ui.weather

import android.widget.ImageView
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R

/**
 * Background KHUSUS halaman Weather (@id/weatherBgImage — full-bleed, root level),
 * ganti sesuai kode cuaca Open-Meteo saat ini. TIDAK menyentuh wallpaper global
 * (@id/heroImage) — tab lain (Home/Apps/Files) tetap pakai hero.jpg seperti biasa.
 *
 * Dipisah dari weatherContent (yang keinset padding contentHost) supaya gambarnya
 * kena sampai tepi layar, bukan cuma sebatas area konten.
 *
 * Cuma 3 aset yang ada, jadi semua weather_code dikelompokkan ke salah satu:
 * cerah/berawan, hujan (termasuk gerimis+badai), atau salju.
 */
object WeatherBackground {

    private val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)

    fun resFor(weatherCode: Int): Int = when (weatherCode) {
        in SNOW_CODES -> R.drawable.weather_bg_snow_scrim
        in RAIN_CODES -> R.drawable.weather_bg_rain_scrim
        else -> R.drawable.weather_bg_clear_scrim
    }

    fun apply(activity: MainActivity, weatherCode: Int) {
        activity.findViewById<ImageView>(R.id.weatherBgImage)?.setImageResource(resFor(weatherCode))
    }
}
