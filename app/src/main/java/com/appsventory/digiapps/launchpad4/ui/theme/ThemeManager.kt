package com.appsventory.digiapps.launchpad4.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.appsventory.digiapps.launchpad4.R

/**
 * Sistem tema Digi Home: mode (Auto/Terang/Gelap) + warna aksen.
 *
 * CAKUPAN JUJUR: seluruh app selain panel Pengaturan masih pakai warna hex statis
 * langsung di XML (ratusan TextView/background tersebar di Home/Apps/Files/Weather).
 * Me-retheme SEMUANYA butuh migrasi besar (tiap warna jadi color-resource + qualifier
 * day/night) yang di luar cakupan perubahan ini. Yang benar-benar diterapkan sekarang:
 *   1. Preferensi tersimpan & bekerja penuh (persist ke SharedPreferences).
 *   2. Panel "Pengaturan Launcher" (sidebar) berubah total sesuai mode terang/gelap —
 *      ini area yang sepenuhnya di bawah kendali [applyToSidebar].
 *   3. Warna aksen diterapkan ke elemen yang reasonable ditinta ulang secara runtime
 *      (switch, stroke fokus, teks "digunakan sekarang", dsb) lewat [accentColor].
 * Screen lain adalah pekerjaan lanjutan kalau mau full re-theme.
 */
object ThemeManager {

    enum class Mode { AUTO, LIGHT, DARK }

    data class Palette(
        val panelBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val divider: Int,
    )

    /** Palette warna dibaca dari color-resource (bukan hex literal) supaya satu sumber kebenaran. */
    fun darkPalette(context: Context): Palette = Palette(
        panelBackground = context.getColor(R.color.theme_dark_panel_background),
        primaryText = context.getColor(R.color.theme_dark_primary_text),
        secondaryText = context.getColor(R.color.theme_dark_secondary_text),
        divider = context.getColor(R.color.theme_dark_divider),
    )

    fun lightPalette(context: Context): Palette = Palette(
        panelBackground = context.getColor(R.color.theme_light_panel_background),
        primaryText = context.getColor(R.color.theme_light_primary_text),
        secondaryText = context.getColor(R.color.theme_light_secondary_text),
        divider = context.getColor(R.color.theme_light_divider),
    )

    fun isDarkEffective(activity: Activity, mode: Mode): Boolean = when (mode) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.AUTO -> {
            val uiMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode != Configuration.UI_MODE_NIGHT_NO
        }
    }

    fun palette(context: Context, isDark: Boolean): Palette =
        if (isDark) darkPalette(context) else lightPalette(context)

    /**
     * Terapkan palette ke seluruh subtree sidebar. View yang perlu ikut tema diberi
     * `android:tag` di XML: "primary" / "secondary" / "divider" — lewat tag (bukan
     * nebak dari ukuran font dkk), jadi eksplisit dan gampang di-maintain.
     */
    fun applyToSidebar(root: ViewGroup, palette: Palette) {
        root.setBackgroundColor(palette.panelBackground)
        fun walk(view: View) {
            when (view.tag) {
                "primary" -> if (view is TextView) view.setTextColor(palette.primaryText)
                "secondary" -> if (view is TextView) view.setTextColor(palette.secondaryText)
                "divider" -> view.setBackgroundColor(palette.divider)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
    }
}
