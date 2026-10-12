package com.appsventory.digiapps.launchpad4.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.GradientPresets
import com.appsventory.digiapps.launchpad4.data.PrefsKeys
import com.appsventory.digiapps.launchpad4.ui.util.FocusLock

private enum class SettingsPage { ROOT, HOME_SCREEN, ALL_APPS, APP_DETAIL, CONTENT, CONTENT_CHANNELS, CUSTOM_CHANNEL, CUSTOM_CHANNELS, WEATHER_WIDGET, APPEARANCE, PREFERENCES, NAV_BAR, SHELL_SHORTCUTS, ABOUT, SELECT_FAVORITE }
class SettingsSidebarController(private val activity: MainActivity) {

    private var currentPage = SettingsPage.ROOT
    /** True kalau sidebar dibuka lewat tombol + (Select app). Dipakai untuk restore fokus ke kartu +. */
    private var openedAsSelectFavorite = false

    /** True kalau lagi drill-in ke daftar "Non TV Apps" (dari kartu di halaman
     *  Select app). Balik ke false tiap kali sidebar Select app dibuka ulang. */
    private var showingNonTvOnly = false

    // Colors read once from resources instead of hardcoded hex literals scattered below.
    private val colorWhite = activity.getColor(R.color.white)
    private val colorWhite20 = activity.getColor(R.color.white_20)
    private val colorWhite50 = activity.getColor(R.color.white_50)
    private val colorWhite60 = activity.getColor(R.color.white_60)
    private val colorAccentSky = activity.getColor(R.color.accent_sky)
    private val colorThemeDarkSecondaryText = activity.getColor(R.color.theme_dark_secondary_text)

    private val sidebarBack get() = activity.findViewById<View>(R.id.sidebarBack)
    private val sidebarTitle get() = activity.findViewById<TextView>(R.id.sidebarTitle)
    private val sidebarRootScroll get() = activity.findViewById<View>(R.id.sidebarRootScroll)
    private val sidebarContentScroll get() = activity.findViewById<View>(R.id.sidebarContentScroll)
    private val sidebarAppearanceScroll get() = activity.findViewById<View>(R.id.sidebarAppearanceScroll)
    private val sidebarContentRow get() = activity.findViewById<View>(R.id.sidebarContentRow)
    private val sidebarCustomChannelRow get() = activity.findViewById<View>(R.id.sidebarCustomChannelRow)
    private val sidebarCustomChannelScroll get() = activity.findViewById<View>(R.id.sidebarCustomChannelScroll)
    private val carouselEnableSwitch get() = activity.findViewById<android.widget.Switch>(R.id.carouselEnableSwitch)
    private val customChannelEnableSwitch get() = activity.findViewById<android.widget.Switch>(R.id.customChannelEnableSwitch)
    private val carouselEnableRow get() = activity.findViewById<View>(R.id.carouselEnableRow)
    private val customChannelEnableRow get() = activity.findViewById<View>(R.id.customChannelEnableRow)
    private val customChannelList get() = activity.findViewById<LinearLayout>(R.id.customChannelList)

    /** Package yang sedang dibuka di drill-down channel (Content / Custom). */
    private var drillPackage: String? = null
    /** Single-expand accordion key for Customize Channel (package name or null). */
    private var expandedCustomChannelPkg: String? = null
    private var drillMode: DrillMode = DrillMode.CAROUSEL

    private enum class DrillMode { CAROUSEL, CUSTOM }
    private val density: Float
        get() = activity.resources.displayMetrics.density

    private fun attachFocusScale(view: View, focusedScale: Float = 1f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (focusedScale != 1f) {
                v.animate().cancel()
                v.animate()
                    .scaleX(if (hasFocus) focusedScale else 1f)
                    .scaleY(if (hasFocus) focusedScale else 1f)
                    .setDuration(120L)
                    .start()
            }

            v.elevation = if (hasFocus) {
                4f * density
            } else {
                0f
            }
        }
    }

    /**
     * Pindah halaman di dalam panel (drill-down). Ini navigasi internal 1 overlay yang
     * sama — bukan buka Activity/Fragment baru — supaya tetap ringan di TV box "kentang".
     */
    private fun showPage(page: SettingsPage) {
        currentPage = page
        val isRoot = page == SettingsPage.ROOT
        activity.findViewById<View>(R.id.settingsSubHeader)?.visibility = if (isRoot) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.settingsHeaderDivider)?.visibility = if (isRoot) View.GONE else View.VISIBLE

        sidebarRootScroll.visibility = if (page == SettingsPage.ROOT) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarHomeScreenScroll)?.visibility =
            if (page == SettingsPage.HOME_SCREEN) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarAllAppsScroll)?.visibility =
            if (page == SettingsPage.ALL_APPS || page == SettingsPage.SELECT_FAVORITE) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarAppDetailScroll)?.visibility =
            if (page == SettingsPage.APP_DETAIL) View.VISIBLE else View.GONE
        sidebarContentScroll.visibility =
            if (page == SettingsPage.CONTENT || page == SettingsPage.CONTENT_CHANNELS || page == SettingsPage.WEATHER_WIDGET) View.VISIBLE else View.GONE
        sidebarCustomChannelScroll.visibility =
            if (page == SettingsPage.CUSTOM_CHANNEL || page == SettingsPage.CUSTOM_CHANNELS) View.VISIBLE else View.GONE
        sidebarAppearanceScroll.visibility = if (page == SettingsPage.APPEARANCE) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarPreferencesScroll)?.visibility =
            if (page == SettingsPage.PREFERENCES) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarNavBarScroll)?.visibility =
            if (page == SettingsPage.NAV_BAR || page == SettingsPage.SHELL_SHORTCUTS) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.sidebarAboutScroll)?.visibility =
            if (page == SettingsPage.ABOUT) View.VISIBLE else View.GONE
        sidebarBack.visibility = if (isRoot) View.GONE else View.VISIBLE
        sidebarTitle.text = when (page) {
            SettingsPage.ROOT -> "Setting"
            SettingsPage.HOME_SCREEN -> "Home Screen"
            SettingsPage.ALL_APPS -> "All Applications"
            SettingsPage.APP_DETAIL -> "Setting"
            SettingsPage.CONTENT -> "Content Carousel"
            SettingsPage.CONTENT_CHANNELS -> appLabel(drillPackage) ?: "Select a channel"
            SettingsPage.CUSTOM_CHANNEL -> "Edit Channel"
            SettingsPage.CUSTOM_CHANNELS -> "Edit Channel"
            SettingsPage.WEATHER_WIDGET -> "Weather Widget"
            SettingsPage.APPEARANCE -> "Appearance"
            SettingsPage.PREFERENCES -> "Preference"
            SettingsPage.NAV_BAR -> "Navigation bar"
            SettingsPage.SHELL_SHORTCUTS -> "Shell Shortcuts"
            SettingsPage.ABOUT -> "About"
            SettingsPage.SELECT_FAVORITE -> if (showingNonTvOnly) "Non-TV Apps" else "Select app"
        }
        val firstFocus: View? = when (page) {
            SettingsPage.ROOT -> activity.findViewById<LinearLayout>(R.id.rootCategoryList)?.getChildAt(0)
            SettingsPage.HOME_SCREEN -> activity.findViewById(R.id.sidebarContentRow) ?: sidebarBack
            SettingsPage.ALL_APPS, SettingsPage.SELECT_FAVORITE ->
                activity.findViewById<android.widget.LinearLayout>(R.id.allAppsList)?.getChildAt(0) ?: sidebarBack
            SettingsPage.APP_DETAIL -> activity.findViewById(R.id.appDetailLaunch) ?: sidebarBack
            SettingsPage.CONTENT -> carouselEnableRow
            SettingsPage.CONTENT_CHANNELS -> activity.providerList.getChildAt(0) ?: sidebarBack
            SettingsPage.WEATHER_WIDGET -> activity.providerList.getChildAt(0) ?: sidebarBack
            SettingsPage.CUSTOM_CHANNEL -> customChannelEnableRow
            SettingsPage.CUSTOM_CHANNELS -> customChannelList.getChildAt(0) ?: sidebarBack
            SettingsPage.APPEARANCE -> activity.pickWallpaperImageButton
            SettingsPage.PREFERENCES -> activity.findViewById(R.id.sidebarClock24hRow) ?: sidebarBack
            SettingsPage.NAV_BAR -> activity.findViewById<LinearLayout>(R.id.navBarEditorList)?.getChildAt(0) ?: sidebarBack
            SettingsPage.SHELL_SHORTCUTS -> activity.findViewById<LinearLayout>(R.id.navBarEditorList)?.getChildAt(0) ?: sidebarBack
            SettingsPage.ABOUT -> activity.resetSettingsButton
        }
        firstFocus?.post {
            firstFocus.isFocusable = true
            firstFocus.isFocusableInTouchMode = true
            firstFocus.requestFocus()
        }
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val ai = activity.packageManager.getApplicationInfo(pkg, 0)
            activity.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /** Dipanggil dari dispatchKeyEvent MainActivity saat tombol Back fisik ditekan.
     *  Return true kalau event "dikonsumsi" di dalam sidebar (mundur 1 halaman),
     *  false berarti MainActivity boleh lanjut menutup sidebar sepenuhnya. */
    fun handleBackPress(): Boolean {
        return when (currentPage) {
            SettingsPage.APP_DETAIL -> { showPage(SettingsPage.ALL_APPS); true }
            SettingsPage.CONTENT_CHANNELS -> { populateCarouselApps(); showPage(SettingsPage.CONTENT); true }
            SettingsPage.CUSTOM_CHANNELS -> { populateCustomApps(); showPage(SettingsPage.CUSTOM_CHANNEL); true }
            SettingsPage.ALL_APPS -> { showPage(SettingsPage.ROOT); true }
            SettingsPage.CONTENT, SettingsPage.CUSTOM_CHANNEL, SettingsPage.WEATHER_WIDGET -> { showPage(SettingsPage.HOME_SCREEN); true }
            SettingsPage.NAV_BAR -> { showPage(SettingsPage.PREFERENCES); true }
            SettingsPage.SHELL_SHORTCUTS -> {
                populateNavBarEditor()
                showPage(SettingsPage.NAV_BAR)
                true
            }
            SettingsPage.HOME_SCREEN, SettingsPage.APPEARANCE, SettingsPage.PREFERENCES, SettingsPage.ABOUT -> {
                showPage(SettingsPage.ROOT); true
            }
            SettingsPage.SELECT_FAVORITE -> {
                if (showingNonTvOnly) {
                    // Mundur dari drill-in "Non TV Apps" → balik ke daftar utama
                    // (TV apps + kartu "Non TV App"), bukan nutup sidebar.
                    showingNonTvOnly = false
                    populateSelectFavoriteApps()
                    showPage(SettingsPage.SELECT_FAVORITE)
                    true
                } else {
                    false // tutup sidebar langsung
                }
            }
            SettingsPage.ROOT -> false
        }
    }

    // ─── Sidebar (launcher settings) ───
    fun setupSidebar() {
        activity.bannerSwitch.isChecked = activity.bannerEnabled
        // Master toggle Content Carousel (di sub-halaman)
        // Switch sendiri focusable=false — fokus & OK di ROW (TV D-pad)
        carouselEnableSwitch.isFocusable = false
        carouselEnableSwitch.isClickable = false
        try {
            carouselEnableSwitch.thumbDrawable = activity.getDrawable(R.drawable.switch_thumb)
            carouselEnableSwitch.trackDrawable = activity.getDrawable(R.drawable.switch_track)
            customChannelEnableSwitch.thumbDrawable = activity.getDrawable(R.drawable.switch_thumb)
            customChannelEnableSwitch.trackDrawable = activity.getDrawable(R.drawable.switch_track)
            activity.clock24hSwitch.thumbDrawable = activity.getDrawable(R.drawable.switch_thumb)
            activity.clock24hSwitch.trackDrawable = activity.getDrawable(R.drawable.switch_track)
        } catch (_: Exception) { }

        carouselEnableSwitch.isChecked = activity.bannerEnabled
        fun bindCarouselRowClick() {
            carouselEnableRow.setOnClickListener {
                carouselEnableSwitch.isChecked = !carouselEnableSwitch.isChecked
            }
            carouselEnableRow.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    carouselEnableSwitch.isChecked = !carouselEnableSwitch.isChecked
                    true
                } else false
            }
        }
        bindCarouselRowClick()
        carouselEnableSwitch.setOnCheckedChangeListener { _, checked ->
            activity.bannerEnabled = checked
            activity.prefs.edit().putBoolean(PrefsKeys.BANNER, checked).apply()
            // Content Carousel takes precedence: force-hide weather widget when on
            // (preference is preserved; only effective display changes)
            activity.homeWeatherWidgetController.refresh()
            syncWeatherWidgetHomeRow()
            activity.loadRecommendations()
            if (!checked) activity.restoreDefaultHero()
            // Immediately load/hide the channel app list — do not require leaving the page
            if (currentPage == SettingsPage.CONTENT || currentPage == SettingsPage.CONTENT_CHANNELS) {
                populateCarouselApps()
            } else {
                val vis = if (checked) View.VISIBLE else View.GONE
                activity.findViewById<View>(R.id.carouselAppsSectionTitle)?.visibility = vis
                activity.providerList.visibility = vis
                activity.providerConfigStatus.visibility = vis
            }
            refreshCarouselSummary()
        }
        customChannelEnableSwitch.isFocusable = false
        customChannelEnableSwitch.isClickable = false
        customChannelEnableSwitch.isChecked = activity.customChannelEnabled
        customChannelEnableRow.setOnClickListener {
            customChannelEnableSwitch.isChecked = !customChannelEnableSwitch.isChecked
        }
        customChannelEnableRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                customChannelEnableSwitch.isChecked = !customChannelEnableSwitch.isChecked
                true
            } else false
        }
        customChannelEnableSwitch.setOnCheckedChangeListener { _, checked ->
            activity.customChannelEnabled = checked
            activity.prefs.edit().putBoolean(PrefsKeys.CUSTOM_CHANNEL_ENABLED, checked).apply()
            // Reload list immediately when toggled on the current page
            if (currentPage == SettingsPage.CUSTOM_CHANNEL || currentPage == SettingsPage.CUSTOM_CHANNELS) {
                populateCustomApps()
            } else {
                val vis = if (checked) View.VISIBLE else View.GONE
                customChannelList.visibility = vis
                activity.findViewById<TextView>(R.id.customChannelStatus)?.visibility = vis
            }
            activity.loadCustomChannelRow()
            refreshCustomSummary()
        }
        activity.sidebarClose.setOnClickListener { closeSidebar() }
        activity.sidebarScrim.setOnClickListener { closeSidebar() }
        activity.sidebarPanel.setOnClickListener { /* consume */ }
        // Kalau handleBackPress return false (sudah di root / Select app), tutup sidebar.
        sidebarBack.setOnClickListener { if (!handleBackPress()) closeSidebar() }
        fun navRow(row: View, keepBackground: Boolean = false, action: () -> Unit) {
            if (!keepBackground) {
                styleInteractive(row)
            } else {
                row.isFocusable = true
                row.isFocusableInTouchMode = true
                row.isClickable = true
            }
            attachFocusScale(row, 1f)
            row.setOnClickListener { action() }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    action(); true
                } else false
            }
        }
        activity.findViewById<View>(R.id.rootAllAppsRow)?.let { row ->
            row.isFocusable = true
            row.isFocusableInTouchMode = true
            row.isClickable = true
            row.setOnClickListener {
                populateAllApps()
                showPage(SettingsPage.ALL_APPS)
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    row.performClick(); true
                } else false
            }
        }
        activity.findViewById<View>(R.id.rootSystemSettingRow)?.let { row ->
            row.isFocusable = true
            row.isFocusableInTouchMode = true
            row.isClickable = true
            row.setOnClickListener {
                try {
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(activity, "Cannot open system settings", Toast.LENGTH_SHORT).show()
                }
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    row.performClick(); true
                } else false
            }
        }
        activity.findViewById<View>(R.id.sidebarAllAppsRow)?.let { row ->
            navRow(row) {
                populateAllApps()
                showPage(SettingsPage.ALL_APPS)
            }
        }
        navRow(sidebarContentRow, keepBackground = true) {
            populateCarouselApps()
            showPage(SettingsPage.CONTENT)
        }
        navRow(sidebarCustomChannelRow, keepBackground = true) {
            populateCustomApps()
            showPage(SettingsPage.CUSTOM_CHANNEL)
        }
        // Weather Widget — inline toggle on Home Screen (carousel has precedence)
        ensureWeatherWidgetHomeRow()
        bindWeatherWidgetHomeRow()
        activity.findViewById<View>(R.id.sidebarSystemSettingRow)?.let { row ->
            navRow(row) {
                try {
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(activity, "Cannot open system settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setupRootCategories()
        refreshCarouselSummary()
        refreshCustomSummary()
    }

    /**
     * Root page redesign: 4 category cards built from one data table instead of the
     * old 12 copy-pasted XML rows. Adding a future 5th category is a one-line addition
     * here rather than another hand-written XML block.
     */
    private data class RootCategory(
        val title: String,
        val subtitle: String,
        val glyph: String,
        val page: SettingsPage,
    )

    private fun setupRootCategories() {
        val categories = listOf(
            RootCategory("All Applications", "", "android", SettingsPage.ALL_APPS),
            RootCategory("Home Screen", "", "home", SettingsPage.HOME_SCREEN),
            RootCategory("Appearance", "", "image", SettingsPage.APPEARANCE),
            RootCategory("Preference", "", "bookmark", SettingsPage.PREFERENCES),
            RootCategory("About", "", "info", SettingsPage.ABOUT)
        )

        val list = activity.findViewById<LinearLayout>(R.id.rootCategoryList)
        list.removeAllViews()

        // Premium TV sidebar: one large, vertically navigable item per category.
        // This keeps D-pad movement predictable and avoids cramped 2-column cards.
        categories.forEach { cat ->
            val card = buildCategoryCard(cat)
            val open = {
                if (cat.page == SettingsPage.ALL_APPS) populateAllApps()
                showPage(cat.page)
            }
            card.setOnClickListener { open() }
            card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    open(); true
                } else false
            }
            list.addView(card)
        }
    }

    private fun buildCategoryCard(cat: RootCategory): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (12 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = activity.getDrawable(R.drawable.bg_settings_menu_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        val iconRes = when (cat.glyph) {
            "android" -> R.drawable.ic_settings_android
            "home" -> R.drawable.ic_settings_home
            "image" -> R.drawable.ic_settings_image
            "bookmark" -> R.drawable.ic_settings_bookmark
            "info" -> R.drawable.ic_info
            else -> R.drawable.ic_settings_gear
        }
        val icon = ImageView(activity).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = activity.getColorStateList(R.color.settings_menu_icon)
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                (22 * density).toInt(), (22 * density).toInt()
            )
        }
        val title = TextView(activity).apply {
            text = cat.title
            textSize = 16f
            setTextColor(activity.getColorStateList(R.color.settings_menu_text))
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (12 * density).toInt() }
        }
        row.addView(icon)
        row.addView(title)
        return row
    }

    private fun styleInteractive(view: View) {
        view.background = activity.getDrawable(R.drawable.bg_hover_solid)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
    }

    // ─── Weather Widget setting (inline on Home Screen) ───

    private var weatherWidgetToggle: android.widget.Switch? = null
    private var weatherWidgetStatus: TextView? = null
    private var weatherWidgetRow: View? = null

    /** Inject Weather Widget row with inline toggle after Customize Channel. */
    private fun ensureWeatherWidgetHomeRow() {
        if (activity.findViewById<View>(R.id.sidebarWeatherWidgetRow) != null) {
            weatherWidgetRow = activity.findViewById(R.id.sidebarWeatherWidgetRow)
            return
        }
        val parent = sidebarCustomChannelRow?.parent as? LinearLayout ?: return
        val idx = parent.indexOfChild(sidebarCustomChannelRow)
        val d = density
        val row = LinearLayout(activity).apply {
            id = R.id.sidebarWeatherWidgetRow
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt())
            background = activity.getDrawable(R.drawable.bg_hover_transparent)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * d).toInt() }
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(activity).apply {
            text = "Weather Widget"
            setTextColor(colorWhite)
            textSize = 16f
        })
        val status = TextView(activity).apply {
            setTextColor(colorThemeDarkSecondaryText)
            textSize = 12f
            setPadding(0, (3 * d).toInt(), 0, 0)
        }
        weatherWidgetStatus = status
        texts.addView(status)
        val toggle = android.widget.Switch(activity).apply {
            isFocusable = false
            isClickable = false
            try {
                thumbDrawable = activity.getDrawable(R.drawable.switch_thumb)
                trackDrawable = activity.getDrawable(R.drawable.switch_track)
            } catch (_: Exception) {}
        }
        weatherWidgetToggle = toggle
        row.addView(texts)
        row.addView(toggle)
        weatherWidgetRow = row
        parent.addView(row, (idx + 1).coerceAtMost(parent.childCount))
        syncWeatherWidgetHomeRow()
    }

    private fun bindWeatherWidgetHomeRow() {
        ensureWeatherWidgetHomeRow()
        val row = weatherWidgetRow ?: activity.findViewById(R.id.sidebarWeatherWidgetRow) ?: return
        row.setOnClickListener {
            if (activity.bannerEnabled) {
                Toast.makeText(activity, "Available when Content Carousel is off", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val next = !activity.prefs.getBoolean(PrefsKeys.WEATHER_WIDGET_ENABLED, true)
            activity.prefs.edit().putBoolean(PrefsKeys.WEATHER_WIDGET_ENABLED, next).apply()
            syncWeatherWidgetHomeRow()
            activity.homeWeatherWidgetController.refresh()
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                row.performClick(); true
            } else false
        }
        attachFocusScale(row, 1f)
        syncWeatherWidgetHomeRow()
    }

    /** Refresh toggle enabled/checked and subtitle from carousel + preference. */
    private fun syncWeatherWidgetHomeRow() {
        val carouselOn = activity.bannerEnabled
        val preferred = activity.prefs.getBoolean(PrefsKeys.WEATHER_WIDGET_ENABLED, true)
        val toggle = weatherWidgetToggle
            ?: (activity.findViewById<View>(R.id.sidebarWeatherWidgetRow) as? ViewGroup)
                ?.let { vg -> (0 until vg.childCount).map { vg.getChildAt(it) }.filterIsInstance<android.widget.Switch>().firstOrNull() }
        val status = weatherWidgetStatus
        if (carouselOn) {
            toggle?.isChecked = false
            toggle?.isEnabled = false
            toggle?.alpha = 0.4f
            status?.text = "Available when Content Carousel is off"
            status?.setTextColor(colorWhite50)
        } else {
            toggle?.isChecked = preferred
            toggle?.isEnabled = true
            toggle?.alpha = 1f
            status?.text = if (preferred) "Shown on Home" else "Hidden"
            status?.setTextColor(colorThemeDarkSecondaryText)
        }
    }

    // ─── Wallpaper (galeri / gradasi warna per grup) ───
    fun setupWallpaper() {
        val container = activity.gradientGroupsContainer
        container.removeAllViews()
        for (group in GradientPresets.GROUPS) {
            addGradientGroup(container, group.label, group.colors)
        }

        activity.pickWallpaperImageButton.setOnClickListener { launchImagePicker() }
        activity.pickWallpaperImageButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { launchImagePicker(); true } else false
        }

        activity.resetWallpaperButton.setOnClickListener { applyDefaultWallpaper(persist = true) }
        activity.resetWallpaperButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { applyDefaultWallpaper(persist = true); true } else false
        }

        applyCurrentWallpaper()
    }

    private fun addGradientGroup(parent: LinearLayout, label: String, presets: List<IntArray>) {
        val title = TextView(activity).apply {
            text = label.uppercase()
            setTextColor(colorThemeDarkSecondaryText)
            textSize = 10f
            letterSpacing = 0.08f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * density).toInt()
            lp.topMargin = (10 * density).toInt()
            layoutParams = lp
        }
        parent.addView(title)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
            addView(row)
        }
        row.clipChildren = false
        row.clipToPadding = false
        for (colors in presets) {
            row.addView(makeGradientSwatch(colors))
        }
        parent.addView(scroll)
    }

    private fun makeGradientSwatch(colors: IntArray): View {
        val size = (44 * density).toInt()
        return View(activity).apply {
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (10 * density).toInt()
            layoutParams = lp
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                cornerRadius = 10 * density
                setStroke((1.5f * density).toInt(), colorWhite20)
            }
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            // Do not use clipToOutline here — it clips the focus scale animation.
            // Rounded look comes from the GradientDrawable cornerRadius alone.
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1f)
                    .scaleY(if (hasFocus) 1.12f else 1f)
                    .setDuration(120)
                    .start()
                // Stroke intensifies on focus for a clear TV-readable ring
                val gd = v.background as? GradientDrawable
                gd?.setStroke(
                    ((if (hasFocus) 2.5f else 1.5f) * density).toInt(),
                    if (hasFocus) colorAccentSky else colorWhite20
                )
            }
            setOnClickListener { applyGradientWallpaper(colors, persist = true) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    applyGradientWallpaper(colors, persist = true); true
                } else false
            }
        }
    }

    /** Terapkan wallpaper yang sedang tersimpan di activity.prefs (dipanggil saat startup / setelah toggle banner off). */
    fun applyCurrentWallpaper() {
        when (activity.prefs.getString(PrefsKeys.WALLPAPER_MODE, "default")) {
            "gradient" -> {
                val idx = activity.prefs.getInt(PrefsKeys.WALLPAPER_GRADIENT_INDEX, 0).coerceIn(0, GradientPresets.ALL.size - 1)
                applyGradientWallpaper(GradientPresets.ALL[idx], persist = false)
            }
            "image" -> {
                val local = activity.prefs.getString(PrefsKeys.WALLPAPER_LOCAL_PATH, null)
                if (!local.isNullOrBlank()) {
                    val f = java.io.File(local)
                    if (f.isFile && f.canRead()) {
                        applyImageWallpaper(Uri.fromFile(f), persist = false)
                    } else {
                        val uriStr = activity.prefs.getString(PrefsKeys.WALLPAPER_URI, null)
                        if (uriStr != null) applyImageWallpaper(Uri.parse(uriStr), persist = false)
                        else applyDefaultWallpaper(persist = false)
                    }
                } else {
                    val uriStr = activity.prefs.getString(PrefsKeys.WALLPAPER_URI, null)
                    if (uriStr != null) {
                        applyImageWallpaper(Uri.parse(uriStr), persist = false)
                    } else {
                        applyDefaultWallpaper(persist = false)
                    }
                }
            }
            else -> applyDefaultWallpaper(persist = false)
        }
    }

    fun applyDefaultWallpaper(persist: Boolean) {
        activity.findViewById<ImageView>(R.id.heroImage).apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.hero)
        }
        activity.wallpaperStatusText.text = "Default wallpaper active"
        if (persist) {
            activity.prefs.edit()
                .putString(PrefsKeys.WALLPAPER_MODE, "default")
                .remove(PrefsKeys.WALLPAPER_URI)
                .remove(PrefsKeys.WALLPAPER_LOCAL_PATH)
                .apply()
            try { java.io.File(activity.filesDir, "wallpaper_user.jpg").delete() } catch (_: Exception) {}
            Toast.makeText(activity, "Default wallpaper applied", Toast.LENGTH_SHORT).show()
        }
    }

    fun applyGradientWallpaper(colors: IntArray, persist: Boolean) {
        activity.findViewById<ImageView>(R.id.heroImage).apply {
            setImageDrawable(null)
            visibility = View.VISIBLE
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        }
        val idx = GradientPresets.ALL.indexOfFirst { it.contentEquals(colors) }.coerceAtLeast(0)
        val name = GradientPresets.NAMES.getOrNull(idx)
        activity.wallpaperStatusText.text =
            if (name != null) "Wallpaper: $name" else "Wallpaper: color gradient"
        if (persist) {
            activity.prefs.edit()
                .putString(PrefsKeys.WALLPAPER_MODE, "gradient")
                .putInt(PrefsKeys.WALLPAPER_GRADIENT_INDEX, idx)
                .apply()
            Toast.makeText(
                activity,
                if (name != null) "Applied $name" else "Gradient wallpaper applied.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, PrefsKeys.REQUEST_PICK_WALLPAPER)
        } catch (_: ActivityNotFoundException) {
            // Fallback untuk device tanpa document picker (mis. beberapa Android TV box lama)
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                activity.startActivityForResult(fallback, PrefsKeys.REQUEST_PICK_WALLPAPER)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "There is no image picker app on this device.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PrefsKeys.REQUEST_PICK_WALLPAPER || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        // Persistable URI permission (OPEN_DOCUMENT); ignore if provider refuses
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { /* optional */ }
        // Always copy into app-private storage so the image survives reboot even when
        // the original SAF grant is revoked or the content provider disappears.
        activity.appRepository.ioExecutor.execute {
            val dest = java.io.File(activity.filesDir, "wallpaper_user.jpg")
            var copied = false
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied = dest.isFile && dest.length() > 0
            } catch (e: Exception) {
                dest.delete()
            }
            activity.runOnUiThread {
                val ed = activity.prefs.edit()
                    .putString(PrefsKeys.WALLPAPER_MODE, "image")
                    .putString(PrefsKeys.WALLPAPER_URI, uri.toString())
                if (copied) {
                    ed.putString(PrefsKeys.WALLPAPER_LOCAL_PATH, dest.absolutePath)
                    applyImageWallpaper(Uri.fromFile(dest), persist = false)
                } else {
                    ed.remove(PrefsKeys.WALLPAPER_LOCAL_PATH)
                    applyImageWallpaper(uri, persist = false)
                }
                ed.apply()
            }
        }
    }

    /**
     * Decode gambar wallpaper pilihan user secara EFISIEN (di-downsample ke ukuran layar,
     * bukan full-resolution) supaya tidak mengulang masalah performa yang sama seperti
     * hero.jpg bawaan yang dulu 6000x4000px. Decode dilakukan di background thread.
     */
    fun applyImageWallpaper(uri: Uri, persist: Boolean) {
        activity.wallpaperStatusText.text = "Loading image…"
        val screenW = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        activity.appRepository.ioExecutor.execute {
            try {
                val bmp = activity.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
                activity.runOnUiThread {
                    if (bmp != null) {
                        activity.findViewById<ImageView>(R.id.heroImage).apply {
                            background = null
                            visibility = View.VISIBLE
                            setImageBitmap(bmp)
                        }
                        activity.wallpaperStatusText.text = "Wallpaper: image from the gallery"
                        if (persist) Toast.makeText(activity, "Wallpaper applied", Toast.LENGTH_SHORT).show()
                    } else {
                        activity.wallpaperStatusText.text = "Failed to load image"
                        Toast.makeText(activity, "Failed to read the selected image.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    activity.wallpaperStatusText.text = "Failed to load image"
                    Toast.makeText(activity, "Wallpaper: ${e.message}", Toast.LENGTH_LONG).show()
                    // Kredensial akses URI mungkin sudah dicabut (mis. app galeri sumber dihapus) → balik ke default
                    applyDefaultWallpaper(persist = true)
                }
            }
        }
    }

    // ─── Preferensi lain (format jam, tab awal, reset) ───
    fun setupPreferences() {
        activity.clock24hSwitch.isChecked = activity.prefs.getBoolean(PrefsKeys.CLOCK_24H, true)
        activity.clock24hSwitch.isFocusable = false
        activity.clock24hSwitch.isClickable = false
        activity.clock24hSwitch.setOnCheckedChangeListener { _, checked ->
            activity.prefs.edit().putBoolean(PrefsKeys.CLOCK_24H, checked).apply()
        }
        activity.findViewById<View>(R.id.sidebarClock24hRow)?.let { row ->
            row.setOnClickListener {
                activity.clock24hSwitch.isChecked = !activity.clock24hSwitch.isChecked
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    activity.clock24hSwitch.isChecked = !activity.clock24hSwitch.isChecked
                    true
                } else false
            }
        }

        activity.defaultTabButton.text = tabDisplayName(readSavedDefaultTab())
        val cycleTab = { cycleDefaultTab() }
        activity.defaultTabButton.setOnClickListener { cycleTab() }
        activity.findViewById<View>(R.id.sidebarInitialTabRow)?.let { row ->
            row.setOnClickListener { cycleTab() }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    cycleTab(); true
                } else false
            }
        }
        activity.defaultTabButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { cycleDefaultTab(); true } else false
        }

        activity.findViewById<View>(R.id.sidebarNavBarRow)?.let { row ->
            row.isFocusable = true
            row.isFocusableInTouchMode = true
            row.isClickable = true
            row.setOnClickListener {
                populateNavBarEditor()
                showPage(SettingsPage.NAV_BAR)
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    populateNavBarEditor()
                    showPage(SettingsPage.NAV_BAR)
                    true
                } else false
            }
        }

        activity.resetSettingsButton.setOnClickListener { confirmResetSettings() }
        activity.resetSettingsButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { confirmResetSettings(); true } else false
        }

        val versionName = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (_: Exception) { null }
        activity.appVersionText.text = if (versionName != null) "Digi Home v$versionName" else "Digi Home"
    }

    internal fun tabDisplayName(tab: MainActivity.Tab) = when (tab) {
        MainActivity.Tab.HOME -> "Home"
        MainActivity.Tab.APPS -> "Apps"
        MainActivity.Tab.FILES -> "Files"
        MainActivity.Tab.WEATHER -> "Weather"
    }

    internal fun readSavedDefaultTab(): MainActivity.Tab {
        val name = activity.prefs.getString(PrefsKeys.DEFAULT_TAB, MainActivity.Tab.HOME.name) ?: MainActivity.Tab.HOME.name
        return try { MainActivity.Tab.valueOf(name) } catch (_: Exception) { MainActivity.Tab.HOME }
    }

    fun cycleDefaultTab() {
        // Hanya cycle tab yang masih tampil di nav (kalau user sembunyikan Weather, jangan tawarkan)
        val visible = readNavTabOrder().ifEmpty {
            listOf(MainActivity.Tab.HOME, MainActivity.Tab.APPS, MainActivity.Tab.FILES, MainActivity.Tab.WEATHER)
        }
        val current = readSavedDefaultTab()
        val idx = visible.indexOf(current).let { if (it < 0) 0 else it }
        val next = visible[(idx + 1) % visible.size]
        activity.prefs.edit().putString(PrefsKeys.DEFAULT_TAB, next.name).apply()
        activity.defaultTabButton.text = tabDisplayName(next)
    }

    // ─── Navigation bar editor (tampil/sembunyi + urutan) ───

    /** Baca urutan tab yang tampil. Default semua. */
    internal fun readNavTabOrder(): List<MainActivity.Tab> {
        val raw = activity.prefs.getString(PrefsKeys.NAV_TAB_ORDER, null)
        if (raw.isNullOrBlank()) {
            return listOf(
                MainActivity.Tab.HOME,
                MainActivity.Tab.APPS,
                MainActivity.Tab.FILES,
                MainActivity.Tab.WEATHER
            )
        }
        return raw.split(",")
            .mapNotNull { name ->
                try { MainActivity.Tab.valueOf(name.trim()) } catch (_: Exception) { null }
            }
            .distinct()
    }

    private fun saveNavTabOrder(order: List<MainActivity.Tab>) {
        // Minimal 1 tab harus tampil
        val safe = if (order.isEmpty()) listOf(MainActivity.Tab.HOME) else order
        activity.prefs.edit()
            .putString(PrefsKeys.NAV_TAB_ORDER, safe.joinToString(",") { it.name })
            .apply()
        // Kalau start tab disembunyikan, pindah ke tab pertama yang tampil
        if (readSavedDefaultTab() !in safe) {
            activity.prefs.edit().putString(PrefsKeys.DEFAULT_TAB, safe.first().name).apply()
            activity.defaultTabButton.text = tabDisplayName(safe.first())
        }
        activity.applyNavTabOrder(safe)
    }

    private fun populateNavBarEditor(focusTab: MainActivity.Tab? = null) {
        val list = activity.findViewById<LinearLayout>(R.id.navBarEditorList) ?: return
        // Ingat tab yang sedang difokus (atau yang baru diubah) supaya fokus tidak hilang setelah rebuild
        val keepFocus = focusTab ?: run {
            val focused = list.findFocus()
            var found: MainActivity.Tab? = null
            if (focused != null) {
                var v: View? = focused
                while (v != null && v !== list) {
                    val t = v.tag as? MainActivity.Tab
                    if (t != null) { found = t; break }
                    v = v.parent as? View
                }
            }
            found
        }
        list.removeAllViews()
        val visible = readNavTabOrder().toMutableList()
        val all = listOf(
            MainActivity.Tab.HOME,
            MainActivity.Tab.APPS,
            MainActivity.Tab.FILES,
            MainActivity.Tab.WEATHER
        )
        // Tampil dulu sesuai urutan, lalu yang disembunyikan di bawah
        val ordered = visible + all.filter { it !in visible }
        var rowToFocus: View? = null
        for (tab in ordered) {
            val isVisible = tab in visible
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (56 * density).toInt()
                )
                lp.bottomMargin = (8 * density).toInt()
                layoutParams = lp
                setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
                background = activity.getDrawable(R.drawable.bg_hover_solid)
                tag = tab // untuk restore fokus
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
            }
            attachFocusScale(row, 1f)

            val title = TextView(activity).apply {
                text = tabDisplayName(tab)
                setTextColor(colorWhite)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(title)

            val status = TextView(activity).apply {
                text = if (isVisible) "ON" else "OFF"
                setTextColor(if (isVisible) colorAccentSky else colorWhite50)
                textSize = 12f
                setPadding(0, 0, (10 * density).toInt(), 0)
            }
            row.addView(status)

            fun toggleVisibility() {
                val current = readNavTabOrder().toMutableList()
                if (tab in current) {
                    if (current.size <= 1) {
                        Toast.makeText(activity, "At least one menu must be displayed.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    current.remove(tab)
                } else {
                    current.add(tab)
                }
                saveNavTabOrder(current)
                populateNavBarEditor(focusTab = tab)
            }

            fun move(delta: Int) {
                val current = readNavTabOrder().toMutableList()
                val i = current.indexOf(tab)
                if (i < 0) return // hidden tidak bisa di-reorder
                val j = (i + delta).coerceIn(0, current.size - 1)
                if (i == j) return
                current.removeAt(i)
                current.add(j, tab)
                saveNavTabOrder(current)
                populateNavBarEditor(focusTab = tab)
            }

            row.setOnClickListener { toggleVisibility() }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        toggleVisibility(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isVisible) move(-1); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isVisible) move(1); true
                    }
                    else -> false
                }
            }

            if (isVisible) {
                val up = TextView(activity).apply {
                    text = "◀"
                    setTextColor(colorWhite60)
                    textSize = 14f
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    isFocusable = false
                    isClickable = true
                    setOnClickListener { move(-1) }
                }
                val down = TextView(activity).apply {
                    text = "▶"
                    setTextColor(colorWhite60)
                    textSize = 14f
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    isFocusable = false
                    isClickable = true
                    setOnClickListener { move(1) }
                }
                row.addView(up)
                row.addView(down)
            }

            list.addView(row)
            if (tab == keepFocus) rowToFocus = row
        }

        // Divider + entry into Shell Shortcuts manager
        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).also { it.topMargin = (12 * density).toInt(); it.bottomMargin = (12 * density).toInt() }
            setBackgroundColor(colorWhite20)
        }
        list.addView(divider)

        val scRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            layoutParams = lp
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        attachFocusScale(scRow, 1f)
        scRow.addView(TextView(activity).apply {
            text = "Shell Shortcuts"
            setTextColor(colorWhite)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        scRow.addView(TextView(activity).apply {
            text = "›"
            setTextColor(colorAccentSky)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        scRow.setOnClickListener {
            populateShellShortcutsEditor()
            showPage(SettingsPage.SHELL_SHORTCUTS)
        }
        scRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                populateShellShortcutsEditor()
                showPage(SettingsPage.SHELL_SHORTCUTS)
                true
            } else false
        }
        list.addView(scRow)

        val target = rowToFocus ?: list.getChildAt(0)
        target?.post {
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus()
        }
    }

    // ─── Shell Shortcuts manager ───────────────────────────────────────────

    private val shellRepo get() = activity.shellShortcutRepository

    private fun populateShellShortcutsEditor(focusId: String? = null) {
        val list = activity.findViewById<LinearLayout>(R.id.navBarEditorList) ?: return
        list.removeAllViews()

        // Ensure built-ins are seeded
        val all = shellRepo.loadAll()
        val active = all.filter { it.enabled }
        val inactive = all.filter { !it.enabled }

        list.addView(TextView(activity).apply {
            text = "Select a shortcut to edit. Use ◀ ▶ to reorder. Active items appear in the Shell."
            setTextColor(colorWhite50)
            textSize = 12f
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        fun sectionHeader(title: String) {
            list.addView(TextView(activity).apply {
                text = title
                setTextColor(colorAccentSky)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            })
        }

        var focusTarget: View? = null

        sectionHeader("Active Shortcuts (${active.size})")
        if (active.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No active shortcuts"
                setTextColor(colorWhite50)
                textSize = 13f
                setPadding((12 * density).toInt(), (4 * density).toInt(), 0, (8 * density).toInt())
            })
        }
        for (sc in active) {
            val row = buildShortcutRow(sc, isActive = true, sectionSize = active.size) {
                populateShellShortcutsEditor(focusId = sc.id)
            }
            list.addView(row)
            if (sc.id == focusId) focusTarget = row
        }

        sectionHeader("Inactive Shortcuts (${inactive.size})")
        if (inactive.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No inactive shortcuts"
                setTextColor(colorWhite50)
                textSize = 13f
                setPadding((12 * density).toInt(), (4 * density).toInt(), 0, (8 * density).toInt())
            })
        }
        for (sc in inactive) {
            val row = buildShortcutRow(sc, isActive = false, sectionSize = inactive.size) {
                populateShellShortcutsEditor(focusId = sc.id)
            }
            list.addView(row)
            if (sc.id == focusId) focusTarget = row
        }

        val addRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            ).also { it.topMargin = (16 * density).toInt() }
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        attachFocusScale(addRow, 1f)
        addRow.addView(TextView(activity).apply {
            text = "+ Add Shortcut"
            setTextColor(colorAccentSky)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addRow.setOnClickListener { openAppPickerForShortcut() }
        addRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                openAppPickerForShortcut(); true
            } else false
        }
        list.addView(addRow)

        val target = focusTarget ?: list.findViewWithTag<View>(active.firstOrNull()?.id)
            ?: list.findViewWithTag<View>(inactive.firstOrNull()?.id)
            ?: addRow
        target.post {
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus()
        }
    }

    /**
     * Row shows: [icon] [name + subtitle] [◀ ▶ reorder only].
     * Enable/Disable/Delete live in the edit dialog — not inline — so D-pad stays reliable.
     */
    private fun buildShortcutRow(
        sc: com.appsventory.digiapps.launchpad4.model.ShellShortcut,
        isActive: Boolean,
        sectionSize: Int,
        onChanged: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            ).also { it.bottomMargin = (6 * density).toInt() }
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            tag = sc.id
        }
        attachFocusScale(row, 1f)

        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = shellRepo.iconFor(sc)
            if (d != null) setImageDrawable(d) else setImageResource(R.drawable.ic_android_robot)
            if (sc.iconRef.startsWith("res:")) setColorFilter(colorWhite)
        }
        row.addView(icon)

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = (10 * density).toInt() }
        }
        texts.addView(TextView(activity).apply {
            text = sc.displayName.ifBlank { sc.packageName }
            setTextColor(colorWhite)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subtitle = when (sc.type) {
            com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_LAUNCHER -> "Built-in · always on"
            com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_SYSTEM -> "Built-in · System settings"
            com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_INTERNET -> "Built-in · Wi-Fi"
            else -> sc.packageName + if (sc.activityName.isNotBlank()) " / ${sc.activityName}" else ""
        }
        texts.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(colorWhite50)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(texts)

        fun doEdit() {
            openShortcutConfigForm(existing = sc)
        }
        fun doMove(delta: Int) {
            if (sectionSize <= 1) return
            shellRepo.moveInSection(sc.id, delta, activeSection = isActive)
            activity.refreshShellShortcuts()
            onChanged()
        }

        row.setOnClickListener { doEdit() }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { doEdit(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { doMove(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { doMove(1); true }
                else -> false
            }
        }

        // Only reorder controls inline (D-pad: Left/Right on the row, or these buttons)
        if (sectionSize > 1) {
            val moveLeft = TextView(activity).apply {
                text = "◀"
                setTextColor(colorWhite60)
                textSize = 14f
                setPadding((8 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                isFocusable = false
                isClickable = true
                setOnClickListener { doMove(-1) }
            }
            val moveRight = TextView(activity).apply {
                text = "▶"
                setTextColor(colorWhite60)
                textSize = 14f
                setPadding((4 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                isFocusable = false
                isClickable = true
                setOnClickListener { doMove(1) }
            }
            row.addView(moveLeft)
            row.addView(moveRight)
        }

        return row
    }

    private fun openAppPickerForShortcut() {
        val loading = Toast.makeText(activity, "Loading apps…", Toast.LENGTH_SHORT)
        loading.show()
        // Broader discovery: launcher + all packages visible under QUERY_ALL_PACKAGES
        activity.appRepository.loadAllDiscoverableApplications { apps ->
            loading.cancel()
            if (apps.isEmpty()) {
                Toast.makeText(activity, "No applications found", Toast.LENGTH_SHORT).show()
                return@loadAllDiscoverableApplications
            }
            showSpotlightAppPicker(apps)
        }
    }

    /**
     * Spotlight-inspired centered app picker with live search, icons, and D-pad support.
     * Filters the in-memory list — does not re-query PackageManager on each keystroke.
     */
    private fun showSpotlightAppPicker(allApps: List<android.content.pm.ApplicationInfo>) {
        val density = this.density
        val dialogWidth = (activity.resources.displayMetrics.widthPixels * 0.72f)
            .toInt()
            .coerceIn((320 * density).toInt(), (560 * density).toInt())

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Search field
        val search = android.widget.EditText(activity).apply {
            hint = "Search apps…"
            setHintTextColor(colorWhite50)
            setTextColor(colorWhite)
            textSize = 16f
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(0x33FFFFFF)
                setStroke((1 * density).toInt(), colorWhite20)
            }
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * density).toInt() }
        }
        root.addView(search)

        val emptyHint = TextView(activity).apply {
            text = "No matching applications"
            setTextColor(colorWhite50)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
            isFocusable = false
        }

        val listHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        val listScroll = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()
            )
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(listHost)
        }
        root.addView(listScroll)
        root.addView(emptyHint)

        var dialogRef: AlertDialog? = null

        fun bindResults(query: String) {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) allApps else allApps.filter { ai ->
                val label = try {
                    activity.appRepository.labelFor(ai).lowercase()
                } catch (_: Exception) { "" }
                label.contains(q) || ai.packageName.lowercase().contains(q)
            }
            listHost.removeAllViews()
            if (filtered.isEmpty()) {
                emptyHint.visibility = View.VISIBLE
                listScroll.visibility = View.GONE
                return
            }
            emptyHint.visibility = View.GONE
            listScroll.visibility = View.VISIBLE
            for (ai in filtered) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (52 * density).toInt()
                    ).also { it.bottomMargin = (4 * density).toInt() }
                    setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                    background = activity.getDrawable(R.drawable.bg_hover_solid)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                }
                attachFocusScale(row, 1f)
                val icon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    try {
                        setImageDrawable(ai.loadIcon(activity.packageManager))
                    } catch (_: Exception) {
                        setImageResource(R.drawable.ic_android_robot)
                    }
                }
                row.addView(icon)
                val texts = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.marginStart = (12 * density).toInt() }
                }
                val label = try { activity.appRepository.labelFor(ai) } catch (_: Exception) { ai.packageName }
                texts.addView(TextView(activity).apply {
                    text = label
                    setTextColor(colorWhite)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                texts.addView(TextView(activity).apply {
                    text = ai.packageName
                    setTextColor(colorWhite50)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(texts)
                row.setOnClickListener {
                    val pkg = ai.packageName
                    val launcherAct = shellRepo.resolveLauncherActivity(pkg) ?: ""
                    val draft = com.appsventory.digiapps.launchpad4.model.ShellShortcut(
                        displayName = label,
                        packageName = pkg,
                        activityName = launcherAct,
                        iconRef = "app",
                        enabled = true,
                        type = com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_USER,
                        action = com.appsventory.digiapps.launchpad4.model.ShellShortcut.ACTION_APP
                    )
                    dialogRef?.dismiss()
                    openShortcutConfigForm(existing = draft, isNew = true)
                }
                row.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        row.performClick(); true
                    } else false
                }
                listHost.addView(row)
            }
        }

        bindResults("")

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Keep focus on search while typing; rebuild list only
                val focusedWasSearch = search.hasFocus()
                bindResults(s?.toString().orEmpty())
                if (focusedWasSearch) search.requestFocus()
            }
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .create()
        dialogRef = dialog
        dialog.setOnShowListener {
            // Center-ish window; focus search immediately
            dialog.window?.setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            search.post {
                search.requestFocus()
            }
        }
        dialog.show()
    }

    private fun openShortcutConfigForm(
        existing: com.appsventory.digiapps.launchpad4.model.ShellShortcut,
        isNew: Boolean = false
    ) {
        val density = this.density
        var iconRef = existing.iconRef
        var enabled = if (existing.canDisable) existing.enabled else true

        val scroll = android.widget.ScrollView(activity).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            isFocusable = false
            clipChildren = false
        }
        scroll.addView(container)

        fun label(text: String) {
            container.addView(TextView(activity).apply {
                this.text = text
                setTextColor(colorWhite50)
                textSize = 12f
                setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
                isFocusable = false
            })
        }

        label("Display name")
        val nameEt = android.widget.EditText(activity).apply {
            setText(existing.displayName)
            setTextColor(colorWhite)
            setHintTextColor(colorWhite50)
            textSize = 14f
            isFocusable = true
            isFocusableInTouchMode = true
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
        }
        container.addView(nameEt)

        val isUser = existing.type == com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_USER
        var actEt: android.widget.EditText? = null
        if (isUser) {
            label("Package")
            container.addView(android.widget.EditText(activity).apply {
                setText(existing.packageName)
                setTextColor(colorWhite60)
                textSize = 13f
                isEnabled = false
                isFocusable = false
                background = activity.getDrawable(R.drawable.bg_hover_solid)
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            })
            label("Target Activity (blank = launcher)")
            actEt = android.widget.EditText(activity).apply {
                setText(existing.activityName)
                setTextColor(colorWhite)
                setHintTextColor(colorWhite50)
                textSize = 14f
                isFocusable = true
                isFocusableInTouchMode = true
                background = activity.getDrawable(R.drawable.bg_hover_solid)
                setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
            }
            container.addView(actEt)
        } else {
            label("Type")
            container.addView(TextView(activity).apply {
                text = when (existing.type) {
                    com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_LAUNCHER -> "Setting Launcher (protected)"
                    com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_SYSTEM -> "Setting System (built-in)"
                    com.appsventory.digiapps.launchpad4.model.ShellShortcut.TYPE_INTERNET -> "Internet (built-in)"
                    else -> existing.type
                }
                setTextColor(colorWhite)
                textSize = 13f
                setPadding((4 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                isFocusable = false
            })
        }

        label("Icon")
        val iconRowRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val iconPreview = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            isFocusable = false
        }
        val iconRefresh = Runnable {
            val tmp = existing.copy(iconRef = iconRef)
            val d = shellRepo.iconFor(tmp)
            if (d != null) iconPreview.setImageDrawable(d) else iconPreview.setImageResource(R.drawable.ic_android_robot)
            if (iconRef.startsWith("res:")) iconPreview.setColorFilter(colorWhite) else iconPreview.clearColorFilter()
        }
        iconRefresh.run()

        val changeIconBtn = TextView(activity).apply {
            text = "Change Icon"
            setTextColor(colorAccentSky)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (12 * density).toInt() }
            setOnClickListener {
                openIconPicker(currentRef = iconRef) { chosen ->
                    iconRef = chosen
                    iconRefresh.run()
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    openIconPicker(currentRef = iconRef) { chosen ->
                        iconRef = chosen
                        iconRefresh.run()
                    }
                    true
                } else false
            }
        }
        iconRowRow.addView(iconPreview)
        iconRowRow.addView(changeIconBtn)
        container.addView(iconRowRow)

        if (existing.canDisable) {
            label("Status")
            val enableBtn = TextView(activity).apply {
                fun sync() {
                    text = if (enabled) "Enabled — tap to disable" else "Disabled — tap to enable"
                    setTextColor(if (enabled) colorAccentSky else colorWhite50)
                }
                sync()
                textSize = 14f
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = activity.getDrawable(R.drawable.bg_hover_solid)
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                setOnClickListener {
                    enabled = !enabled
                    sync()
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        enabled = !enabled
                        sync()
                        true
                    } else false
                }
            }
            container.addView(enableBtn)
        } else {
            label("Status")
            container.addView(TextView(activity).apply {
                text = "Always enabled (protected)"
                setTextColor(colorAccentSky)
                textSize = 13f
                setPadding((4 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                isFocusable = false
            })
        }

        var deleteBtn: TextView? = null
        if (existing.canDelete && !isNew) {
            label("Danger zone")
            deleteBtn = TextView(activity).apply {
                text = "Delete Shortcut"
                setTextColor(activity.getColor(android.R.color.holo_red_light))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = activity.getDrawable(R.drawable.bg_settings_danger)
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                gravity = android.view.Gravity.CENTER
            }
            container.addView(deleteBtn)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "New Shortcut" else "Edit Shortcut")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEt.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Display name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val act = actEt?.text?.toString()?.trim().orEmpty() ?: existing.activityName
                val saved = existing.copy(
                    displayName = name,
                    activityName = if (isUser) act else existing.activityName,
                    iconRef = iconRef,
                    enabled = if (existing.canDisable) enabled else true
                )
                shellRepo.upsert(saved)
                activity.refreshShellShortcuts()
                populateShellShortcutsEditor(focusId = saved.id)
                showPage(SettingsPage.SHELL_SHORTCUTS)
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)

        if (isUser) {
            builder.setNeutralButton("Test") { _, _ ->
                val test = existing.copy(
                    displayName = nameEt.text?.toString()?.trim().orEmpty().ifEmpty { existing.displayName },
                    activityName = actEt?.text?.toString()?.trim().orEmpty() ?: existing.activityName,
                    iconRef = iconRef,
                    enabled = true
                )
                shellRepo.launch(test, showErrors = true)
            }
        }

        val dialog = builder.create()

        deleteBtn?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Remove shortcut?")
                .setMessage("Remove “${existing.displayName}”? This cannot be undone.")
                .setPositiveButton("Remove") { _, _ ->
                    shellRepo.remove(existing.id)
                    activity.refreshShellShortcuts()
                    dialog.dismiss()
                    populateShellShortcutsEditor()
                    showPage(SettingsPage.SHELL_SHORTCUTS)
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }
        deleteBtn?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                deleteBtn.performClick()
                true
            } else false
        }

        dialog.setOnShowListener {
            nameEt.post {
                nameEt.isFocusable = true
                nameEt.isFocusableInTouchMode = true
                nameEt.requestFocus()
                nameEt.setSelection(nameEt.text?.length ?: 0)
            }
        }
        dialog.show()
    }

    /**
     * Icon picker: fixed header + scrollable grid that fills dialog width.
     *
     * Layout regions (top → bottom):
     *   1. Header (title only) — not scrollable, not overlapping the grid
     *   2. ScrollView with GridLayout — only region that scrolls
     *   3. AlertDialog Cancel button (framework-owned, outside our content)
     *
     * Width: measure after show; cells use equal column weights so the grid
     * fills the content area with no empty band on the right.
     */
    private fun openIconPicker(currentRef: String, onChosen: (String) -> Unit) {
        val density = this.density
        val choices = mutableListOf("app") + shellRepo.builtInIconNames().map { "res:$it" }
        val padH = (14 * density).toInt()
        val padV = (12 * density).toInt()
        val gap = (6 * density).toInt()
        val minCell = (40 * density).toInt()
        val maxCols = 9

        // ── Root: vertical stack of HEADER + SCROLLABLE GRID ──
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // No clipChildren=false on root — that allowed scaled cells to draw over the title
            clipChildren = true
            clipToPadding = true
        }

        // ── 1. Fixed header ──
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(padH, padV, padH, (10 * density).toInt())
            isFocusable = false
            // Solid background so scrolled/scaled icons cannot show through the title
            setBackgroundColor(0xFF1C1C1E.toInt())
        }
        header.addView(TextView(activity).apply {
            text = "Select an icon"
            setTextColor(colorWhite)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isFocusable = false
            setPadding(0, 0, 0, 0)
        })
        root.addView(header)

        // ── 2. Scrollable grid region only ──
        val gridHost = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // Allow focus scale to draw slightly outside the cell without covering the header
            // (header is a sibling above the scroll view, not a child)
            clipChildren = false
            clipToPadding = false
        }
        val maxGridH = (activity.resources.displayMetrics.heightPixels * 0.42f)
            .toInt()
            .coerceIn((200 * density).toInt(), (380 * density).toInt())
        val gridScroll = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxGridH
            )
            setPadding(padH, 0, padH, (8 * density).toInt())
            isFocusable = false
            isFillViewport = false
            clipToPadding = true
            // afterDescendants so D-pad reaches cells
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(gridHost)
        }
        root.addView(gridScroll)

        var selectedRef = currentRef

        fun styleCell(v: View, ref: String, focused: Boolean) {
            val selected = ref == selectedRef
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setColor(
                    when {
                        focused -> 0x554FC3F7
                        selected -> 0x334FC3F7
                        else -> 0x18FFFFFF
                    }
                )
                if (selected) setStroke((2 * density).toInt(), colorAccentSky)
                else if (focused) setStroke((2 * density).toInt(), colorWhite)
                else setStroke((1 * density).toInt(), colorWhite20)
            }
            v.background = bg
            // Keep scale modest so cells do not visually collide with the header
            if (focused) {
                v.scaleX = 1.06f
                v.scaleY = 1.06f
            } else {
                v.scaleX = 1f
                v.scaleY = 1f
            }
        }

        fun buildGrid(contentWidthPx: Int) {
            if (contentWidthPx <= 0) return
            gridHost.removeAllViews()
            val available = contentWidthPx.coerceAtLeast(minCell)

            var cols = maxCols
            while (cols > 3) {
                val cell = (available - gap * (cols - 1)) / cols
                if (cell >= minCell) break
                cols--
            }
            val cellSize = ((available - gap * (cols - 1)) / cols).coerceAtLeast(minCell)
            val iconInner = (cellSize * 0.55f).toInt().coerceAtLeast((20 * density).toInt())

            val grid = android.widget.GridLayout(activity).apply {
                columnCount = cols
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
                isFocusable = false
                clipChildren = false
                clipToPadding = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val cells = mutableListOf<View>()
            for ((index, ref) in choices.withIndex()) {
                val col = index % cols
                val row = index / cols
                val cell = FrameLayout(activity).apply {
                    val lp = android.widget.GridLayout.LayoutParams(
                        android.widget.GridLayout.spec(row),
                        android.widget.GridLayout.spec(col, 1f)
                    ).apply {
                        width = 0
                        height = cellSize
                        setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                    }
                    layoutParams = lp
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    tag = ref
                    contentDescription = if (ref == "app") "App icon" else ref.removePrefix("res:")
                }
                val iv = ImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(iconInner, iconInner, android.view.Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isFocusable = false
                    if (ref == "app") {
                        setImageResource(R.drawable.ic_android_robot)
                        setColorFilter(colorWhite)
                    } else {
                        val id = shellRepo.drawableIdForRef(ref)
                        if (id != 0) {
                            setImageResource(id)
                            setColorFilter(colorWhite)
                        } else {
                            setImageResource(R.drawable.ic_android_robot)
                            setColorFilter(colorWhite50)
                        }
                    }
                }
                cell.addView(iv)
                styleCell(cell, ref, focused = false)
                cell.setOnFocusChangeListener { v, hasFocus -> styleCell(v, ref, hasFocus) }
                cell.setOnClickListener {
                    selectedRef = ref
                    cells.forEach { c -> styleCell(c, c.tag as String, c.isFocused) }
                    onChosen(ref)
                    (root.tag as? AlertDialog)?.dismiss()
                }
                cell.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        cell.performClick(); true
                    } else false
                }
                grid.addView(cell)
                cells.add(cell)
            }
            gridHost.addView(grid)

            val initial = cells.find { it.tag == currentRef } ?: cells.firstOrNull()
            initial?.post { initial.requestFocus() }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .create()
        root.tag = dialog

        dialog.setOnShowListener {
            val screenW = activity.resources.displayMetrics.widthPixels
            val targetW = (screenW * 0.82f).toInt()
                .coerceIn((360 * density).toInt(), (760 * density).toInt())
            dialog.window?.setLayout(targetW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

            root.post {
                // Width available to the grid = scroll view content width (after padding)
                val measured = when {
                    gridScroll.width > 0 ->
                        gridScroll.width - gridScroll.paddingLeft - gridScroll.paddingRight
                    root.width > 0 ->
                        root.width - padH * 2
                    else ->
                        targetW - padH * 2
                }
                buildGrid(measured)
            }
        }
        dialog.show()
    }

    fun confirmResetSettings() {
        AlertDialog.Builder(activity)
            .setTitle("Reset all settings?")
            .setMessage("This will return wallpaper, media providers, favorite apps, and other preferences to default. This action cannot be undone.")
            .setPositiveButton("Reset all") { _, _ -> performResetSettings() }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .show()
    }

    fun performResetSettings() {
        activity.prefs.edit().clear().apply()
        Toast.makeText(activity, "Launcher settings reset.", Toast.LENGTH_SHORT).show()
        closeSidebar()
        activity.recreate()
    }

    /** Buka sidebar Pengaturan langsung ke halaman Edit Channel — dipakai
     *  kartu promo "Tambah Channel" di Home saat Channel belum aktif. */
    fun openChannelSettings() {
        openSidebar()
        showPage(SettingsPage.CUSTOM_CHANNEL)
    }

    fun openSidebar() {        if (activity.sidebarOpen) return
        openedAsSelectFavorite = false
        activity.sidebarOpen = true
        showPage(SettingsPage.ROOT)
        FocusLock.lock(activity.mainContent, this)
        try {
            activity.loadApplications()
            populateChannelList()
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to load channel: ${e.message}", Toast.LENGTH_LONG).show()
        }
        try {
            val dateFmt = java.text.SimpleDateFormat("EEE MMM d, yyyy", java.util.Locale.US)
            activity.findViewById<TextView>(R.id.settingsProfileDate)?.text = dateFmt.format(java.util.Date())
            activity.findViewById<TextView>(R.id.settingsProfileName)?.text =
                android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "User"
        } catch (_: Exception) {}
        // Tint android icon for focus (white default, dark when focused via color state)
        activity.findViewById<ImageView>(R.id.rootAllAppsIcon)?.imageTintList =
            activity.getColorStateList(R.color.settings_menu_icon)
        // Sync profile avatar with top bar (same drawable)
        activity.findViewById<ImageView>(R.id.settingsProfileAvatar)?.setImageResource(R.drawable.ic_person)
        activity.sidebarScrim.visibility = View.VISIBLE
        activity.sidebarScrim.alpha = 0f
        val w = activity.sidebarPanel.width.takeIf { it > 0 }?.toFloat()
            ?: (440 * activity.resources.displayMetrics.density)
        activity.sidebarPanel.translationX = w
        activity.sidebarPanel.animate().translationX(0f).setDuration(250).start()
        activity.sidebarScrim.animate().alpha(1f).setDuration(250).start()
    }

    /** Buka sidebar mode pilih favorite (reuse panel settings, judul "Select app"). */
    fun openSelectFavorite() {
        openedAsSelectFavorite = true
        showingNonTvOnly = false
        if (activity.sidebarOpen) {
            populateSelectFavoriteApps()
            showPage(SettingsPage.SELECT_FAVORITE)
            return
        }
        activity.sidebarOpen = true
        FocusLock.lock(activity.mainContent, this)
        populateSelectFavoriteApps()
        showPage(SettingsPage.SELECT_FAVORITE)
        activity.sidebarScrim.visibility = View.VISIBLE
        activity.sidebarScrim.alpha = 0f
        val w = activity.sidebarPanel.width.takeIf { it > 0 }?.toFloat()
            ?: (440 * activity.resources.displayMetrics.density)
        activity.sidebarPanel.translationX = w
        activity.sidebarPanel.animate().translationX(0f).setDuration(250).start()
        activity.sidebarScrim.animate().alpha(1f).setDuration(250).start()
    }

    /**
     * Isi list app yang belum favorite. Klik = addFavorite + hilangkan baris.
     * Desain row sama dengan All Apps (bg_settings_app_row) biar konsisten.
     *
     * App "made for TV" (leanback + punya banner) tampil apa adanya di daftar utama.
     * App non-TV (gak ada banner ATAU gak leanback) DIKUMPULKAN jadi 1 kartu collapsed
     * "Non TV App" (desain row sama, icon grid) di PALING BAWAH — diklik dulu baru
     * drill-in ke daftar non-TV-nya sendiri (halaman yang sama, judul ganti jadi
     * "Non TV Apps", tombol Back balik ke daftar utama). Semua tetap bisa ditambah,
     * cuma dikelompokkan biar daftar utama bersih dari app yang emang bukan buat TV.
     */
    private fun populateSelectFavoriteApps() {
        val list = activity.findViewById<LinearLayout>(R.id.allAppsList) ?: return
        val status = activity.findViewById<TextView>(R.id.allAppsStatus)
        val d = density
        val gap = (6 * d).toInt()

        list.removeAllViews()
        status?.visibility = View.GONE

        val candidates = activity.installedApps.filter { !activity.isFavorite(it.packageName) }
        if (candidates.isEmpty()) {
            status?.visibility = View.VISIBLE
            status?.text = "All applications have been added to Favorites."
            return
        }

        val (otherApps, tvApps) = candidates.partition { app ->
            val (banner, _, _) = activity.appRepository.visualsFor(app)
            banner == null || !activity.appRepository.isTvApp(app.packageName)
        }

        if (showingNonTvOnly) {
            // Drill-in: cuma app non-TV apa adanya, row biasa.
            if (otherApps.isEmpty()) {
                status?.visibility = View.VISIBLE
                status?.text = "All non-TV apps have been added to Favorites."
            } else {
                for (app in otherApps) addAppRow(list, app, gap, d)
            }
            list.getChildAt(0)?.requestFocus()
            return
        }

        // Daftar utama: app TV apa adanya, kartu "Non TV App" collapsed di paling bawah.
        for (app in tvApps) addAppRow(list, app, gap, d)
        if (otherApps.isNotEmpty()) addNonTvCard(list, otherApps.size, gap, d)
        if (tvApps.isEmpty() && otherApps.isEmpty()) {
            status?.visibility = View.VISIBLE
            status?.text = "All applications have been added to Favorites."
        }
        list.getChildAt(0)?.requestFocus()
    }

    /**
     * Kartu collapsed "Non TV App (n)" — desain row identik app row biasa, cuma
     * icon-nya grid dan klik-nya bukan addFavorite tapi drill-in ke daftar non-TV.
     */
    private fun addNonTvCard(list: LinearLayout, count: Int, gap: Int, d: Float) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (12 * d).toInt()
            val padV = (12 * d).toInt()
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            isClickable = true
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        val icon = ImageView(activity).apply {
            val h = (52 * d).toInt()
            val w = (h * 16f / 9f).toInt()

            layoutParams = LinearLayout.LayoutParams(w, h)
            setImageResource(R.drawable.ic_grid)
            scaleType = ImageView.ScaleType.CENTER
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF9F2"))
                cornerRadius = 7f * d
            }

            clipToOutline = true
        }
        val nameTv = TextView(activity).apply {
            text = "Non-TV App ($count)"
            setTextColor(colorWhite)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * d).toInt()
            }
        }
        row.addView(icon)
        row.addView(nameTv)
        row.setOnClickListener {
            showingNonTvOnly = true
            populateSelectFavoriteApps()
            showPage(SettingsPage.SELECT_FAVORITE)
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                row.performClick()
                true
            } else false
        }
        list.addView(row)
    }

    /** Satu baris app di sidebar Select app (dipakai daftar utama maupun drill-in non-TV). */
    private fun addAppRow(list: LinearLayout, app: ApplicationInfo, gap: Int, d: Float) {
        val (banner, iconFallback, labelCs) = activity.appRepository.visualsFor(app)
        val label = labelCs?.toString() ?: app.packageName
        val iconDr = banner ?: iconFallback
        val pkg = app.packageName

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (12 * d).toInt()
            val padV = (12 * d).toInt()
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            isClickable = true
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap }
        }
        val icon = ImageView(activity).apply {
            val h = (52 * d).toInt()

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                h
            )

            setImageDrawable(iconDr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true

            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 7f * d
            }

            clipToOutline = true
        }
        val nameTv = TextView(activity).apply {
            text = label
            setTextColor(colorWhite)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * d).toInt()
            }
        }
        row.addView(icon)
        row.addView(nameTv)
        row.setOnClickListener {
            activity.addFavorite(pkg)
            list.removeView(row)
            if (list.childCount == 0) {
                activity.findViewById<TextView>(R.id.allAppsStatus)?.apply {
                    visibility = View.VISIBLE
                    text = if (showingNonTvOnly) "All non-TV apps have been added to Favorites."
                        else "All applications have been added to Favorites."
                }
            } else {
                // Fokus ke item berikutnya biar D-pad tetap nyaman
                list.getChildAt(0)?.requestFocus()
            }
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                row.performClick()
                true
            } else false
        }
        list.addView(row)
    }

    fun closeSidebar() {
        if (!activity.sidebarOpen) return
        val wasSelectFavorite = openedAsSelectFavorite
        openedAsSelectFavorite = false
        showingNonTvOnly = false
        activity.sidebarOpen = false
        FocusLock.unlock(activity.mainContent, this)
        val w = activity.sidebarPanel.width.toFloat().coerceAtLeast(1f)
        activity.sidebarPanel.animate().translationX(w).setDuration(220).withEndAction {
            activity.sidebarScrim.visibility = View.GONE
        }.start()
        activity.sidebarScrim.animate().alpha(0f).setDuration(220).start()
        if (wasSelectFavorite) {
            activity.appsContainer.post {
                val addBtn = (0 until activity.appsContainer.childCount)
                    .mapNotNull { activity.appsContainer.getChildAt(it) }
                    .lastOrNull { it.tag == "add_favorite" }
                    ?: activity.appsContainer.getChildAt(activity.appsContainer.childCount - 1)
                addBtn?.requestFocus()
            }
        } else {
            // Restore focus to the Shell shortcut that opened settings (or the first one).
            // Legacy homeIcon is GONE — dynamic shell_sc_* views own the action pill.
            activity.findViewById<View>(R.id.actionPill)?.post {
                val pill = activity.findViewById<LinearLayout>(R.id.actionPill)
                val preferredId = activity.pendingShellFocusId
                activity.pendingShellFocusId = null
                var target: View? = null
                if (pill != null) {
                    for (i in 0 until pill.childCount) {
                        val c = pill.getChildAt(i)
                        val t = c.tag
                        if (t is String && t.startsWith("shell_sc_")) {
                            if (preferredId != null && t == "shell_sc_$preferredId") {
                                target = c
                                break
                            }
                            if (target == null) target = c
                        }
                    }
                }
                (target ?: activity.homeButton)?.requestFocus()
            }
        }
    }

    fun updateContentStatus(text: String) {
        activity.providerConfigStatus.text = text
    }

    private fun refreshCarouselSummary() {
        val summary = activity.findViewById<TextView>(R.id.providerSummary)
        if (!activity.bannerEnabled) {
            summary.text = "Off — recently added media"
            return
        }
        val id = activity.carouselChannelId
        if (id == null) {
            summary.text = "Recently added media"
            return
        }
        val ch = activity.tvChannelRepository.listChannels().firstOrNull { it.id == id }
        summary.text = ch?.displayName ?: "Recently added media"
    }

    private fun refreshCustomSummary() {
        val summary = activity.findViewById<TextView>(R.id.customChannelSummary)
        if (!activity.customChannelEnabled) {
            summary.text = "Non-active"
            return
        }
        val n = activity.customChannelIds.size
        summary.text = when {
            n == 0 -> "0 channels displayed"
            n == 1 -> "1 channel displayed"
            else -> "$n channels displayed"
        }
    }

    /** Alias lama — beberapa call-site masih memakai nama ini. */
    fun populateChannelList() = populateCarouselApps()

    /** Halaman Content Carousel: daftar app (dikelompokkan per package). */
    private fun openAppInfoSettings(packageName: String) {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )

            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Unable to open app info",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    /** Semua package terinstal (termasuk system) + ukuran APK. */
    private fun populateAllApps() {
        val list = activity.findViewById<android.widget.LinearLayout>(R.id.allAppsList) ?: return
        val status = activity.findViewById<TextView>(R.id.allAppsStatus)
        list.removeAllViews()
        status?.text = "Loading…"

        val d = activity.resources.displayMetrics.density
        val gap = (6 * d).toInt()

        activity.appRepository.ioExecutor.execute {
            val pm = activity.packageManager

            val apps = try {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            } catch (_: Exception) {
                emptyList()
            }

            fun labelOf(app: android.content.pm.ApplicationInfo): String {
                return try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }
            }

            fun isLaunchable(app: android.content.pm.ApplicationInfo): Boolean {
                return try {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    intent != null
                } catch (_: Exception) {
                    false
                }
            }

            fun sizeOf(app: android.content.pm.ApplicationInfo): Long {
                return try {
                    val path = app.sourceDir ?: app.publicSourceDir ?: return 0L
                    java.io.File(path).length()
                } catch (_: Exception) {
                    0L
                }
            }

            fun fmt(bytes: Long): String {
                if (bytes <= 0L) return "—"

                val kb = bytes / 1024.0
                val mb = kb / 1024.0

                return if (mb >= 1.0) {
                    String.format("%.1f MB", mb)
                } else {
                    String.format("%.0f KB", kb)
                }
            }

            /*
             * Group 1:
             * Aplikasi yang bukan system app.
             */
            val installedApps = apps
                .filter {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .sortedBy {
                    labelOf(it).lowercase()
                }

            /*
             * Group 2:
             * System app yang mempunyai launcher activity.
             */
            val systemApps = apps
                .filter {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            isLaunchable(it)
                }
                .sortedBy {
                    labelOf(it).lowercase()
                }

            /*
             * Group 3:
             * Semua sisanya.
             */
            val groupedPackages =
                (installedApps + systemApps)
                    .map { it.packageName }
                    .toHashSet()

            val otherApps = apps
                .filter {
                    it.packageName !in groupedPackages
                }
                .sortedBy {
                    labelOf(it).lowercase()
                }

            fun addSectionTitle(title: String) {
                val titleView = TextView(activity).apply {
                    text = title
                    setTextColor(colorWhite)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)

                    setPadding(
                        (4 * d).toInt(),
                        (14 * d).toInt(),
                        (4 * d).toInt(),
                        (6 * d).toInt()
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                list.addView(titleView)
            }

            fun addAppRow(app: android.content.pm.ApplicationInfo) {
                val label = labelOf(app)
                val iconDr = try {
                    app.loadIcon(pm)
                } catch (_: Exception) {
                    null
                }

                val sizeText = fmt(sizeOf(app))
                val pkg = app.packageName

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL

                    val padH = (12 * d).toInt()
                    val padV = (10 * d).toInt()

                    setPadding(
                        padH,
                        padV,
                        padH,
                        padV
                    )

                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true

                    background = activity.getDrawable(
                        R.drawable.bg_hover_solid
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = gap
                    }
                }

                val icon = ImageView(activity).apply {
                    val s = (36 * d).toInt()

                    layoutParams = LinearLayout.LayoutParams(s, s)

                    setImageDrawable(iconDr)

                    scaleType = ImageView.ScaleType.FIT_CENTER

                    background = android.graphics.drawable.ColorDrawable(
                        0xFF2A2A2A.toInt()
                    )
                }

                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL

                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = (12 * d).toInt()
                        marginEnd = (6 * d).toInt()
                    }
                }

                col.addView(TextView(activity).apply {
                    text = label
                    setTextColor(colorWhite)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })

                col.addView(TextView(activity).apply {
                    text = pkg
                    setTextColor(colorWhite60)
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END

                    setPadding(
                        0,
                        (1 * d).toInt(),
                        0,
                        0
                    )
                })

                val sizeTv = TextView(activity).apply {
                    text = sizeText
                    setTextColor(colorWhite60)
                    textSize = 12f
                }

                row.addView(icon)
                row.addView(col)
                row.addView(sizeTv)

                row.setOnClickListener {
                    if (isLaunchable(app)) {
                        showAppDetail(pkg)
                    } else {
                        openAppInfoSettings(pkg)
                    }
                }

                row.setOnKeyListener { _, keyCode, event ->
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                        (
                                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        keyCode == KeyEvent.KEYCODE_ENTER
                                )
                    ) {
                        row.performClick()
                        true
                    } else {
                        false
                    }
                }

                list.addView(row)
            }

            activity.runOnUiThread {
                list.removeAllViews()

                status?.visibility = android.view.View.GONE

                if (installedApps.isNotEmpty()) {
                    addSectionTitle("Installed Applications")

                    installedApps.forEach {
                        addAppRow(it)
                    }
                }

                if (systemApps.isNotEmpty()) {
                    addSectionTitle("System Application")

                    systemApps.forEach {
                        addAppRow(it)
                    }
                }

                if (otherApps.isNotEmpty()) {
                    addSectionTitle("Other Applications")

                    otherApps.forEach {
                        addAppRow(it)
                    }
                }

                list.getChildAt(1)?.requestFocus()
            }
        }
    }

    private var detailPackage: String? = null

    private fun setActionButton(btn: TextView?, enabled: Boolean, onClick: (() -> Unit)?) {
        if (btn == null) return
        btn.isEnabled = enabled
        btn.isFocusable = enabled
        btn.isClickable = enabled
        btn.alpha = if (enabled) 1f else 0.4f
        btn.setOnClickListener(if (enabled && onClick != null) View.OnClickListener { onClick() } else null)
    }

    private fun showAppDetail(pkg: String) {
        detailPackage = pkg
        val pm = activity.packageManager
        val appInfo = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) { null }
        val label = try { pm.getApplicationLabel(appInfo!!).toString() } catch (_: Exception) { pkg }
        val iconDr = try { appInfo?.loadIcon(pm) } catch (_: Exception) { null }
        var version = ""
        try {
            @Suppress("DEPRECATION")
            version = "Version " + (pm.getPackageInfo(pkg, 0).versionName ?: "—")
        } catch (_: Exception) {}
        var sizeBytes = 0L
        try {
            val path = appInfo?.sourceDir ?: appInfo?.publicSourceDir
            if (path != null) sizeBytes = java.io.File(path).length()
        } catch (_: Exception) {}
        fun fmt(bytes: Long): String {
            if (bytes <= 0L) return "—"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB used in internal storage", mb)
            else String.format("%.0f kB used in internal storage", kb)
        }
        fun fmtShort(bytes: Long): String {
            if (bytes <= 0L) return "—"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f kB", kb)
        }

        activity.findViewById<ImageView>(R.id.appDetailIcon)?.setImageDrawable(iconDr)
        activity.findViewById<TextView>(R.id.appDetailName)?.text = label
        activity.findViewById<TextView>(R.id.appDetailVersion)?.text = version
        activity.findViewById<TextView>(R.id.appDetailStorageValue)?.text = fmt(sizeBytes)
        activity.findViewById<TextView>(R.id.appDetailClearDataValue)?.text = fmtShort(sizeBytes)
        activity.findViewById<TextView>(R.id.appDetailClearCacheValue)?.text = "—"
        activity.findViewById<TextView>(R.id.appDetailClearDefaultsValue)?.text = "no defaults set"
        // Permissions as chip rows (not plain multiline text)
        val permList = activity.findViewById<LinearLayout>(R.id.appDetailPermissionsList)
        val permEmpty = activity.findViewById<TextView>(R.id.appDetailPermissions)
        permList?.removeAllViews()
        try {
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
            val requested = pi.requestedPermissions
            if (requested.isNullOrEmpty()) {
                permEmpty?.visibility = android.view.View.VISIBLE
                permEmpty?.text = "No permissions"
                permList?.visibility = android.view.View.GONE
            } else {
                permEmpty?.visibility = android.view.View.GONE
                permList?.visibility = android.view.View.VISIBLE
                val d = density
                for (raw in requested.take(20)) {
                    val short = raw.removePrefix("android.permission.")
                        .removePrefix("android.permission. ")
                    val chip = TextView(activity).apply {
                        text = short
                        setTextColor(0xFFCCCCCC.toInt())
                        textSize = 12f
                        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                        background = activity.getDrawable(R.drawable.bg_settings_perm_chip)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (6 * d).toInt() }
                    }
                    permList?.addView(chip)
                }
                if (requested.size > 20) {
                    permList?.addView(TextView(activity).apply {
                        text = "+${requested.size - 20} more"
                        setTextColor(colorWhite60)
                        textSize = 11f
                        setPadding(0, (4 * d).toInt(), 0, 0)
                    })
                }
            }
        } catch (_: Exception) {
            permEmpty?.visibility = android.view.View.VISIBLE
            permEmpty?.text = "No permissions"
            permList?.visibility = android.view.View.GONE
        }

        // Cache size: StorageStatsManager (API 26+) when possible
        fun fmtBytes(bytes: Long): String {
            if (bytes < 0L) return "—"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f kB", kb)
        }
        activity.findViewById<TextView>(R.id.appDetailClearCacheValue)?.text = "…"
        activity.appRepository.ioExecutor.execute {
            var cacheStr = "—"
            var dataStr: String? = null
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val ssm = activity.getSystemService(android.app.usage.StorageStatsManager::class.java)
                    val uuid = android.os.storage.StorageManager.UUID_DEFAULT
                    val uid = appInfo?.uid ?: -1
                    if (ssm != null && uid >= 0) {
                        val stats = ssm.queryStatsForUid(uuid, uid)
                        cacheStr = fmtBytes(stats.cacheBytes)
                        dataStr = fmtBytes(stats.dataBytes)
                    }
                }
            } catch (_: SecurityException) {
                cacheStr = "Need usage access"
            } catch (_: Exception) {
                cacheStr = "—"
            }
            val dataFinal = dataStr
            val cacheFinal = cacheStr
            activity.runOnUiThread {
                activity.findViewById<TextView>(R.id.appDetailClearCacheValue)?.text = cacheFinal
                if (dataFinal != null) {
                    activity.findViewById<TextView>(R.id.appDetailClearDataValue)?.text = dataFinal
                }
            }
        }

        // Launch: only if has launcher intent
        val launchIntent = activity.appRepository.launchIntentFor(pkg)
        setActionButton(
            activity.findViewById(R.id.appDetailLaunch),
            launchIntent != null
        ) {
            try { activity.startActivity(launchIntent) } catch (_: Exception) {}
        }

        // Uninstall: not for system apps (no FLAG_SYSTEM or updated system)
        val isSystem = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val canUninstall = appInfo != null && (!isSystem || isUpdatedSystem) && pkg != activity.packageName
        setActionButton(
            activity.findViewById(R.id.appDetailUninstall),
            canUninstall
        ) {
            try {
                activity.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
            } catch (_: Exception) {
                Toast.makeText(activity, "Cannot uninstall", Toast.LENGTH_SHORT).show()
            }
        }

        // Clear data / cache / force-stop of OTHER apps require system privileges
        // (DELETE_CACHE_FILES / CLEAR_APP_USER_DATA / FORCE_STOP_PACKAGES).
        // Regular launcher cannot do it in-process; we open system app details.
        // For THIS app we can clear own cache via cacheDir delete.
        fun openSystemAppDetails() {
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$pkg")
                })
            } catch (_: Exception) {
                Toast.makeText(activity, "Cannot open system settings", Toast.LENGTH_SHORT).show()
            }
        }
        // Force stop / app details: always open system app details if package exists
        val canDetails = appInfo != null
        setActionButton(
            activity.findViewById(R.id.openAppDetail),
            canDetails
        ) {
            openSystemAppDetails();
        }

        val isSelf = pkg == activity.packageName

        activity.findViewById<View>(R.id.appDetailStorageRow)?.apply {
            isEnabled = canDetails; isFocusable = canDetails; isClickable = canDetails
            setOnClickListener(if (canDetails) View.OnClickListener { openSystemAppDetails() } else null)
        }
        activity.findViewById<View>(R.id.appDetailClearDataRow)?.apply {
            isEnabled = canDetails; isFocusable = canDetails; isClickable = canDetails
            setOnClickListener {
                if (isSelf) {
                    Toast.makeText(activity, "Clear data for Digi Home opens system settings", Toast.LENGTH_SHORT).show()
                }
                openSystemAppDetails()
            }
        }
        activity.findViewById<View>(R.id.appDetailClearCacheRow)?.apply {
            isEnabled = canDetails; isFocusable = canDetails; isClickable = canDetails
            setOnClickListener {
                if (isSelf) {
                    // In-app: clear own cache directories
                    try {
                        activity.cacheDir?.deleteRecursively()
                        activity.codeCacheDir?.deleteRecursively()
                        activity.externalCacheDir?.deleteRecursively()
                        activity.findViewById<TextView>(R.id.appDetailClearCacheValue)?.text = "0 kB"
                        Toast.makeText(activity, "Cache cleared", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Other apps: need system permission — open system UI
                    openSystemAppDetails()
                }
            }
        }
        activity.findViewById<View>(R.id.appDetailClearDefaultsRow)?.apply {
            isEnabled = canDetails; isFocusable = canDetails; isClickable = canDetails
            setOnClickListener(if (canDetails) View.OnClickListener { openSystemAppDetails() } else null)
        }

        showPage(SettingsPage.APP_DETAIL)
    }

    fun populateCarouselApps() {
        drillMode = DrillMode.CAROUSEL
        drillPackage = null
        val list = activity.providerList
        list.removeAllViews()
        val vis = if (activity.bannerEnabled) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.carouselAppsSectionTitle).visibility = vis
        list.visibility = vis
        activity.providerConfigStatus.visibility = vis
        if (!activity.bannerEnabled) {
            updateContentStatus("Nyalakan Content Carousel untuk memilih sumber.")
            refreshCarouselSummary()
            return
        }
        val channels = activity.tvChannelRepository.listChannels()
        if (channels.isEmpty()) {
            list.addView(emptyHintView(
                "Belum ada channel terdeteksi.\n\nBuka app media (Jellyfin, Plex, YouTube), lalu approve \"Add to home screen\"."
            ))
            updateContentStatus("Tidak ada channel")
            refreshCarouselSummary()
            return
        }
        val byPkg = channels.groupBy { it.packageName }
        updateContentStatus("${byPkg.size} aplikasi · ketuk untuk pilih channel (satu saja)")
        for ((pkg, chs) in byPkg.toSortedMap()) {
            list.addView(appRow(pkg, chs.size) {
                drillPackage = pkg
                drillMode = DrillMode.CAROUSEL
                fun bind() {
                    populateChannelChoices(list, chs, selectedId = activity.carouselChannelId) { id ->
                        activity.carouselChannelId = id
                        activity.enabledChannelIds.clear()
                        activity.enabledChannelIds.add(id)
                        persistCarouselChannel()
                        activity.loadRecommendations()
                        refreshCarouselSummary()
                        bind()
                    }
                }
                bind()
                showPage(SettingsPage.CONTENT_CHANNELS)
            })
        }
        refreshCarouselSummary()
    }


    /**
     * Edit channels — gaya Projectivy:
     * daftar datar semua Preview Channel + toggle Visible (multi).
     * Tidak ada drill-down per app.
     */
    fun populateCustomApps() {
        drillMode = DrillMode.CUSTOM
        drillPackage = null
        val list = customChannelList
        list.removeAllViews()
        val vis = if (activity.customChannelEnabled) View.VISIBLE else View.GONE
        list.visibility = vis
        activity.findViewById<TextView>(R.id.customChannelStatus).visibility = vis
        if (!activity.customChannelEnabled) {
            activity.findViewById<TextView>(R.id.customChannelStatus).text =
                "Turn on \"Enable Home Channels\" to show channels on Home."
            refreshCustomSummary()
            return
        }
        val channels = activity.tvChannelRepository.listChannels()
        val status = activity.findViewById<TextView>(R.id.customChannelStatus)
        if (channels.isEmpty()) {
            list.addView(emptyHintView(
                "No channels detected.\n\nOpen a media app (Jellyfin, YouTube, Plex), then approve \"Add to home screen\"."
            ))
            status.text = "No channels"
            refreshCustomSummary()
            return
        }
        val visible = activity.customChannelIds.size
        status.text = "$visible of ${channels.size} visible · expand an app to configure"
        val byPkg = channels.groupBy { it.packageName }.toSortedMap()
        // Drop expanded key if package disappeared
        if (expandedCustomChannelPkg != null && expandedCustomChannelPkg !in byPkg) {
            expandedCustomChannelPkg = null
        }
        for ((pkg, chs) in byPkg) {
            list.addView(buildChannelAppAccordionRow(pkg, chs, list, status, channels.size))
        }
        refreshCustomSummary()
    }

    /**
     * Single-expand accordion row for one application under Customize Channel.
     * OK expands/collapses; only one package expanded at a time.
     */
    private fun buildChannelAppAccordionRow(
        pkg: String,
        chs: List<com.appsventory.digiapps.launchpad4.model.TvChannelInfo>,
        list: LinearLayout,
        status: TextView,
        totalChannels: Int
    ): View {
        val d = density
        val expanded = expandedCustomChannelPkg == pkg
        val uniqueChs = chs.distinctBy { it.displayName.trim().lowercase() }
        val enabledCount = uniqueChs.count { it.id in activity.customChannelIds }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * d).toInt() }
            tag = "accordion_$pkg"
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * d).toInt()
            )
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            tag = pkg
        }
        attachFocusScale(header, 1f)

        val icon = ImageView(activity).apply {
            val s = (32 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                setImageDrawable(activity.packageManager.getApplicationIcon(pkg))
            } catch (_: Exception) {
                setImageResource(R.drawable.ic_android_robot)
            }
        }
        header.addView(icon)

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = (12 * d).toInt() }
        }
        texts.addView(TextView(activity).apply {
            text = appLabel(pkg) ?: pkg
            setTextColor(colorWhite)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = "$enabledCount of ${uniqueChs.size} channels visible"
            setTextColor(colorWhite50)
            textSize = 11f
        })
        header.addView(texts)

        val chevron = TextView(activity).apply {
            text = if (expanded) "▾" else "▸"
            setTextColor(colorAccentSky)
            textSize = 16f
            setPadding((8 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        header.addView(chevron)

        fun toggleExpand() {
            expandedCustomChannelPkg = if (expandedCustomChannelPkg == pkg) null else pkg
            // Rebuild keeps single-expand invariant and restores focus to this package header
            populateCustomApps()
            // Restore focus to the header for this package
            customChannelList.post {
                for (i in 0 until customChannelList.childCount) {
                    val c = customChannelList.getChildAt(i)
                    if (c.tag == "accordion_$pkg") {
                        val h = (c as? ViewGroup)?.getChildAt(0)
                        h?.requestFocus()
                        break
                    }
                }
            }
        }
        header.setOnClickListener { toggleExpand() }
        header.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                toggleExpand(); true
            } else false
        }
        container.addView(header)

        if (expanded) {
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * d).toInt(), (4 * d).toInt(), 0, (4 * d).toInt())
            }
            for (ch in uniqueChs) {
                val enabled = ch.id in activity.customChannelIds
                body.addView(multiChannelRow(ch, enabled) { checked ->
                    if (checked) activity.customChannelIds.add(ch.id)
                    else activity.customChannelIds.remove(ch.id)
                    persistCustomChannel()
                    activity.loadCustomChannelRow()
                    refreshCustomSummary()
                    status.text = "${activity.customChannelIds.size} of $totalChannels visible · expand an app to configure"
                    // Update subtitle on this header without full rebuild
                    (texts.getChildAt(1) as? TextView)?.text =
                        "${uniqueChs.count { it.id in activity.customChannelIds }} of ${uniqueChs.size} channels visible"
                })
            }
            container.addView(body)
        }
        return container
    }

    private fun multiChannelRow(
        ch: com.appsventory.digiapps.launchpad4.model.TvChannelInfo,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val d = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (14 * d).toInt()
            val padV = (10 * d).toInt()
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            isClickable = true
            background = activity.getDrawable(R.drawable.bg_hover_transparent)
        }
        val icon = ImageView(activity).apply {
            val s = (36 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setImageDrawable(
                try { activity.packageManager.getApplicationIcon(ch.packageName) }
                catch (_: Exception) { null }
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val labelCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * d).toInt()
                marginEnd = (8 * d).toInt()
            }
        }
        labelCol.addView(TextView(activity).apply {
            text = ch.displayName
            setTextColor(colorWhite)
            textSize = 15f
        })
        labelCol.addView(TextView(activity).apply {
            text = if (enabled) "Visible on Home" else "Hidden"
            setTextColor(colorWhite50)
            textSize = 11f
            tag = "visible_label"
        })
        val toggle = android.widget.Switch(activity).apply {
            isChecked = enabled
            isFocusable = false
            isClickable = false
        }
        row.addView(icon)
        row.addView(labelCol)
        row.addView(toggle)
        fun flip() {
            toggle.isChecked = !toggle.isChecked
            onToggle(toggle.isChecked)
            (labelCol.getChildAt(1) as? TextView)?.text =
                if (toggle.isChecked) "Visible on Home" else "Hidden"
        }
        row.setOnClickListener { flip() }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { flip(); true } else false
        }
        return row
    }

    /** Single-select daftar channel milik satu app. */
    private fun populateChannelChoices(
        container: LinearLayout,
        channels: List<com.appsventory.digiapps.launchpad4.model.TvChannelInfo>,
        selectedId: Long?,
        onSelect: (Long) -> Unit
    ) {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = "Pilih satu channel (tidak bisa multi):"
            setTextColor(colorWhite50)
            textSize = 12f
            setPadding(0, 8, 0, 12)
        })
        for (ch in channels) {
            val selected = ch.id == selectedId
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val pad = (12 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                isFocusable = true
                isClickable = true
                background = activity.getDrawable(R.drawable.nav_focus)
                alpha = if (selected) 1f else 0.75f
            }
            val radio = TextView(activity).apply {
                text = if (selected) "●" else "○"
                setTextColor(if (selected) colorAccentSky else colorWhite50)
                textSize = 18f
                val w = (28 * activity.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val name = TextView(activity).apply {
                text = ch.displayName
                setTextColor(colorWhite)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(radio)
            row.addView(name)
            row.setOnClickListener { onSelect(ch.id) }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onSelect(ch.id); true
                } else false
            }
            container.addView(row)
        }
    }

    private fun appRow(pkg: String, channelCount: Int, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isClickable = true
            background = activity.getDrawable(R.drawable.nav_focus)
        }
        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * activity.resources.displayMetrics.density).toInt(),
                (36 * activity.resources.displayMetrics.density).toInt()
            )
            setImageDrawable(
                try { activity.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val labelCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * activity.resources.displayMetrics.density).toInt()
            }
        }
        labelCol.addView(TextView(activity).apply {
            text = appLabel(pkg) ?: pkg
            setTextColor(colorWhite)
            textSize = 15f
        })
        labelCol.addView(TextView(activity).apply {
            text = "$channelCount channel"
            setTextColor(colorWhite50)
            textSize = 11f
        })
        val chevron = TextView(activity).apply {
            text = "›"
            setTextColor(colorThemeDarkSecondaryText)
            textSize = 20f
        }
        row.addView(icon)
        row.addView(labelCol)
        row.addView(chevron)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun emptyHintView(msg: String): TextView = TextView(activity).apply {
        text = msg
        setTextColor(colorWhite50)
        textSize = 13f
        setPadding(0, 12, 0, 12)
        setLineSpacing(4f, 1f)
    }

    private fun persistCarouselChannel() {
        activity.prefs.edit()
            .putString(PrefsKeys.CAROUSEL_CHANNEL_ID, activity.carouselChannelId?.toString() ?: "")
            .putString(PrefsKeys.ENABLED_TV_CHANNEL_IDS, activity.enabledChannelIds.joinToString(","))
            .apply()
    }

    private fun persistCustomChannel() {
        activity.prefs.edit()
            .putString(PrefsKeys.CUSTOM_CHANNEL_IDS, activity.customChannelIds.joinToString(","))
            .putBoolean(PrefsKeys.CUSTOM_CHANNEL_ENABLED, activity.customChannelEnabled)
            .apply()
    }

}
