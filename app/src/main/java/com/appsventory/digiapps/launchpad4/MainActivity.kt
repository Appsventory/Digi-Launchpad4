package com.appsventory.digiapps.launchpad4

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.TransitionManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.net.HttpURLConnection
import android.widget.HorizontalScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.appsventory.digiapps.launchpad4.model.Recommendation
import com.appsventory.digiapps.launchpad4.data.PrefsKeys
import com.appsventory.digiapps.launchpad4.data.AppRepository
import com.appsventory.digiapps.launchpad4.data.TvChannelRepository
import com.appsventory.digiapps.launchpad4.ui.apps.AppCardBinder
import com.appsventory.digiapps.launchpad4.ui.apps.AppGridAdapter
import com.appsventory.digiapps.launchpad4.ui.settings.SettingsSidebarController
import com.appsventory.digiapps.launchpad4.ui.files.FileManagerController
import com.appsventory.digiapps.launchpad4.ui.TopBarController
import com.appsventory.digiapps.launchpad4.ui.home.HomeWeatherWidgetController
import com.appsventory.digiapps.launchpad4.ui.home.ChannelManageController
import com.appsventory.digiapps.launchpad4.ui.weather.WeatherController
import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val neededPermissions: Array<String> get() {
            val list = mutableListOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                "android.permission.READ_TV_LISTINGS"
            )
            if (Build.VERSION.SDK_INT < 33) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return list.toTypedArray()
        }

    private fun hasAllNeededPermissions(): Boolean {
        return neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun shouldShowPermissionOnboarding(): Boolean {
        // Muncul setelah reset (prefs clear) atau first install
        val alreadyPrompted = prefs.getBoolean(PrefsKeys.PERMISSIONS_PROMPTED, false)
        return !alreadyPrompted || !hasAllNeededPermissions()
    }

    private fun showPermissionOnboarding() {
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_permission_onboarding, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.permItemsContainer)
        val btnAllow = dialogView.findViewById<TextView>(R.id.btnAllow)
        val btnLater = dialogView.findViewById<TextView>(R.id.btnLater)

        // Build permission rows
        fun addItem(text: String) {
            val row = layoutInflater.inflate(R.layout.item_permission_row, container, false)
            row.findViewById<TextView>(R.id.permItemText).text = text
            container.addView(row)
        }

        if (missing.any { it.contains("LOCATION") }) {
            addItem("Location → Accurate weather")
        }
        if (missing.any { it.contains("STORAGE") }) {
            addItem("Storage → Browse local files in File Manager")
        }
        if (missing.any { it == "android.permission.READ_TV_LISTINGS" }) {
            addItem("TV Listings → Show channels & program guide")
        }

        val dialog = Dialog(this).apply {
            setContentView(dialogView)
            setCancelable(false)
            window?.setBackgroundDrawableResource(android.R.color.transparent)

            // Optional: rounded corners + size
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.42f).toInt().coerceAtMost(520),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnAllow.setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.PERMISSIONS_PROMPTED, true).apply()
            dialog.dismiss()
            requestPermissions(neededPermissions, REQ_PERMISSIONS)
        }

        btnLater.setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.PERMISSIONS_PROMPTED, true).apply()
            dialog.dismiss()
        }

        dialog.show()

        // TV / D-pad: focus langsung ke tombol Allow
        btnAllow.post { btnAllow.requestFocus() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "Semua izin diberikan", Toast.LENGTH_SHORT).show()
                // Optional: refresh weather / file manager jika sudah terbuka
            } else {
                Toast.makeText(
                    this,
                    "Beberapa izin ditolak. Fitur terkait terbatas.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Colors read once (lazily, after Activity theme is attached) instead of hardcoded hex literals.
    private val colorWhite by lazy { getColor(R.color.white) }
    private val colorWhite70 by lazy { getColor(R.color.white_70) }
    private val colorTransparent by lazy { getColor(R.color.transparent) }

    private val posterPlaceholderColors by lazy {
        intArrayOf(
            getColor(R.color.poster_placeholder_1),
            getColor(R.color.poster_placeholder_2),
            getColor(R.color.poster_placeholder_3),
            getColor(R.color.poster_placeholder_4),
        )
    }

    internal lateinit var appsContainer: LinearLayout
    private lateinit var recommendationContainer: LinearLayout
    private lateinit var recommendationScroll: View
    private lateinit var heroTextBlock: View
    private lateinit var heroSection: View
    private lateinit var heroSectionScrim: View
    private lateinit var heroContentBlock: View
    private lateinit var customChannelsSection: LinearLayout
    private lateinit var homeScroll: android.widget.ScrollView

    /** Grid fokus eksplisit: channelFocusGrid[row][col] = kartu. */
    private val channelFocusGrid = mutableListOf<MutableList<View>>()

    private lateinit var topPicksTitle: View
    private lateinit var homeScrollInner: View

    /** Top picks shelf lagi ditampilkan (true) atau disembunyikan total (false) —
     *  dipakai adjustHomeSpacer() biar heroSection ikut collapse pas shelf-nya
     *  disembunyikan, gak cuma soal bannerEnabled tapi juga saat contentnya kosong. */
    private var carouselShelfVisible: Boolean = true

    /** Item carousel yang sedang ditampilkan (untuk auto-scroll + hero bg). */
    private var currentCarouselItems: List<Recommendation> = emptyList()
    private var carouselIndex: Int = 0
    private var carouselFocused: Boolean = false

    /** Card Top Picks yang sedang "aktif" (mengikuti hero), di luar state focus asli. */
    private var activeCarouselCard: View? = null
    private val carouselAutoScrollIntervalMs = 4500L
    private val carouselAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (carouselFocused || currentCarouselItems.isEmpty()) return
            val count = recommendationContainer.childCount
            if (count <= 1) return
            carouselIndex = (carouselIndex + 1) % count
            showCarouselIndex(carouselIndex, fromAutoScroll = true)
            handler.postDelayed(this, carouselAutoScrollIntervalMs)
        }
    }
    private lateinit var appsGrid: GridView
    private lateinit var homeContent: View
    private lateinit var weatherContent: View
    private lateinit var filesContent: View
    private lateinit var contentHost: ViewGroup

    internal lateinit var homeButton: View
    internal lateinit var appsButton: View
    internal lateinit var filesButton: View
    internal lateinit var weatherButton: View

    internal lateinit var homeButtonLabel: TextView
    internal lateinit var appsButtonLabel: TextView
    internal lateinit var filesButtonLabel: TextView
    internal lateinit var weatherButtonLabel: TextView

    private lateinit var homeDot: View
    private lateinit var appsDot: View
    private lateinit var filesDot: View
    private lateinit var weatherDot: View

    internal lateinit var clockTime: TextView
    internal lateinit var clockAppName: TextView
    internal lateinit var networkIndicator: ImageView

    internal lateinit var mainContent: ViewGroup
    internal lateinit var sidebarScrim: View
    internal lateinit var sidebarPanel: View
    internal lateinit var bannerSwitch: Switch
    internal lateinit var providerList: LinearLayout
    internal lateinit var providerConfigStatus: TextView

    internal val appRepository by lazy {
        AppRepository(applicationContext)
    }

    internal val shellShortcutRepository by lazy {
        com.appsventory.digiapps.launchpad4.data.ShellShortcutRepository(this)
    }

    internal val tvChannelRepository by lazy {
        TvChannelRepository(applicationContext)
    }

    private val appCardBinder by lazy {
        AppCardBinder(appRepository, cardCornerPx)
    }

    /** Alias supaya kode lama yang masih menyebut ioExecutor tetap jalan. */
    private val ioExecutor
        get() = appRepository.ioExecutor

    internal lateinit var sidebarClose: View

    internal val fileManager by lazy {
        FileManagerController(
            this,
            focusLockRoot = mainContent,
            tabButtonId = R.id.filesButton,
            standalone = false,
        )
    }

    // Wallpaper & preferensi tambahan
    internal lateinit var wallpaperStatusText: TextView
    internal lateinit var pickWallpaperImageButton: View
    internal lateinit var gradientGroupsContainer: LinearLayout
    internal lateinit var resetWallpaperButton: View
    internal lateinit var clock24hSwitch: Switch
    internal lateinit var defaultTabButton: TextView
    internal lateinit var resetSettingsButton: View
    internal lateinit var appVersionText: TextView

    internal lateinit var prefs: SharedPreferences

    internal var installedApps: List<ApplicationInfo> = emptyList()

    private var favoritePackages: MutableList<String> = mutableListOf()

    internal var bannerEnabled = true

    internal val enabledChannelIds = mutableSetOf<Long>()

    internal var carouselChannelId: Long? = null

    internal var customChannelEnabled: Boolean = false

    /** Insertion-ordered so Home row order matches persisted order. */
    internal val customChannelIds = linkedSetOf<Long>()

    internal enum class Tab {
        HOME,
        APPS,
        FILES,
        WEATHER
    }

    private var activeTab = Tab.HOME

    private var isAnimatingTab = false

    internal var sidebarOpen = false

    /** Stable Shell shortcut id to restore focus to after closing the settings sidebar. */
    internal var pendingShellFocusId: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val cardCornerPx by lazy {
        4f * resources.displayMetrics.density
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    /** Refresh daftar app saat ada install/uninstall. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            appRepository.clearVisualCache()
            loadApplications()

            if (sidebarOpen) {
                settingsSidebar.populateChannelList()
            }
        }
    }

    /** Panel Pengaturan Launcher. */
    internal val settingsSidebar by lazy {
        SettingsSidebarController(this)
    }

    /** Top bar. */
    internal val topBar by lazy {
        TopBarController(this)
    }

    internal val weatherController by lazy {
        WeatherController(this)
    }

    /** Widget cuaca kecil di Home, tampil hanya saat Content Carousel mati. */
    internal val channelManage by lazy { ChannelManageController(this) }

    internal val homeWeatherWidgetController by lazy {
        HomeWeatherWidgetController(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(
            PrefsKeys.PREFS_FILE,
            MODE_PRIVATE
        )

        loadFavoritePackages()

        bannerEnabled = prefs.getBoolean(
            PrefsKeys.BANNER,
            true
        )

        enabledChannelIds.clear()

        carouselChannelId =
            prefs.getString(
                PrefsKeys.CAROUSEL_CHANNEL_ID,
                null
            )
                ?.trim()
                ?.toLongOrNull()

        if (carouselChannelId == null) {
            val legacy =
                prefs.getString(
                    PrefsKeys.ENABLED_TV_CHANNEL_IDS,
                    ""
                )
                    ?.split(",")
                    ?.mapNotNull {
                        it.trim().toLongOrNull()
                    }
                    .orEmpty()

            carouselChannelId = legacy.firstOrNull()
        }

        carouselChannelId?.let {
            enabledChannelIds.add(it)
        }

        customChannelEnabled =
            prefs.getBoolean(
                PrefsKeys.CUSTOM_CHANNEL_ENABLED,
                false
            )

        customChannelIds.clear()

        val multi =
            prefs.getString(
                PrefsKeys.CUSTOM_CHANNEL_IDS,
                null
            )
                ?.split(",")
                ?.mapNotNull {
                    it.trim().toLongOrNull()
                }
                .orEmpty()

        if (multi.isNotEmpty()) {
            customChannelIds.addAll(multi)
        } else {
            prefs.getString(
                PrefsKeys.CUSTOM_CHANNEL_ID,
                null
            )
                ?.trim()
                ?.toLongOrNull()
                ?.let {
                    customChannelIds.add(it)
                }
        }

        bindViews()

        adjustHomeSpacer()

        setupClock()

        loadRecommendations()

        loadCustomChannelRow()

        loadApplications()

        topBar.setupTopBarActions()

        setupFocusNavigation()

        settingsSidebar.setupSidebar()
        settingsSidebar.setupWallpaper()
        settingsSidebar.setupPreferences()

        setupFileManager()

        updateNetworkIndicator()

        applySelectedProviderToHome()

        // Terapkan urutan/visibility nav dulu, baru pilih start tab
        settingsSidebar.readNavTabOrder().let { applyNavTabOrder(it) }

        val defaultTab =
            settingsSidebar.readSavedDefaultTab()

        showTab(
            defaultTab,
            animate = false
        )

        activeTabButton().requestFocus()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    packageReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(
                    packageReceiver,
                    filter
                )
            }
        } catch (_: Exception) {
        }


        // Permission onboarding — muncul first install & setelah Reset Settings
        handler.post {
            if (shouldShowPermissionOnboarding()) {
                showPermissionOnboarding()
            }
        }
        handler.post(
            object : Runnable {
                override fun run() {
                    updateNetworkIndicator()

                    handler.postDelayed(
                        this,
                        5000
                    )
                }
            }
        )
    }

    private companion object {
        const val TYPE_MOVIE = 0
        const val TYPE_TV_SERIES = 1
        const val TYPE_TV_SEASON = 2
        const val TYPE_TV_EPISODE = 3
        const val TYPE_CLIP = 4
        const val TYPE_TRACK = 7
        const val TYPE_ALBUM = 8
        const val SHELF_FOCUS_SCALE = 1.15f
        const val SHELF_FOCUS_DURATION = 120L
        const val SHELF_FOCUS_ALPHA = 0.92f
        const val SHELF_FOCUS_Z = 100f
        const val REQ_PERMISSIONS = 9001
    }

    private fun formatChannelMeta(
        item: Recommendation
    ): String {

        val parts = mutableListOf<String>()

        when (item.programType) {

            TYPE_CLIP -> {
                item.author
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add(it)
                    }

                formatDuration(item.durationMillis)
                    ?.let {
                        parts.add(it)
                    }

                item.interactionCount
                    ?.takeIf { it > 0 }
                    ?.let {
                        parts.add(formatCount(it))
                    }
            }

            TYPE_TV_EPISODE -> {
                formatRelease(item.releaseDate)
                    ?.let {
                        parts.add(it)
                    }

                formatSeasonEpisode(
                    item.seasonNumber,
                    item.episodeNumber
                )?.let {
                    parts.add(it)
                }

                item.episodeTitle
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty() &&
                                !it.equals(
                                    item.title,
                                    ignoreCase = true
                                )
                    }
                    ?.let {
                        parts.add(it)
                    }

                formatDuration(item.durationMillis)
                    ?.let {
                        parts.add(it)
                    }
            }

            TYPE_TV_SEASON -> {
                formatRelease(item.releaseDate)
                    ?.let {
                        parts.add(it)
                    }

                item.seasonNumber
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add("S$it")
                    }
            }

            TYPE_MOVIE -> {
                item.genre
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add(
                            it.split(",")
                                .first()
                                .trim()
                        )
                    }

                formatDuration(item.durationMillis)
                    ?.let {
                        parts.add(it)
                    }

                formatRelease(item.releaseDate)
                    ?.let {
                        parts.add(it)
                    }
            }

            TYPE_TRACK,
            TYPE_ALBUM -> {

                formatRelease(item.releaseDate)
                    ?.let {
                        parts.add(it)
                    }

                item.genre
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add(it)
                    }

                item.author
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add(it)
                    }

                formatDuration(item.durationMillis)
                    ?.let {
                        parts.add(it)
                    }
            }

            else -> {

                item.author
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        parts.add(it)
                    }

                formatSeasonEpisode(
                    item.seasonNumber,
                    item.episodeNumber
                )?.let {
                    parts.add(it)
                }

                formatDuration(item.durationMillis)
                    ?.let {
                        parts.add(it)
                    }

                formatRelease(item.releaseDate)
                    ?.let {
                        parts.add(it)
                    }
            }
        }

        return parts.joinToString(" · ")
    }

    private fun formatDuration(
        millis: Long?
    ): String? {

        if (
            millis == null ||
            millis <= 0L ||
            millis == 100_000L
        ) {
            return null
        }

        val totalSec = millis / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60

        return when {
            h > 0 && m > 0 ->
                "${h}j ${m}m"

            h > 0 ->
                "${h}j"

            m > 0 && s > 0 ->
                "${m}m ${s}s"

            m > 0 ->
                "${m}m"

            s > 0 ->
                "${s}s"

            else ->
                null
        }
    }

    private fun formatRelease(
        raw: String?
    ): String? {

        val v =
            raw
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

        val year =
            Regex("""\b(19|20)\d{2}\b""")
                .find(v)
                ?.value

        return year ?: v.take(10)
    }

    private fun formatSeasonEpisode(
        season: String?,
        episode: String?
    ): String? {

        val s =
            season
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

        val e =
            episode
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

        return when {
            s != null && e != null ->
                "S${s.toString().padStart(2, '0')}" +
                        "E${e.toString().padStart(2, '0')}"

            s != null ->
                "S${s.toString().padStart(2, '0')}"

            e != null ->
                "E${e.toString().padStart(2, '0')}"

            else ->
                null
        }
    }

    private fun formatCount(n: Long): String {
        return when {
            n >= 1_000_000 ->
                String.format(
                    "%.1fM",
                    n / 1_000_000.0
                )

            n >= 1_000 ->
                String.format(
                    "%.1fK",
                    n / 1_000.0
                )

            else ->
                n.toString()
        }
    }

    private fun bindViews() {

        appsContainer =
            findViewById(R.id.appsContainer)

        recommendationContainer =
            findViewById(R.id.recommendationContainer)

        recommendationScroll =
            findViewById(R.id.recommendationScroll)

        heroTextBlock =
            findViewById(R.id.heroTextBlock)

        heroSection =
            findViewById(R.id.heroSection)

        heroSectionScrim =
            findViewById(R.id.heroSectionScrim)

        heroContentBlock =
            findViewById(R.id.heroContentBlock)

        customChannelsSection =
            findViewById(R.id.customChannelsSection)

        homeScroll =
            findViewById(R.id.homeScroll)

        topPicksTitle =
            findViewById(R.id.topPicksTitle)

        homeScrollInner =
            findViewById(R.id.homeScrollInner)

        appsGrid =
            findViewById(R.id.appsGrid)

        homeContent =
            findViewById(R.id.homeContent)

        weatherContent =
            findViewById(R.id.weatherContent)

        filesContent =
            findViewById(R.id.filesContent)

        contentHost =
            findViewById(R.id.contentHost)

        homeButton =
            findViewById(R.id.homeButton)

        appsButton =
            findViewById(R.id.appsButton)

        filesButton =
            findViewById(R.id.filesButton)

        weatherButton =
            findViewById(R.id.weatherButton)

        homeButtonLabel =
            findViewById(R.id.homeButtonLabel)

        appsButtonLabel =
            findViewById(R.id.appsButtonLabel)

        filesButtonLabel =
            findViewById(R.id.filesButtonLabel)

        weatherButtonLabel =
            findViewById(R.id.weatherButtonLabel)

        homeDot =
            findViewById(R.id.homeDot)

        appsDot =
            findViewById(R.id.appsDot)

        filesDot =
            findViewById(R.id.filesDot)

        weatherDot =
            findViewById(R.id.weatherDot)

        clockTime =
            findViewById(R.id.clockTime)

        clockAppName =
            findViewById(R.id.clockAppName)

        networkIndicator =
            findViewById(R.id.networkIndicator)

        mainContent =
            findViewById(R.id.mainContent)

        sidebarScrim =
            findViewById(R.id.sidebarScrim)

        sidebarPanel =
            findViewById(R.id.sidebarPanel)

        bannerSwitch =
            findViewById(R.id.bannerSwitch)

        providerList =
            findViewById(R.id.providerList)

        providerConfigStatus =
            findViewById(R.id.providerConfigStatus)

        sidebarClose =
            findViewById(R.id.sidebarClose)

        wallpaperStatusText =
            findViewById(R.id.wallpaperStatusText)

        pickWallpaperImageButton =
            findViewById(R.id.pickWallpaperImageButton)

        gradientGroupsContainer =
            findViewById(R.id.gradientGroupsContainer)

        resetWallpaperButton =
            findViewById(R.id.resetWallpaperButton)

        clock24hSwitch =
            findViewById(R.id.clock24hSwitch)

        defaultTabButton =
            findViewById(R.id.defaultTabButton)

        resetSettingsButton =
            findViewById(R.id.resetSettingsButton)

        appVersionText =
            findViewById(R.id.appVersionText)
    }

    /** Called from weather controller when widget visibility changes. */
    internal fun adjustHomeSpacerPublic() = adjustHomeSpacer()

    private fun adjustHomeSpacer() {
        // heroSection sizing:
        // - Full viewport when Content Carousel shelf is actually showing (hero art + top picks).
        // - WRAP_CONTENT otherwise so Favorite Apps (and optional Weather in the same slot)
        //   sit at the top with no empty reserved space.
        if (!::homeScroll.isInitialized || !::heroSection.isInitialized) return

        val weatherVisible =
            findViewById<View>(R.id.homeWeatherWidget)?.visibility == View.VISIBLE

        // Full-height hero when carousel shelf is showing OR weather occupies the content slot.
        // That keeps Favorite Apps in the lower hero band and Channel below the fold.
        // Collapse only when BOTH are off so Favorite Apps moves up with no empty region.
        val showFullHero = (bannerEnabled && carouselShelfVisible) || weatherVisible

        if (!showFullHero) {
            // Case C — both carousel and weather disabled
            collapseHeroSection()
            return
        }

        homeScroll.post {
            // Re-check: carousel may have been turned off before this post runs.
            val weatherStill =
                findViewById<View>(R.id.homeWeatherWidget)?.visibility == View.VISIBLE
            if (!((bannerEnabled && carouselShelfVisible) || weatherStill)) {
                collapseHeroSection()
                return@post
            }

            val viewport = homeScroll.height
            if (viewport <= 0) return@post

            val topBar = findViewById<View>(R.id.topBar)
            val topBarH = topBar?.height?.takeIf { it > 0 } ?: dp(56)
            val padTop = if (::mainContent.isInitialized) mainContent.paddingTop else dp(16)
            val pullUp = topBarH + padTop

            val lp = heroSection.layoutParams ?: return@post
            val needH = viewport + pullUp
            var changed = false

            if (lp.height != needH) {
                lp.height = needH
                changed = true
            }
            if (lp is android.view.ViewGroup.MarginLayoutParams) {
                if (lp.topMargin != -pullUp) {
                    lp.topMargin = -pullUp
                    changed = true
                }
            }
            if (changed) {
                heroSection.layoutParams = lp
            }

            // Weather sits at top of heroSection; compensate pull-up so it is not under the top bar
            val widget = findViewById<View>(R.id.homeWeatherWidget)
            if (widget != null && widget.visibility == View.VISIBLE) {
                val wlp = widget.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                val safeTop = pullUp + dp(24)
                if (wlp != null && wlp.topMargin != safeTop) {
                    wlp.topMargin = safeTop
                    // Keep content-width (XML wrap_content)
                    if (wlp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        wlp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    widget.layoutParams = wlp
                }
            }
        }
    }

    /** Collapse heroSection so Favorite Apps is not pinned under empty space. */
    private fun collapseHeroSection() {
        if (!::heroSection.isInitialized) return
        val lp = heroSection.layoutParams ?: return
        var changed = false
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            changed = true
        }
        if (lp is android.view.ViewGroup.MarginLayoutParams && lp.topMargin != 0) {
            lp.topMargin = 0
            changed = true
        }
        if (changed) {
            heroSection.layoutParams = lp
        }
    }



    // ─────────────────────────────────────────────
    // FOCUS SCALE + PIVOT
    // ─────────────────────────────────────────────

    /**
     * Update pivot semua child dalam satu horizontal shelf.
     *
     * Child pertama:
     *      pivotX = 0
     *
     * Child terakhir:
     *      pivotX = width
     *
     * Child tengah:
     *      pivotX = center
     *
     * pivotY selalu center.
     */
    private fun updateShelfPivots(container: ViewGroup) {

        // Izinkan card yang di-scale keluar dari batas parent
        container.clipChildren = false
        container.clipToPadding = false

        val total = container.childCount

        for (i in 0 until total) {

            val view = container.getChildAt(i)

            view.post {

                view.pivotX = when {
                    total <= 1 -> view.width / 2f
                    i == 0 -> 0f
                    i == total - 1 -> view.width.toFloat()
                    else -> view.width / 2f
                }

                view.pivotY = view.height / 2f
            }
        }
    }

    /**
     * Update pivot setelah layout selesai.
     */
    private fun updateShelfPivotsWhenReady(
        container: ViewGroup
    ) {

        container.post {

            updateShelfPivots(container)

            container.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {

                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int
                    ) {

                        updateShelfPivots(container)
                    }
                }
            )
        }
    }

    /**
     * Satu-satunya sumber animasi focus untuk SEMUA kartu shelf:
     * Top Picks, custom channel card, channel placeholder kosong,
     * dan favorite app. Semua pakai scale/duration/alpha/elevation
     * yang sama (konstanta SHELF_FOCUS_*) biar konsisten.
     *
     * [scaleTarget] dipisah dari [view] untuk kasus channel card:
     * scale diterapkan ke child (channelCard) sementara alpha,
     * elevation, dan hit-area tetap di root [view].
     *
     * bringChildToFront dipanggil tiap kali dapat fokus supaya
     * kartu yang sedang fokus SELALU digambar di atas tetangganya,
     * termasuk saat transisi fokus antar-kartu tumpang tindih
     * (elevation/translationZ sama-sama > 0 sesaat).
     */
    private fun attachShelfFocusAnimation(
        view: View,
        scaleTarget: View = view,
        onFocusGained: (() -> Unit)? = null,
        onFocusLost: (() -> Unit)? = null
    ) {

        view.setOnFocusChangeListener { v, hasFocus ->

            v.animate().cancel()
            scaleTarget.animate().cancel()

            if (hasFocus) {

                (v.parent as? ViewGroup)?.invalidate()

                v.translationZ = SHELF_FOCUS_Z
                v.elevation = SHELF_FOCUS_Z

                scaleTarget.animate()
                    .scaleX(SHELF_FOCUS_SCALE)
                    .scaleY(SHELF_FOCUS_SCALE)
                    .setDuration(SHELF_FOCUS_DURATION)
                    .start()

                v.animate()
                    .alpha(1f)
                    .setDuration(SHELF_FOCUS_DURATION)
                    .start()

                ensureCardVisible(v)

                onFocusGained?.invoke()

            } else {

                scaleTarget.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(
                        SHELF_FOCUS_DURATION
                    )
                    .start()

                v.animate()
                    .alpha(SHELF_FOCUS_ALPHA)
                    .setDuration(
                        SHELF_FOCUS_DURATION
                    )
                    .start()

                v.translationZ = 0f
                v.elevation = 0f

                onFocusLost?.invoke()
            }
        }
    }

    /**
     * Tandai card Top Picks yang sedang aktif (sinkron dengan hero carousel),
     * pakai style visual sama seperti state focus (scale + alpha + elevation).
     * Card lain yang sedang benar-benar di-focus user tidak diganggu.
     */
    private fun setActiveCarouselCard(view: View?) {

        val prev = activeCarouselCard
        if (prev === view) return

        if (prev != null && !prev.hasFocus()) {
            prev.animate().cancel()
            prev.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(SHELF_FOCUS_ALPHA)
                .setDuration(SHELF_FOCUS_DURATION)
                .start()
            prev.translationZ = 0f
            prev.elevation = 0f
        }

        activeCarouselCard = view

        if (view != null && !view.hasFocus()) {
            view.animate().cancel()
            view.translationZ = SHELF_FOCUS_Z
            view.elevation = SHELF_FOCUS_Z
            view.animate()
                .scaleX(SHELF_FOCUS_SCALE)
                .scaleY(SHELF_FOCUS_SCALE)
                .alpha(1f)
                .setDuration(SHELF_FOCUS_DURATION)
                .start()
        }
    }

    // ─────────────────────────────────────────────
    // PREFS
    // ─────────────────────────────────────────────

    private fun loadFavoritePackages() {

        val raw =
            prefs.getString(
                PrefsKeys.FAVORITES,
                ""
            ) ?: ""

        favoritePackages =
            if (raw.isBlank()) {
                mutableListOf()
            } else {
                raw
                    .split('\n')
                    .filter {
                        it.isNotBlank()
                    }
                    .toMutableList()
            }
    }

    private fun saveFavoritePackages() {

        prefs.edit()
            .putString(
                PrefsKeys.FAVORITES,
                favoritePackages.joinToString("\n")
            )
            .apply()
    }

    internal fun isFavorite(
        pkg: String
    ) =
        favoritePackages.contains(pkg)

    internal fun addFavorite(
        pkg: String
    ) {

        if (!favoritePackages.contains(pkg)) {

            favoritePackages.add(pkg)

            saveFavoritePackages()

            rebuildFavoriteRow()

            Toast.makeText(
                this,
                "Ditambahkan ke Favorite",
                Toast.LENGTH_SHORT
            ).show()

        } else {

            Toast.makeText(
                this,
                "Sudah ada di Favorite",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun removeFavorite(
        pkg: String
    ) {

        if (favoritePackages.remove(pkg)) {

            saveFavoritePackages()

            rebuildFavoriteRow()

            Toast.makeText(
                this,
                "Dihapus dari Favorite",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun moveFavorite(
        pkg: String,
        dir: Int
    ) {

        val idx =
            favoritePackages.indexOf(pkg)

        if (idx < 0) {
            return
        }

        val newIdx =
            (idx + dir)
                .coerceIn(
                    0,
                    favoritePackages.size - 1
                )

        if (newIdx == idx) {
            return
        }

        favoritePackages.removeAt(idx)

        favoritePackages.add(
            newIdx,
            pkg
        )

        saveFavoritePackages()

        rebuildFavoriteRow()

        appsContainer.post {
            appsContainer
                .getChildAt(newIdx)
                ?.requestFocus()
        }
    }

    // ─────────────────────────────────────────────
    // CLOCK
    // ─────────────────────────────────────────────

    private fun setupClock() {

        handler.post(
            object : Runnable {

                override fun run() {

                    val is24h =
                        prefs.getBoolean(
                            PrefsKeys.CLOCK_24H,
                            true
                        )

                    val pattern =
                        if (is24h) {
                            "HH:mm"
                        } else {
                            "hh:mm a"
                        }

                    clockTime.text =
                        SimpleDateFormat(
                            pattern,
                            Locale.getDefault()
                        ).format(Date())

                    handler.postDelayed(
                        this,
                        1000
                    )
                }
            }
        )
    }

    // ─────────────────────────────────────────────
    // NETWORK
    // ─────────────────────────────────────────────

    private fun updateNetworkIndicator() {

        val cm =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            cm.activeNetwork

        val caps =
            network?.let {
                cm.getNetworkCapabilities(it)
            }

        when {

            caps == null ->
                networkIndicator.setImageResource(
                    R.drawable.ic_wifi_off
                )

            caps.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ) ->
                networkIndicator.setImageResource(
                    R.drawable.ic_ethernet
                )

            caps.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) ->
                networkIndicator.setImageResource(
                    R.drawable.ic_wifi
                )

            caps.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) ->
                networkIndicator.setImageResource(
                    R.drawable.ic_wifi
                )

            else ->
                networkIndicator.setImageResource(
                    R.drawable.ic_wifi_off
                )
        }
    }

    // ─────────────────────────────────────────────
    // RECOMMENDATIONS
    // ─────────────────────────────────────────────

    private val recommendationsSnapshotFile: java.io.File by lazy {
        java.io.File(cacheDir, "recommendations_snapshot.json")
    }

    private fun recommendationToJson(
        r: Recommendation
    ): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("title", r.title)
            put("topPicksTitle", r.topPicksTitle)
            put("description", r.description)
            put("posterUri", r.posterUri ?: org.json.JSONObject.NULL)
            put("thumbnailUri", r.thumbnailUri ?: org.json.JSONObject.NULL)
            put("intentUri", r.intentUri ?: org.json.JSONObject.NULL)
            put("providerPackageName", r.providerPackageName ?: org.json.JSONObject.NULL)
            put("providerName", r.providerName ?: org.json.JSONObject.NULL)
            put("programType", r.programType ?: org.json.JSONObject.NULL)
            put("durationMillis", r.durationMillis ?: org.json.JSONObject.NULL)
            put("author", r.author ?: org.json.JSONObject.NULL)
            put("interactionCount", r.interactionCount ?: org.json.JSONObject.NULL)
            put("releaseDate", r.releaseDate ?: org.json.JSONObject.NULL)
            put("genre", r.genre ?: org.json.JSONObject.NULL)
            put("seasonNumber", r.seasonNumber ?: org.json.JSONObject.NULL)
            put("episodeNumber", r.episodeNumber ?: org.json.JSONObject.NULL)
            put("episodeTitle", r.episodeTitle ?: org.json.JSONObject.NULL)
        }

    private fun jsonToRecommendation(
        o: org.json.JSONObject
    ): Recommendation = Recommendation(
        title = o.optString("title"),
        topPicksTitle = o.optString("topPicksTitle", ""),
        description = o.optString("description", ""),
        posterUri = if (o.isNull("posterUri")) null else o.optString("posterUri"),
        thumbnailUri = if (o.isNull("thumbnailUri")) null else o.optString("thumbnailUri"),
        intentUri = if (o.isNull("intentUri")) null else o.optString("intentUri"),
        providerPackageName = if (o.isNull("providerPackageName")) null else o.optString("providerPackageName"),
        providerName = if (o.isNull("providerName")) null else o.optString("providerName"),
        programType = if (o.isNull("programType")) null else o.optInt("programType"),
        durationMillis = if (o.isNull("durationMillis")) null else o.optLong("durationMillis"),
        author = if (o.isNull("author")) null else o.optString("author"),
        interactionCount = if (o.isNull("interactionCount")) null else o.optLong("interactionCount"),
        releaseDate = if (o.isNull("releaseDate")) null else o.optString("releaseDate"),
        genre = if (o.isNull("genre")) null else o.optString("genre"),
        seasonNumber = if (o.isNull("seasonNumber")) null else o.optString("seasonNumber"),
        episodeNumber = if (o.isNull("episodeNumber")) null else o.optString("episodeNumber"),
        episodeTitle = if (o.isNull("episodeTitle")) null else o.optString("episodeTitle"),
    )

    private fun saveRecommendationsSnapshot(
        items: List<Recommendation>
    ) {
        try {
            val arr = org.json.JSONArray()
            items.forEach { arr.put(recommendationToJson(it)) }
            recommendationsSnapshotFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadRecommendationsSnapshot(): List<Recommendation> {
        return try {
            if (!recommendationsSnapshotFile.exists()) return emptyList()
            val arr = org.json.JSONArray(
                recommendationsSnapshotFile.readText()
            )
            (0 until arr.length()).map {
                jsonToRecommendation(arr.getJSONObject(it))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun loadRecommendations() {

        recommendationContainer.removeAllViews()
        activeCarouselCard = null

        applyCarouselVisibility()

        if (!bannerEnabled) {

            setHeroVisible(false)

            hideCarouselShelf()

            restoreDefaultHero()
            stopCarouselAutoScroll()
            currentCarouselItems = emptyList()

            homeWeatherWidgetController.updateForCarouselState(carouselEnabled = false)

            wireHomeRowFocus()
            adjustHomeSpacer()

            return
        }

        homeWeatherWidgetController.updateForCarouselState(carouselEnabled = true)

        setHeroVisible(true)

        ioExecutor.execute {

            // Tier 1: snapshot lokal (JSON di cacheDir). Baca+render dulu
            // sebelum nyentuh TvProvider sama sekali, jadi user langsung
            // liat kartu+poster (poster sendiri juga punya disk cache,
            // lihat loadPosterIntoCard) tanpa nunggu query ContentResolver.
            val cached =
                loadRecommendationsSnapshot()

            if (cached.isNotEmpty()) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    renderRecommendationItems(cached)
                }
            }

            // Tier 2: query beneran ke TvProvider. Kartu cuma di-rebuild
            // kalau datanya emang beda drpd snapshot -> gak ada flicker
            // percuma tiap refresh periodik kalau isinya sama aja.
            // Perkecualian: fresh kosong tetep harus di-render (walau cached
            // juga kosong / fresh == cached) supaya empty-state (sembunyikan
            // shelf + overlay hitam) kepasang — kalau enggak, UI nyangkut di
            // kondisi default applyCarouselVisibility() (shelf+overlay tetap
            // kelihatan padahal gak ada konten).
            val fresh =
                tvChannelRepository
                    .fetchRecommendations(
                        enabledChannelIds
                    )

            if (fresh != cached || fresh.isEmpty()) {

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    renderRecommendationItems(fresh)
                }

                saveRecommendationsSnapshot(fresh)
            }
        }
    }

    private fun renderRecommendationItems(
        rawItems: List<Recommendation>
    ) {

        val items =
            rawItems.distinctBy {
                it.title to (it.landscapeArtUri ?: it.intentUri)
            }

        recommendationContainer.removeAllViews()
        activeCarouselCard = null

        if (items.isEmpty()) {

            val msg =
                if (carouselChannelId == null) {

                    "Belum ada channel dipilih.\n" +
                            "Aktifkan Content Carousel di Pengaturan, lalu pilih app & channel."

                } else {

                    "Channel kosong atau belum terdeteksi.\n" +
                            "Buka app media lalu approve \"Add to home screen\"."
                }

            settingsSidebar.updateContentStatus(
                msg.replace(
                    "\n",
                    " "
                )
            )

            setHeroVisible(false)

            hideCarouselShelf()

            restoreDefaultHero()
            stopCarouselAutoScroll()
            currentCarouselItems = emptyList()

        } else {

            val limitedItems =
                items.take(4)

            setHeroVisible(true)

            settingsSidebar.updateContentStatus(
                "${limitedItems.size} item dimuat dari channel aktif"
            )

            for (item in limitedItems) {
                addRecommendationCard(item)
            }

            currentCarouselItems = limitedItems
            carouselIndex = 0

            // Daftarin heroArtUri SEMUA item carousel ke activePosterUris
            // dari sekarang — jangan nunggu tiap item kepilih jadi hero
            // (auto-scroll baru sampe sana beberapa detik lagi). Kalau
            // enggak, prunePosterDiskCache() yang jalan 8 detik pasca refresh
            // bakal hapus disk cache item yang belum sempat "keliatan",
            // padahal bentar lagi bakal ditampilin -> backdrop hilang lagi
            // pas gilirannya tiba, gak konsisten tiap siklus refresh.
            for (item in limitedItems) {
                item.heroArtUri?.let { activePosterUris.add(it) }
            }

            limitedItems
                .firstOrNull()
                ?.let {
                    applyHeroFromRecommendation(it)
                }

            setActiveCarouselCard(
                recommendationContainer.getChildAt(0)
            )

            for (
            i in 0 until recommendationContainer.childCount
            ) {

                val card =
                    recommendationContainer
                        .getChildAt(i)

                val rec =
                    limitedItems
                        .getOrNull(i)
                        ?: continue

                loadPosterIntoCard(
                    card,
                    rec
                )
            }

            // ─────────────────────────
            // UPDATE PIVOT TOP PICKS
            // ─────────────────────────

            updateShelfPivotsWhenReady(
                recommendationContainer
            )

            startCarouselAutoScroll()
        }

        wireHomeRowFocus()
        adjustHomeSpacer()
    }

    // ─────────────────────────────────────────────
    // CUSTOM CHANNELS
    // ─────────────────────────────────────────────

    /**
     * Channel (baris di bawah Favorite app) belum diaktifkan atau belum
     * ada channel dipilih -> tampilkan 1 kartu promo (ikon + judul +
     * subjudul) gaya kartu rekomendasi Android TV, ngarahin ke Pengaturan >
     * Edit Channel — bukan cuma disembunyikan diam-diam.
     */
    private fun showCustomChannelPromoCard() {

        customChannelsSection.visibility =
            View.VISIBLE

        val density =
            resources.displayMetrics.density

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    (18 * density).toInt(),
                    (16 * density).toInt(),
                    (18 * density).toInt(),
                    (16 * density).toInt()
                )

                background =
                    getDrawable(
                        R.drawable.bg_channel_promo_card
                    )

                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                minimumWidth =
                    (360 * density).toInt()
            }

        card.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_settings_home)
                setColorFilter(colorWhite70)
                layoutParams =
                    LinearLayout.LayoutParams(
                        (22 * density).toInt(),
                        (22 * density).toInt()
                    )
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Tambah Channel"
                setTextColor(colorWhite)
                textSize = 18f
                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
                setPadding(
                    0,
                    (14 * density).toInt(),
                    0,
                    (4 * density).toInt()
                )
            }
        )

        card.addView(
            TextView(this).apply {
                text =
                    "Aktifkan Channel di Pengaturan untuk menampilkan channel favorit kamu di Home."
                setTextColor(colorWhite70)
                textSize = 12.5f
                layoutParams =
                    LinearLayout.LayoutParams(
                        (300 * density).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
            }
        )

        card.setOnClickListener {
            settingsSidebar.openChannelSettings()
        }

        customChannelsSection.addView(card)
    }

    internal fun loadCustomChannelRow() {

        customChannelsSection.removeAllViews()

        channelFocusGrid.clear()

        if (
            !customChannelEnabled ||
            customChannelIds.isEmpty()
        ) {

            showCustomChannelPromoCard()

            wireHomeRowFocus()

            return
        }

        customChannelsSection.visibility =
            View.VISIBLE

        val ids =
            customChannelIds.toSet()

        ioExecutor.execute {

            val all =
                tvChannelRepository.listChannels()
            val orderPref =
                prefs.getString(
                    PrefsKeys.CUSTOM_CHANNEL_ORDER,
                    null
                )
                    ?.split(",")
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    .orEmpty()
            val ordered = buildList {
                val used = mutableSetOf<Long>()
                for (id in orderPref) {
                    if (id !in ids) continue
                    all.find { it.id == id }?.let {
                        add(it)
                        used += id
                    }
                }
                for (id in ids) {
                    if (id in used) continue
                    all.find { it.id == id }?.let { add(it) }
                }
            }

            val rows =
                ordered.map { ch ->

                    ch to
                            tvChannelRepository
                                .fetchRecommendations(
                                    setOf(ch.id),
                                    limitPerChannel = 10
                                )
                }

            runOnUiThread {

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@runOnUiThread
                }

                customChannelsSection.removeAllViews()

                if (rows.isEmpty()) {

                    customChannelsSection.visibility =
                        View.GONE

                    wireHomeRowFocus()

                    return@runOnUiThread
                }

                val density =
                    resources.displayMetrics.density

                rows.forEachIndexed { rowIndex, pair ->
                    val ch = pair.first
                    val items = pair.second.distinctBy {
                        it.title to (it.landscapeArtUri ?: it.intentUri)
                    }

                    customChannelsSection.addView(
                        TextView(this).apply {
                            text = ch.displayName
                            setTextColor(colorWhite)
                            textSize = 15f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
                            isFocusable = false
                        }
                    )

                    val scroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        clipChildren = false
                        clipToPadding = false
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        isFocusable = false
                        setPadding(
                            0,
                            (8 * density).toInt(),
                            (16 * density).toInt(),
                            (8 * density).toInt()
                        )
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.TOP
                        clipChildren = false
                        clipToPadding = false
                        isFocusable = false
                        tag = rowIndex
                        setPadding(0, 0, 0, (8 * density).toInt())
                    }

                    while (channelFocusGrid.size <= rowIndex) {
                        channelFocusGrid.add(mutableListOf())
                    }

                    if (items.isEmpty()) {
                        val placeholder = buildEmptyChannelPlaceholder(rowIndex, ch.id)
                        row.addView(placeholder)
                        channelFocusGrid[rowIndex] = mutableListOf(placeholder)
                    } else {
                        val gridRow = mutableListOf<View>()
                        for ((col, item) in items.withIndex()) {
                            val card = buildChannelCard(item, rowIndex, col, ch.id)
                            row.addView(card)
                            gridRow.add(card)
                        }
                        channelFocusGrid[rowIndex] = gridRow
                    }

                    scroll.addView(row)
                    customChannelsSection.addView(scroll)
                    updateShelfPivotsWhenReady(row)
                }

                wireHomeRowFocus()
            }
        }
    }

    private data class ChannelPos(
        val row: Int,
        val col: Int,
        val channelId: Long = -1L
    )

    private fun buildEmptyChannelPlaceholder(
        rowIndex: Int,
        channelId: Long = -1L
    ): View {

        val density =
            resources.displayMetrics.density

        val frame =
            android.widget.FrameLayout(this).apply {

                id =
                    View.generateViewId()

                isFocusable =
                    true

                isClickable =
                    true

                isFocusableInTouchMode =
                    true

                alpha =
                    0.92f

                tag =
                    ChannelPos(
                        rowIndex,
                        0,
                        channelId
                    )

                background =
                    getDrawable(
                        R.drawable.bg_card
                    )

                layoutParams =
                    LinearLayout.LayoutParams(
                        (228 * density).toInt(),
                        (128 * density).toInt()
                    ).apply {

                        marginEnd =
                            (14 * density).toInt()
                    }
            }

        val label =
            TextView(this).apply {

                text =
                    "Belum ada program\ndi channel ini"

                setTextColor(
                    colorWhite70
                )

                textSize =
                    13f

                gravity =
                    android.view.Gravity.CENTER

                isFocusable =
                    false

                isClickable =
                    false

                setPadding(
                    (12 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (8 * density).toInt()
                )

                layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
            }

        frame.addView(label)

        appCardBinder.applyRoundClip(frame)

        attachShelfFocusAnimation(frame)

        frame.setOnKeyListener {
                v,
                keyCode,
                event ->

            handleChannelCardKey(
                v,
                keyCode,
                event
            )
        }

        return frame
    }

    private fun buildChannelCard(
        item: Recommendation,
        rowIndex: Int,
        colIndex: Int,
        channelId: Long = -1L
    ): View {

        val view =
            layoutInflater.inflate(
                R.layout.item_channel_card,
                customChannelsSection,
                false
            )

        // ─────────────────────────────────────────
        // TITLE
        // ─────────────────────────────────────────

        val title =
            view.findViewById<TextView>(
                R.id.channelTitle
            )

        title.text =
            item.title

        title.isFocusable =
            false

        title.isClickable =
            false


        // ─────────────────────────────────────────
        // META
        // ─────────────────────────────────────────

        val meta =
            view.findViewById<TextView>(
                R.id.channelMeta
            )

        val metaText =
            formatChannelMeta(item)

        if (metaText.isEmpty()) {

            meta.visibility =
                View.GONE

        } else {

            meta.visibility =
                View.VISIBLE

            meta.text =
                metaText
        }

        meta.isFocusable =
            false

        meta.isClickable =
            false


        // ─────────────────────────────────────────
        // PROFILE / DESCRIPTION
        // ─────────────────────────────────────────

        val profile =
            view.findViewById<TextView>(
                R.id.channelMetaProfile
            )

        profile.text =
            item.description

        profile.isFocusable =
            false

        profile.isClickable =
            false


        // ─────────────────────────────────────────
        // POSTER
        // ─────────────────────────────────────────

        val poster =
            view.findViewById<ImageView>(
                R.id.channelPoster
            )

        poster.isFocusable =
            false

        poster.isClickable =
            false

        if (item.posterRes != null) {

            poster.setImageResource(
                item.posterRes
            )

        } else {

            poster.setImageDrawable(
                null
            )

            poster.setBackgroundColor(
                placeholderColor(
                    item.title
                )
            )
        }


        // ─────────────────────────────────────────
        // CARD
        // ─────────────────────────────────────────

        val card =
            view.findViewById<View>(
                R.id.channelCard
            )

        appCardBinder.applyRoundClip(
            card ?: view
        )


        // ─────────────────────────────────────────
        // ROOT VIEW
        // ─────────────────────────────────────────

        view.id =
            View.generateViewId()

        view.isFocusable =
            true

        view.isClickable =
            true

        view.isFocusableInTouchMode =
            true

        view.alpha =
            SHELF_FOCUS_ALPHA

        view.tag =
            ChannelPos(
                rowIndex,
                colIndex,
                channelId
            )


        // ─────────────────────────────────────────
        // TEXT INITIAL STATE
        // ─────────────────────────────────────────
        //
        // Tidak fokus:
        // text tidak terlihat.
        //
        // Alpha digunakan agar ukuran/layout
        // card tetap stabil ketika fokus berpindah.
        //

        title.visibility = View.GONE
        meta.visibility = View.GONE
        profile.visibility = View.GONE


        // ─────────────────────────────────────────
        // FOCUS ANIMATION
        // ─────────────────────────────────────────

        attachShelfFocusAnimation(
            view = view,

            onFocusGained = {

                (view.parent as? ViewGroup)?.invalidate()

                val row = view.parent as? LinearLayout
                if (row != null) {
                    val density = resources.displayMetrics.density
                    TransitionManager.beginDelayedTransition(customChannelsSection)
                    row.setPadding(0, 0, 0, (48 * density).toInt())
                }

                title.visibility = View.VISIBLE
                if (metaText.isNotEmpty()) meta.visibility = View.VISIBLE
                if (!item.description.isNullOrBlank()) profile.visibility = View.VISIBLE
            },

            onFocusLost = {

                title.visibility = View.GONE
                meta.visibility = View.GONE
                profile.visibility = View.GONE

                view.post {
                    val row = view.parent as? LinearLayout
                    if (row != null) {
                        val anyFocused = (0 until row.childCount).any { row.getChildAt(it).hasFocus() }
                        if (!anyFocused) {
                            val density = resources.displayMetrics.density
                            TransitionManager.beginDelayedTransition(customChannelsSection)
                            row.setPadding(0, 0, 0, (8 * density).toInt())
                        }
                    }
                }
            }
        )


        // ─────────────────────────────────────────
        // KEY NAVIGATION
        // ─────────────────────────────────────────

        view.setOnKeyListener {
                v,
                keyCode,
                event ->

            handleChannelCardKey(
                v,
                keyCode,
                event
            )
        }


        // ─────────────────────────────────────────
        // CLICK
        // ─────────────────────────────────────────

        view.setOnClickListener {
            if (channelManage.isReorderMode()) return@setOnClickListener

            val intent =
                tvChannelRepository.intentFor(
                    item
                )

            if (intent != null) {

                try {

                    startActivity(
                        intent
                    )

                } catch (_: Exception) {

                    Toast.makeText(
                        this,
                        "Tidak bisa membuka konten",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }


        // ─────────────────────────────────────────
        // POSTER LOADING
        // ─────────────────────────────────────────

        loadPosterIntoCard(
            view,
            item
        )

        return view
    }

    // ─────────────────────────────────────────────
    // POSTER
    // ─────────────────────────────────────────────

    private val posterCache =
        object :
            android.util.LruCache<
                    String,
                    android.graphics.Bitmap
                    >(
                (Runtime.getRuntime().maxMemory() / 8).toInt()
            ) {
            override fun sizeOf(
                key: String,
                value: android.graphics.Bitmap
            ): Int = value.byteCount
        }

    // LIFO: item yg baru masuk viewport didahulukan drpd item lama yg udah
    // discroll lewat. execute() biasa itu FIFO, jadi kita bypass execute()
    // dan push langsung ke depan deque; worker threads (prestart) narik dari
    // depan juga (take() == takeFirst() di LinkedBlockingDeque).
    private val imageQueue =
        java.util.concurrent.LinkedBlockingDeque<Runnable>()

    private val imageExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            4,
            4,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            imageQueue
        ).apply {
            prestartAllCoreThreads()
        }

    private fun runImageJob(job: () -> Unit) {
        imageQueue.addFirst(Runnable { job() })
    }

    private val posterDiskDir: java.io.File by lazy {
        java.io.File(cacheDir, "posters").apply { mkdirs() }
    }

    // Semua posterUri yg lagi ditampilin (home + subscriptions row). Direset
    // tiap siklus refresh (lihat recommendationsRefreshRunnable), lalu dipakai
    // buat prune: file disk yg key-nya gak ada di set ini artinya kontennya
    // udah gak nongol lagi di layar manapun -> aman dihapus.
    private val activePosterUris =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun posterCacheKeyOf(uriStr: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(uriStr.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun prunePosterDiskCache() {
        try {
            val keepKeys =
                synchronized(activePosterUris) {
                    activePosterUris.map { posterCacheKeyOf(it) }.toSet()
                }
            // Kosong berarti belum ada refresh siklus penuh yg selesai —
            // jangan hapus apa-apa drpd salah nebak semuanya orphan.
            if (keepKeys.isEmpty()) return
            posterDiskDir.listFiles()?.forEach { file ->
                val key = file.nameWithoutExtension
                if (key !in keepKeys) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun posterDiskFile(uriStr: String): java.io.File {
        return java.io.File(posterDiskDir, "${posterCacheKeyOf(uriStr)}.jpg")
    }

    private fun loadPosterFromDisk(
        uriStr: String
    ): android.graphics.Bitmap? {
        val f = posterDiskFile(uriStr)
        if (!f.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun savePosterToDisk(
        uriStr: String,
        bmp: android.graphics.Bitmap
    ) {
        try {
            posterDiskFile(uriStr).outputStream().use {
                bmp.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    85,
                    it
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun loadPosterIntoCard(
        card: View,
        rec: Recommendation
    ) {

        val uriStr =
            rec.landscapeArtUri
                ?: return

        activePosterUris.add(uriStr)

        val poster =
            card.findViewById<ImageView>(
                R.id.channelPoster
            )
                ?: card.findViewById<ImageView>(
                    R.id.recommendationPoster
                )
                ?: return

        poster.tag =
            uriStr

        posterCache
            .get(uriStr)
            ?.let {

                poster.setImageBitmap(it)

                poster.setBackgroundColor(
                    colorTransparent
                )

                return
            }

        val targetW =
            (
                    200 *
                            resources.displayMetrics.density
                    )
                .toInt()
                .coerceIn(
                    120,
                    320
                )

        runImageJob {

            // View udah discroll lewat sebelum job sempat jalan -> skip.
            // Ga usah buang waktu decode buat poster yg udah gak keliatan.
            if (poster.tag != uriStr) return@runImageJob

            try {

                // Tier 2: disk cache. Ini yg bikin poster tetep instant
                // walau app baru dibuka ulang (proses baru = memory cache
                // kosong) — gak perlu nunggu ContentResolver/network lagi.
                loadPosterFromDisk(uriStr)
                    ?.let { diskBmp ->

                        posterCache.put(uriStr, diskBmp)

                        poster.post {
                            if (poster.tag == uriStr) {
                                poster.setImageBitmap(diskBmp)
                                poster.setBackgroundColor(colorTransparent)
                            }
                        }

                        return@runImageJob
                    }

                // Tier 3: decode asli (loadThumbnail dari provider / network)
                val bmp =
                    decodePosterSampled(
                        uriStr,
                        targetW
                    )
                        ?: return@runImageJob

                posterCache.put(
                    uriStr,
                    bmp
                )

                savePosterToDisk(uriStr, bmp)

                poster.post {

                    if (
                        poster.tag ==
                        uriStr
                    ) {

                        poster.setImageBitmap(
                            bmp
                        )

                        poster.setBackgroundColor(
                            colorTransparent
                        )
                    }
                }

            } catch (_: Exception) {
            }
        }
    }

    private fun decodePosterSampled(
        uriStr: String,
        targetWidth: Int,
        targetHeight: Int = targetWidth
    ): android.graphics.Bitmap? {

        // Fast path: content:// (poster dari TvProvider app lain, ini yg
        // paling sering dipakai TvContractCompat.PreviewPrograms). Minta
        // thumbnail langsung ke provider (API 29+) -> provider yg decode +
        // downsample sisi dia, kita gak decode full-res lokal. Ini penyebab
        // utama "lama muncul": kode lama selalu decode 2x round-trip
        // (bounds + full) dari stream mentah provider, walau providernya
        // udah punya cara cepat buat generate thumbnail sendiri.
        if (
            uriStr.startsWith("content://") &&
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.Q
        ) {
            try {
                val thumb =
                    contentResolver.loadThumbnail(
                        Uri.parse(uriStr),
                        android.util.Size(
                            targetWidth,
                            targetHeight
                        ),
                        null
                    )
                return thumb
            } catch (_: Exception) {
                // Provider gak dukung loadThumbnail -> lanjut fallback decode manual di bawah
            }
        }

        fun openStream():
                java.io.InputStream? {

            return if (
                uriStr.startsWith(
                    "content://"
                )
            ) {

                contentResolver.openInputStream(
                    Uri.parse(uriStr)
                )

            } else {

                val conn =
                    (
                            URL(uriStr)
                                .openConnection()
                                    as HttpURLConnection
                            ).apply {

                            connectTimeout =
                                2500

                            readTimeout =
                                2500

                            instanceFollowRedirects =
                                true

                            useCaches =
                                true
                        }

                try {
                    conn.inputStream
                } catch (_: Exception) {
                    null
                }
            }
        }

        val bounds =
            android.graphics.BitmapFactory.Options()
                .apply {
                    inJustDecodeBounds =
                        true
                }

        openStream()?.use {
            android.graphics.BitmapFactory
                .decodeStream(
                    it,
                    null,
                    bounds
                )
        }

        if (
            bounds.outWidth <= 0
        ) {
            return null
        }

        var sample =
            1

        while (
            bounds.outWidth / sample >
            targetWidth * 2
        ) {
            sample *= 2
        }

        val opts =
            android.graphics.BitmapFactory.Options()
                .apply {

                    inSampleSize =
                        sample.coerceAtLeast(1)

                    inPreferredConfig =
                        android.graphics.Bitmap.Config.RGB_565
                }

        return openStream()?.use {
            android.graphics.BitmapFactory
                .decodeStream(
                    it,
                    null,
                    opts
                )
        }
    }

    private fun applyCarouselVisibility() {

        carouselShelfVisible = true

        topPicksTitle.visibility =
            View.VISIBLE

        recommendationScroll.visibility =
            View.VISIBLE

        heroSectionScrim.visibility =
            View.VISIBLE

        homeScrollInner.setBackgroundColor(
            getColor(R.color.black)
        )

        customChannelsSection.setBackgroundColor(
            getColor(R.color.black)
        )
    }

    /**
     * Dipanggil kalau Top Picks gak punya apapun buat ditampilkan — baik
     * karena Content Carousel dimatikan manual, MAUPUN karena nyala tapi
     * belum ada channel/app/data yang kebaca. Dua-duanya sama: sembunyikan
     * shelf-nya total (bukan tampilkan kartu "Buka Pengaturan"), dan
     * transparankan backdrop hitamnya biar wallpaper kelihatan, supaya
     * Home cuma nampilin "Favorite app" kalau memang belum ada data.
     */
    private fun hideCarouselShelf() {

        carouselShelfVisible = false

        recommendationContainer.removeAllViews()
        activeCarouselCard = null

        topPicksTitle.visibility =
            View.GONE

        recommendationScroll.visibility =
            View.GONE

        heroSectionScrim.visibility =
            View.GONE

        homeScrollInner.background = null

        customChannelsSection.background = null
    }

    private fun addRecommendationCard(
        item: Recommendation
    ) {

        val view =
            layoutInflater.inflate(
                R.layout.item_recommendation,
                recommendationContainer,
                false
            )

        val poster =
            view.findViewById<ImageView>(
                R.id.recommendationPoster
            )

        findViewById<TextView>(
            R.id.topPicksTitle
        ).text =
            item.topPicksTitle

        if (item.posterRes != null) {

            poster.setImageResource(
                item.posterRes
            )

        } else {

            poster.setImageDrawable(null)

            poster.setBackgroundColor(
                placeholderColor(
                    item.title
                )
            )
        }

        appCardBinder.applyRoundClip(
            view
        )

        view.id =
            View.generateViewId()

        setupRecommendationFocus(
            view,
            item
        )

        view.setOnClickListener {

            val intent =
                tvChannelRepository.intentFor(
                    item
                )

            if (intent != null) {

                try {

                    startActivity(intent)

                } catch (_: Exception) {

                    Toast.makeText(
                        this,
                        "Tidak bisa membuka \"${item.title}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        (
                view.layoutParams
                        as? ViewGroup.MarginLayoutParams
                )?.marginEnd =
            (
                    14 *
                            resources.displayMetrics.density
                    ).toInt()

        recommendationContainer.addView(
            view
        )
    }

    private fun placeholderColor(
        seed: String
    ): Int {

        return posterPlaceholderColors[
            kotlin.math.abs(
                seed.hashCode()
            ) %
                    posterPlaceholderColors.size
        ]
    }

    /**
     * Focus animation Top Picks.
     *
     * Pivot tidak diatur di sini.
     * Semua pivot Top Picks diatur oleh
     * updateShelfPivots(recommendationContainer).
     */
    private fun setupRecommendationFocus(
        view: View,
        item: Recommendation
    ) {

        attachShelfFocusAnimation(
            view,
            onFocusGained = {
                carouselFocused = true
                stopCarouselAutoScroll()
                setActiveCarouselCard(null)
                val idx = recommendationContainer.indexOfChild(view)
                if (idx >= 0) {
                    carouselIndex = idx
                }
                if (bannerEnabled) {
                    applyHeroFromRecommendation(item)
                }
            },
            onFocusLost = {
                // Hanya resume kalau tidak ada card lain di carousel yang fokus
                view.post {
                    val anyFocused = (0 until recommendationContainer.childCount).any {
                        recommendationContainer.getChildAt(it).hasFocus()
                    }
                    if (!anyFocused) {
                        carouselFocused = false
                        startCarouselAutoScroll()
                    }
                }
            }
        )

        view.setOnKeyListener {
                v,
                keyCode,
                event ->

            if (
                event.action !=
                KeyEvent.ACTION_DOWN
            ) {
                return@setOnKeyListener false
            }

            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_DOWN -> {

                    if (
                        appsContainer.childCount >
                        0
                    ) {

                        appsContainer
                            .getChildAt(0)
                            ?.requestFocus()

                    } else {

                        firstCustomChannelCard()
                            ?.requestFocus()
                    }

                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {

                    activeTabButton()
                        .requestFocus()

                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    val idx =
                        recommendationContainer
                            .indexOfChild(v)

                    if (
                        idx >= 0 &&
                        idx <
                        recommendationContainer.childCount - 1
                    ) {

                        recommendationContainer
                            .getChildAt(
                                idx + 1
                            )
                            ?.requestFocus()
                    }

                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {

                    val idx =
                        recommendationContainer
                            .indexOfChild(v)

                    if (idx > 0) {

                        recommendationContainer
                            .getChildAt(
                                idx - 1
                            )
                            ?.requestFocus()
                    }

                    true
                }

                else ->
                    false
            }
        }
    }

    // ─────────────────────────────────────────────
    // PROVIDER / HERO
    // ─────────────────────────────────────────────

    private fun loadProviderLogo(
        providerName: String?,
        imageView: ImageView
    ) {

        if (providerName.isNullOrBlank()) {

            imageView.setImageDrawable(null)

            return
        }

        val providerKey =
            providerName
                .lowercase()
                .replace(
                    Regex("[^a-z0-9]+"),
                    "_"
                )
                .trim('_')

        var drawableRes =
            resources.getIdentifier(
                "ic_provider_$providerKey",
                "drawable",
                packageName
            )

        if (drawableRes == 0) {

            val parts =
                providerKey.split("_")

            for (part in parts) {

                if (part.isBlank()) {
                    continue
                }

                val fallbackRes =
                    resources.getIdentifier(
                        "ic_provider_$part",
                        "drawable",
                        packageName
                    )

                if (fallbackRes != 0) {

                    drawableRes =
                        fallbackRes

                    break
                }
            }
        }

        if (drawableRes != 0) {

            imageView.setImageResource(
                drawableRes
            )

        } else {

            imageView.setImageDrawable(null)
        }
    }

    private fun applyHeroFromRecommendation(
        item: Recommendation
    ) {

        val providerLogo =
            findViewById<ImageView>(
                R.id.provider
            )

        loadProviderLogo(
            item.providerName,
            providerLogo
        )

        findViewById<TextView>(
            R.id.title
        ).text =
            item.title

        findViewById<TextView>(
            R.id.description
        ).text =
            item.description

        findViewById<TextView>(
            R.id.meta
        ).text =
            formatChannelMeta(item)

        // Background di heroSection (tinggi = 1 viewport, atas→bawah full).
        // setBackground → tidak mengubah measurement; konten tetap gravity bottom.
        applyHeroSectionBackground(item)
    }

    private fun applyHeroSectionBackground(item: Recommendation) {
        if (!::heroSection.isInitialized) return

        fun applyBmp(bmp: android.graphics.Bitmap) {
            heroSection.background = centerCropBitmapDrawable(bmp)
        }

        if (item.posterRes != null) {
            val dr = resources.getDrawable(item.posterRes, theme)?.mutate()
            if (dr is android.graphics.drawable.BitmapDrawable && dr.bitmap != null) {
                applyBmp(dr.bitmap)
            } else {
                heroSection.setBackgroundResource(item.posterRes)
            }
            return
        }

        val uriStr = item.heroArtUri
        if (uriStr.isNullOrBlank()) return

        activePosterUris.add(uriStr)
        heroSection.setTag(R.id.heroSection, uriStr)

        posterCache.get(uriStr)?.let {
            applyBmp(it)
            return
        }

        runImageJob {
            // Hero ketutup gradient scrim + teks, gak butuh tajam sampe 1920px.
            // Request landscape (bukan persegi) sesuai proporsi hero asli ->
            // total pixel jauh lebih kecil = fetch+decode dari server Jellyfin
            // jauh lebih cepat, khusus buat first-load (belum ke-disk-cache).
            val heroTargetW =
                (resources.displayMetrics.widthPixels).coerceAtMost(1280)
            val heroTargetH =
                (heroTargetW * 9 / 16).coerceAtLeast(1)

            val bmp =
                loadPosterFromDisk(uriStr)
                    ?: decodePosterSampled(
                        uriStr,
                        heroTargetW,
                        heroTargetH
                    )
                    ?: return@runImageJob

            posterCache.put(uriStr, bmp)
            savePosterToDisk(uriStr, bmp)

            heroSection.post {
                if (isFinishing || isDestroyed) return@post
                if (heroSection.getTag(R.id.heroSection) != uriStr) return@post
                applyBmp(bmp)
            }
        }
    }

    private fun centerCropBitmapDrawable(
        bmp: android.graphics.Bitmap
    ): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or
                        android.graphics.Paint.FILTER_BITMAP_FLAG
            ).apply {
                shader = android.graphics.BitmapShader(
                    bmp,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                super.onBoundsChange(bounds)
                if (bounds.isEmpty || bmp.width <= 0 || bmp.height <= 0) return
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val vw = bounds.width().toFloat()
                val vh = bounds.height().toFloat()
                val scale = maxOf(vw / bw, vh / bh)
                val dx = (vw - bw * scale) * 0.5f
                val dy = (vh - bh * scale) * 0.5f
                val matrix = android.graphics.Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(bounds.left + dx, bounds.top + dy)
                (paint.shader as android.graphics.BitmapShader).setLocalMatrix(matrix)
            }

            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawRect(bounds, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            override fun getOpacity(): Int =
                android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun clearHeroContentBackground() {
        if (::heroSection.isInitialized) {
            heroSection.background = null
        }
        if (::heroContentBlock.isInitialized) {
            heroContentBlock.background = null
        }
    }

    private fun startCarouselAutoScroll() {
        stopCarouselAutoScroll()
        if (carouselFocused) return
        if (recommendationContainer.childCount <= 1) return
        if (!bannerEnabled) return
        handler.postDelayed(carouselAutoScrollRunnable, carouselAutoScrollIntervalMs)
    }

    private fun stopCarouselAutoScroll() {
        handler.removeCallbacks(carouselAutoScrollRunnable)
    }

    /** Pindah ke index carousel (auto-scroll atau manual). */
    private fun showCarouselIndex(index: Int, fromAutoScroll: Boolean) {
        val count = recommendationContainer.childCount
        if (count == 0) return
        val safe = ((index % count) + count) % count
        carouselIndex = safe

        val card = recommendationContainer.getChildAt(safe) ?: return
        val item = currentCarouselItems.getOrNull(safe)

        // Scroll horizontal ke card
        val hsv = recommendationScroll as? HorizontalScrollView
        hsv?.smoothScrollTo(
            (card.left - dp(8)).coerceAtLeast(0),
            0
        )

        if (item != null && bannerEnabled) {
            applyHeroFromRecommendation(item)
        }

        setActiveCarouselCard(card)

        // Auto-scroll: jangan requestFocus (itu akan matikan auto-scroll).
        // User focus: tetap biarkan focus di card yang dipilih.
        if (!fromAutoScroll && !carouselFocused) {
            // no-op
        }
    }

    internal fun restoreDefaultHero() {

        val providerLogo =
            findViewById<ImageView>(
                R.id.provider
            )

        providerLogo.setImageDrawable(
            null
        )

        findViewById<TextView>(
            R.id.title
        ).text = ""

        findViewById<TextView>(
            R.id.description
        ).text = ""

        if (::heroSection.isInitialized) {
            heroSection.background = null
        }

        clearHeroContentBackground()

        settingsSidebar.applyCurrentWallpaper()
    }

    private fun setHeroVisible(
        visible: Boolean
    ) {

        heroTextBlock.visibility =
            if (visible) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    // ─────────────────────────────────────────────
    // APPS
    // ─────────────────────────────────────────────

    private var pinnedPackages: MutableSet<String> = mutableSetOf()
    private var noBannerApps: List<ApplicationInfo> = emptyList()
    private var otherAppsDialog: android.app.Dialog? = null

    private fun loadPinnedPackages() {
        pinnedPackages = prefs.getString(PrefsKeys.PINNED_APPS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableSet()
            ?: mutableSetOf()
    }

    private fun savePinnedPackages() {
        prefs.edit()
            .putString(PrefsKeys.PINNED_APPS, pinnedPackages.joinToString(","))
            .apply()
    }

    private fun isPinned(pkg: String) = pinnedPackages.contains(pkg)

    private fun pinApp(pkg: String) {
        if (pinnedPackages.add(pkg)) {
            savePinnedPackages()
            loadApplications()
            refreshOtherAppsModal()   // ← update modal yang lagi terbuka
            Toast.makeText(this, "Pinned to Apps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unpinApp(pkg: String) {
        if (pinnedPackages.remove(pkg)) {
            savePinnedPackages()
            loadApplications()
            refreshOtherAppsModal()
            Toast.makeText(this, "Unpinned from Apps", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun loadApplications() {
        appRepository.loadApplications { resolved ->
            if (isFinishing || isDestroyed) return@loadApplications

            installedApps = resolved

            val (noBanner, withBanner) = resolved.partition { app ->
                val (banner, _, _) = appRepository.visualsFor(app)
                banner == null
            }

            // Yang tampil di grid: semua punya banner + yang di-pin
            val pinnedNoBanner = noBanner.filter { isPinned(it.packageName) }
            val gridApps = withBanner + pinnedNoBanner

            // Sisanya (tanpa banner & belum pin) → modal "Other apps"
            noBannerApps = noBanner.filter { !isPinned(it.packageName) }

            rebuildFavoriteRow()

            appsGrid.adapter = AppGridAdapter(
                context = this,
                apps = gridApps,
                otherAppsCount = noBannerApps.size,
                binder = appCardBinder,
                onClick = { app ->
                    appRepository.launchIntentFor(app.packageName)?.let { startActivity(it) }
                },
                onLongClick = { app -> showAppsContextMenu(app) },
                onOtherAppsClick = { showOtherAppsModal() }
            )
        }
    }
    private fun showOtherAppsModal() {
        // tutup yang lama kalau masih terbuka
        otherAppsDialog?.dismiss()

        if (noBannerApps.isEmpty()) {
            otherAppsDialog = null
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_other_apps, null)
        val list = dialogView.findViewById<LinearLayout>(R.id.otherAppsList)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnCloseOtherApps)

        populateOtherAppsList(list)

        val dialog = android.app.Dialog(this).apply {
            setContentView(dialogView)
            setCancelable(true)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.45f).toInt().coerceAtMost(560),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnDismissListener { otherAppsDialog = null }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        otherAppsDialog = dialog
        dialog.show()
        btnClose.post { btnClose.requestFocus() }
    }

    private fun populateOtherAppsList(list: LinearLayout) {
        list.removeAllViews()
        for (app in noBannerApps) {
            val row = layoutInflater.inflate(R.layout.item_other_app_row, list, false)
            val (_, icon, label) = appRepository.visualsFor(app)

            row.findViewById<ImageView>(R.id.otherAppIcon).setImageDrawable(icon)
            row.findViewById<TextView>(R.id.otherAppName).text = label

            row.setOnClickListener {
                appRepository.launchIntentFor(app.packageName)?.let { startActivity(it) }
            }
            row.setOnLongClickListener {
                showAppsContextMenu(app)
                true
            }
            list.addView(row)
        }
    }

    /** Panggil setelah pin/unpin supaya row di modal langsung hilang/muncul. */
    private fun refreshOtherAppsModal() {
        val dialog = otherAppsDialog ?: return
        if (!dialog.isShowing) return

        // noBannerApps sudah di-update oleh loadApplications
        // tapi loadApplications async — jadi rebuild dari pinnedPackages
        val stillOther = installedApps.filter { app ->
            val (banner, _, _) = appRepository.visualsFor(app)
            banner == null && !isPinned(app.packageName)
        }
        noBannerApps = stillOther

        if (stillOther.isEmpty()) {
            dialog.dismiss()
            return
        }

        val list = dialog.findViewById<LinearLayout>(R.id.otherAppsList) ?: return
        populateOtherAppsList(list)
    }

    private fun rebuildFavoriteRow() {

        appsContainer.removeAllViews()

        for (
        pkg in favoritePackages
        ) {

            val app =
                installedApps.find {
                    it.packageName == pkg
                }
                    ?: appRepository
                        .applicationInfoOrNull(pkg)
                    ?: continue

            addFavoriteAppView(app)
        }

        // Selalu tampilkan kartu "+" di akhir agar user bisa menambah favorite kapan saja.
        addFavoriteAddView()

        updateShelfPivotsWhenReady(
            appsContainer
        )

        wireHomeRowFocus()
    }

    /**
     * Kartu "+" di akhir baris Favorite. Pakai layout & binder kartu app
     * yang sama (item_app.xml + AppCardBinder) supaya desain identik.
     * Klik membuka sidebar Select app (reuse panel settings).
     */
    private fun addFavoriteAddView() {

        val view =
            layoutInflater.inflate(
                R.layout.item_app,
                appsContainer,
                false
            )

        val lp =
            LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(
                    R.dimen.apps_favorite_card_width
                ),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        lp.marginEnd =
            resources.getDimensionPixelSize(
                R.dimen.apps_grid_gap_h
            )

        view.layoutParams =
            lp

        view.findViewById<ImageView>(R.id.appIcon).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(colorWhite70)

            // perbesar icon (default di layout 48dp)
            val size = (64 * resources.displayMetrics.density).toInt()  // 64dp, ubah sesuai selera
            layoutParams = layoutParams.apply {
                width = size
                height = size
            }
        }

        val label =
            getString(R.string.favorite_add_app)

        view.findViewById<TextView>(
            R.id.appNameBelow
        )?.text = label

        view.setOnClickListener {
            settingsSidebar.openSelectFavorite()
        }
        view.tag = "add_favorite"

        // Sesuai desain lain di Home: kalau fokus di kartu paling kanan, tekan kanan
        // lagi harus berhenti di situ — bukan "jatuh" ke row channel di bawahnya.
        // Row lain trap ini kebetulan lewat geometri (gak ada apa-apa lagi di kanan),
        // tapi kartu "+" ini kebetulan collide sama channel row, jadi di-trap eksplisit.
        ensureUniqueFocusId(view)
        view.nextFocusRightId = view.id

        appCardBinder.applyRoundClip(view)
        appCardBinder.setupScaleFocusAnimation(view)

        appsContainer.addView(view)
    }

    private fun addFavoriteAppView(
        application: ApplicationInfo
    ) {

        val view =
            layoutInflater.inflate(
                R.layout.item_app,
                appsContainer,
                false
            )

        val lp =
            LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(
                    R.dimen.apps_favorite_card_width
                ),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        lp.marginEnd =
            resources.getDimensionPixelSize(
                R.dimen.apps_grid_gap_h
            )

        view.layoutParams =
            lp

        appCardBinder.bind(
            view,
            application
        ) {

            appRepository
                .launchIntentFor(
                    application.packageName
                )
                ?.let {
                    startActivity(it)
                }
        }

        appCardBinder.applyRoundClip(
            view
        )

        appCardBinder.applyRoundClip(view)
        appCardBinder.setupScaleFocusAnimation(view)
        view.setOnLongClickListener { showFavoriteContextMenu(application); true }

        view.setOnKeyListener {
                v,
                keyCode,
                event ->

            if (
                event.action !=
                KeyEvent.ACTION_DOWN
            ) {
                return@setOnKeyListener false
            }

            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_UP -> {

                    focusFilmNearApp(v)

                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {

                    val target =
                        firstCustomChannelCard()

                    target?.requestFocus()

                    target != null
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    val idx =
                        appsContainer.indexOfChild(v)

                    if (
                        idx >= 0 &&
                        idx <
                        appsContainer.childCount - 1
                    ) {

                        appsContainer
                            .getChildAt(
                                idx + 1
                            )
                            ?.requestFocus()
                    }

                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {

                    val idx =
                        appsContainer.indexOfChild(v)

                    if (idx > 0) {

                        appsContainer
                            .getChildAt(
                                idx - 1
                            )
                            ?.requestFocus()
                    }

                    true
                }

                else ->
                    false
            }
        }

        appsContainer.addView(view)
    }

    // ─────────────────────────────────────────────
    // CONTEXT MENU
    // ─────────────────────────────────────────────

    private fun showAppsContextMenu(app: ApplicationInfo) {
        val label = app.loadLabel(packageManager).toString()
        val (banner, _, _) = appRepository.visualsFor(app)
        val hasBanner = banner != null

        val items = mutableListOf<String>()

        // Favorite
        items += if (isFavorite(app.packageName)) "Remove from Favorite" else "Add to Favorite"

        // Pin hanya relevan untuk app TANPA banner
        if (!hasBanner) {
            items += if (isPinned(app.packageName)) "Unpin from Apps" else "Pin to Apps"
        }

        items += "App info"
        items += "Uninstall"

        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(items.toTypedArray()) { _, which ->
                var idx = 0
                // 0 = favorite
                if (which == idx++) {
                    if (isFavorite(app.packageName)) removeFavorite(app.packageName)
                    else addFavorite(app.packageName)
                    return@setItems
                }
                // pin (hanya kalau !hasBanner)
                if (!hasBanner) {
                    if (which == idx++) {
                        if (isPinned(app.packageName)) unpinApp(app.packageName)
                        else pinApp(app.packageName)
                        return@setItems
                    }
                }
                // app info
                if (which == idx++) {
                    openAppInfo(app.packageName)
                    return@setItems
                }
                // uninstall
                if (which == idx) {
                    uninstallApp(app.packageName)
                }
            }
            .show()
    }

    private fun showFavoriteContextMenu(
        app: ApplicationInfo
    ) {

        val label =
            app.loadLabel(
                packageManager
            ).toString()

        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(
                arrayOf(
                    "Move kiri",
                    "Move kanan",
                    "Info aplikasi",
                    "Hapus dari Favorite"
                )
            ) {
                    _,
                    which ->

                when (which) {

                    0 ->
                        moveFavorite(
                            app.packageName,
                            -1
                        )

                    1 ->
                        moveFavorite(
                            app.packageName,
                            +1
                        )

                    2 ->
                        openAppInfo(
                            app.packageName
                        )

                    3 ->
                        removeFavorite(
                            app.packageName
                        )
                }
            }
            .show()
    }

    internal fun openAppInfo(
        pkg: String = packageName
    ) {

        try {

            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {

                    data =
                        Uri.parse(
                            "package:$pkg"
                        )
                }
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            Toast.makeText(
                this,
                "Info aplikasi tidak ditemukan",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun uninstallApp(
        pkg: String
    ) {

        try {

            startActivity(
                Intent(
                    Intent.ACTION_DELETE
                ).apply {

                    data =
                        Uri.parse(
                            "package:$pkg"
                        )
                }
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            Toast.makeText(
                this,
                "Tidak bisa menghapus aplikasi",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ─────────────────────────────────────────────
    // PROVIDER
    // ─────────────────────────────────────────────

    internal fun applySelectedProviderToHome() {

        findViewById<ImageView>(
            R.id.provider
        ).setImageDrawable(null)
    }

    // ─────────────────────────────────────────────
    // FILE MANAGER
    // ─────────────────────────────────────────────

    private fun setupFileManager() {
        fileManager.setup()
    }

    // ─────────────────────────────────────────────
    // FOCUS NAVIGATION
    // ─────────────────────────────────────────────

    private fun setupFocusNavigation() {

        homeButton.nextFocusDownId =
            R.id.recommendationContainer

        appsButton.nextFocusDownId =
            R.id.appsGrid

        filesButton.nextFocusDownId =
            R.id.filesMenuButton

        weatherButton.nextFocusDownId =
            R.id.weatherHeroCard

        listOf(
            homeButton,
            appsButton,
            filesButton,
            weatherButton
        ).forEach { btn ->

            btn.nextFocusUpId =
                btn.id
        }

        findViewById<View>(
            R.id.clockTime
        )?.let {

            it.nextFocusUpId =
                it.id
        }

        findViewById<View>(
            R.id.clockAppName
        )?.let {

            it.nextFocusUpId =
                it.id
        }

        findViewById<View>(
            R.id.profileButton
        )?.let {

            it.nextFocusUpId =
                it.id
        }

        wireHomeRowFocus()
    }

    /**
     * Terapkan urutan + visibility tab di navPill.
     * Search button tetap di kiri; tab diurut ulang; yang tidak ada di list = GONE.
     */
    internal fun applyNavTabOrder(order: List<Tab>) {
        val pill = findViewById<android.widget.LinearLayout>(R.id.navPill) ?: return
        val search = findViewById<View>(R.id.searchButton)
        val buttonFor: (Tab) -> View = { tab ->
            when (tab) {
                Tab.HOME -> homeButton
                Tab.APPS -> appsButton
                Tab.FILES -> filesButton
                Tab.WEATHER -> weatherButton
            }
        }
        val allTabs = listOf(Tab.HOME, Tab.APPS, Tab.FILES, Tab.WEATHER)
        // Sembunyikan dulu semua
        for (t in allTabs) {
            buttonFor(t).visibility = View.GONE
        }
        // Tampilkan sesuai urutan & re-parent ke posisi setelah search
        var insertAt = 1 // index 0 = search
        for (t in order) {
            val btn = buttonFor(t)
            btn.visibility = View.VISIBLE
            pill.removeView(btn)
            pill.addView(btn, insertAt.coerceAtMost(pill.childCount))
            insertAt++
        }
        // Perbaiki nextFocus kiri-kanan berantai
        val visibleBtns = order.map(buttonFor)
        if (search != null && visibleBtns.isNotEmpty()) {
            search.nextFocusRightId = visibleBtns.first().id
            visibleBtns.first().nextFocusLeftId = search.id
            for (i in visibleBtns.indices) {
                if (i > 0) visibleBtns[i].nextFocusLeftId = visibleBtns[i - 1].id
                if (i < visibleBtns.lastIndex) visibleBtns[i].nextFocusRightId = visibleBtns[i + 1].id
            }
            val homeIcon = findViewById<View>(R.id.homeIcon)
            if (homeIcon != null) {
                visibleBtns.last().nextFocusRightId = homeIcon.id
                homeIcon.nextFocusLeftId = visibleBtns.last().id
            }
        }
        // Kalau active tab disembunyikan, pindah ke tab pertama yang tampil
        if (activeTab !in order && order.isNotEmpty()) {
            showTab(order.first(), animate = false)
        }
        // Keep action-pill focus chain in sync after nav changes
        refreshShellShortcuts()
    }

    /**
     * Rebuild the entire Shell action pill from the authoritative shortcut config.
     * Legacy hardcoded homeIcon / settingsButton / networkIndicator are hidden;
     * all enabled shortcuts (built-in + user) are rendered dynamically in order.
     */
    internal fun refreshShellShortcuts() {
        val pill = findViewById<android.widget.LinearLayout>(R.id.actionPill) ?: return
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val pad = (8 * density).toInt()
        val focusTextDark = getColor(R.color.focus_text_dark)
        val white = getColor(R.color.white)

        // Hide legacy static built-in views — they are now managed via ShellShortcut config
        findViewById<View>(R.id.homeIcon)?.visibility = View.GONE
        findViewById<View>(R.id.settingsButton)?.visibility = View.GONE
        findViewById<View>(R.id.networkIndicator)?.visibility = View.GONE

        // Remove previous dynamic shortcut views
        val toRemove = mutableListOf<View>()
        for (i in 0 until pill.childCount) {
            val child = pill.getChildAt(i)
            val t = child.tag
            if (t is String && t.startsWith("shell_sc_")) toRemove.add(child)
        }
        toRemove.forEach { pill.removeView(it) }

        val enabled = shellShortcutRepository.loadEnabled()
        val dynamicViews = mutableListOf<View>()
        for (sc in enabled) {
            val iv = ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                background = getDrawable(R.drawable.nav_focus)
                tag = "shell_sc_${sc.id}"
                contentDescription = sc.displayName
                if (id == View.NO_ID) id = View.generateViewId()
                val icon = shellShortcutRepository.iconFor(sc)
                if (icon != null) setImageDrawable(icon)
                else setImageResource(R.drawable.ic_android_robot)
                if (sc.iconRef.startsWith("res:")) {
                    setColorFilter(white)
                    setOnFocusChangeListener { _, hasFocus ->
                        setColorFilter(if (hasFocus) focusTextDark else white)
                    }
                } else {
                    clearColorFilter()
                    setOnFocusChangeListener { v, hasFocus ->
                        v.alpha = if (hasFocus) 1f else 0.92f
                    }
                }
                val launch = {
                    pendingShellFocusId = sc.id
                    shellShortcutRepository.launch(sc, openSidebar = {
                        settingsSidebar.openSidebar()
                    })
                }
                setOnClickListener { launch() }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                    ) {
                        launch(); true
                    } else false
                }
            }
            pill.addView(iv)
            dynamicViews.add(iv)
        }

        // Focus chain: last *visible* nav control → first shortcut → … → clock
        val clock = clockTime
        val searchBtn = findViewById<View>(R.id.searchButton)
        val navCandidates = mutableListOf<View>()
        // Respect configured order, then append Search if visible (Search is not in Tab enum order alone)
        val order = try { settingsSidebar.readNavTabOrder() } catch (_: Exception) { emptyList() }
        // Build left-to-right: search first if visible, then ordered tabs
        if (searchBtn != null && searchBtn.visibility == View.VISIBLE) {
            navCandidates += searchBtn
        }
        for (tab in order) {
            val btn = when (tab) {
                Tab.HOME -> homeButton
                Tab.APPS -> appsButton
                Tab.FILES -> filesButton
                Tab.WEATHER -> weatherButton
            }
            if (btn.visibility == View.VISIBLE && btn !in navCandidates) navCandidates += btn
        }
        // Fallback if order empty
        if (navCandidates.isEmpty()) {
            listOfNotNull(searchBtn, homeButton, appsButton, filesButton, weatherButton)
                .filter { it.visibility == View.VISIBLE }
                .let { navCandidates += it }
        }
        val lastNav = navCandidates.lastOrNull()

        // Chain nav items among themselves (search ↔ tabs)
        for (i in navCandidates.indices) {
            if (i > 0) navCandidates[i].nextFocusLeftId = navCandidates[i - 1].id
            if (i < navCandidates.lastIndex) navCandidates[i].nextFocusRightId = navCandidates[i + 1].id
        }
        // Profile → first nav
        findViewById<View>(R.id.profileButton)?.let { profile ->
            val first = navCandidates.firstOrNull()
            if (first != null) {
                profile.nextFocusRightId = first.id
                first.nextFocusLeftId = profile.id
            }
        }

        if (dynamicViews.isEmpty()) {
            lastNav?.nextFocusRightId = R.id.clockTime
            clock.nextFocusLeftId = lastNav?.id ?: View.NO_ID
        } else {
            lastNav?.nextFocusRightId = dynamicViews.first().id
            dynamicViews.first().nextFocusLeftId = lastNav?.id ?: View.NO_ID
            for (i in dynamicViews.indices) {
                if (dynamicViews[i].id == View.NO_ID) dynamicViews[i].id = View.generateViewId()
                if (i > 0) dynamicViews[i].nextFocusLeftId = dynamicViews[i - 1].id
                if (i < dynamicViews.lastIndex) dynamicViews[i].nextFocusRightId = dynamicViews[i + 1].id
            }
            dynamicViews.last().nextFocusRightId = R.id.clockTime
            clock.nextFocusLeftId = dynamicViews.last().id
        }
    }

    private fun activeTabButton(): View =
        when (activeTab) {

            Tab.HOME ->
                homeButton

            Tab.APPS ->
                appsButton

            Tab.FILES ->
                filesButton

            Tab.WEATHER ->
                weatherButton
        }

    // ─────────────────────────────────────────────
    // CHANNEL FOCUS
    // ─────────────────────────────────────────────

    private fun channelCardRows():
            List<ViewGroup> {

        val rows =
            mutableListOf<ViewGroup>()

        for (
        i in 0 until
                customChannelsSection.childCount
        ) {

            val child =
                customChannelsSection
                    .getChildAt(i)

            if (
                child is HorizontalScrollView
            ) {

                val row =
                    child.getChildAt(
                        0
                    ) as? ViewGroup
                        ?: continue

                var hasFocusable =
                    false

                for (
                j in 0 until
                        row.childCount
                ) {

                    if (
                        row.getChildAt(j)
                            .isFocusable
                    ) {

                        hasFocusable =
                            true

                        break
                    }
                }

                if (hasFocusable) {
                    rows.add(row)
                }
            }
        }

        return rows
    }

    private fun firstCustomChannelCard():
            View? {

        for (
        line in channelFocusGrid
        ) {

            val card =
                line.firstOrNull()
                    ?: continue

            return card
        }

        return channelCardRows()
            .firstOrNull()
            ?.getChildAt(0)
            ?.takeIf {
                it.isFocusable
            }
    }

    private fun ensureCardVisible(
        card: View
    ) {

        card.post {

            val density =
                resources.displayMetrics.density

            val topInset =
                (48 * density).toInt()

            val bottomInset =
                (37 * density).toInt()

            val sideInset =
                (16 * density).toInt()

            card.requestRectangleOnScreen(
                android.graphics.Rect(
                    -sideInset,
                    -topInset,
                    card.width + sideInset,
                    card.height + bottomInset
                ),
                false
            )
        }
    }

    private fun focusChannelCell(
        row: Int,
        col: Int,
        dir: Int = 1,
        preferFirst: Boolean = false
    ): Boolean {

        if (
            channelFocusGrid.isEmpty()
        ) {
            return false
        }

        var r =
            row

        while (
            r in channelFocusGrid.indices
        ) {

            val line =
                channelFocusGrid[r]

            if (line.isNotEmpty()) {

                val targetCol =
                    if (preferFirst) {
                        0
                    } else {
                        col.coerceIn(
                            0,
                            line.lastIndex
                        )
                    }

                val card =
                    line[targetCol]

                card.requestFocus()

                ensureCardVisible(card)

                return true
            }

            r += dir
        }

        return false
    }

    /**
     * Restore D-pad focus to the Home channel that opened the order sidebar.
     * Uses stable [channelId] because loadCustomChannelRow may have rebuilt views.
     */
    internal fun restoreFocusToChannel(channelId: Long, fallback: View?) {
        if (channelId >= 0L) {
            // channelFocusGrid rows align with enabled channel order
            val order = customChannelIds.toList()
            val rowIndex = order.indexOf(channelId)
            if (rowIndex >= 0) {
                val line = channelFocusGrid.getOrNull(rowIndex)
                val card = line?.firstOrNull()
                if (card != null && card.isAttachedToWindow) {
                    card.requestFocus()
                    ensureCardVisible(card)
                    return
                }
            }
            // Search all cards for matching ChannelPos.channelId
            for (line in channelFocusGrid) {
                for (card in line) {
                    val pos = card.tag as? ChannelPos
                    if (pos != null && pos.channelId == channelId && card.isAttachedToWindow) {
                        card.requestFocus()
                        ensureCardVisible(card)
                        return
                    }
                }
            }
        }
        if (fallback != null && fallback.isAttachedToWindow && fallback.visibility == View.VISIBLE) {
            fallback.requestFocus()
            return
        }
        // Last resort: first channel card — never Profile
        channelFocusGrid.firstOrNull()?.firstOrNull()?.requestFocus()
    }

    /** Focus the first card of a channel row (used during Reorder mode). */
    internal fun focusChannelRowForReorder(rowIndex: Int) {
        val line = channelFocusGrid.getOrNull(rowIndex) ?: return
        val card = line.firstOrNull() ?: return
        card.post {
            card.requestFocus()
            ensureCardVisible(card)
            // Soft highlight: scale slightly
            card.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            line.drop(1).forEach { other ->
                other.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }
    }

    private fun handleChannelCardKey(
        v: View,
        keyCode: Int,
        event: KeyEvent
    ): Boolean {

        if (
            event.action !=
            KeyEvent.ACTION_DOWN
        ) {
            return false
        }

        // Channel order sidebar open: never let Home cards steal D-pad
        if (channelManage.isOpen()) {
            return channelManage.handleReorderKey(keyCode, event)
        }

        val pos =
            v.tag as? ChannelPos
                ?: return false

        when (keyCode) {

            KeyEvent.KEYCODE_DPAD_DOWN -> {

                focusChannelCell(
                    pos.row + 1,
                    pos.col,
                    dir = +1,
                    preferFirst = true
                )

                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {

                if (
                    pos.row > 0 &&
                    focusChannelCell(
                        pos.row - 1,
                        pos.col,
                        dir = -1,
                        preferFirst = true
                    )
                ) {

                    // ok

                } else if (
                    appsContainer.childCount > 0
                ) {

                    appsContainer
                        .getChildAt(0)
                        ?.requestFocus()

                    homeScroll.smoothScrollTo(
                        0,
                        0
                    )

                } else {

                    focusFilmNearApp(v)

                    homeScroll.smoothScrollTo(
                        0,
                        0
                    )
                }

                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {

                val line =
                    channelFocusGrid
                        .getOrNull(
                            pos.row
                        )
                        ?: return true

                if (
                    pos.col <
                    line.lastIndex
                ) {

                    val next =
                        line[
                            pos.col + 1
                        ]

                    next.requestFocus()

                    ensureCardVisible(
                        next
                    )
                }

                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {

                val line =
                    channelFocusGrid
                        .getOrNull(
                            pos.row
                        )
                        ?: return true

                if (pos.col > 0) {

                    val prev =
                        line[
                            pos.col - 1
                        ]

                    prev.requestFocus()

                    ensureCardVisible(
                        prev
                    )
                } else {
                    // Leftmost item: reveal this row's controls beside content
                    if (event.repeatCount == 0) {
                        val cid = pos.channelId
                        if (cid >= 0L) channelManage.open(v, cid)
                    }
                }

                return true
            }

            else ->
                return false
        }
    }

    private fun channelPosOf(
        view: View
    ): ChannelPos? {

        var v: View? =
            view

        while (v != null) {

            val tag =
                v.tag

            if (
                tag is ChannelPos
            ) {
                return tag
            }

            v =
                v.parent as? View
        }

        return null
    }

    private fun channelRowIndexOf(
        view: View
    ): Int =
        channelPosOf(view)?.row ?: -1

    private fun channelColIndexOf(
        view: View
    ): Int =
        channelPosOf(view)?.col ?: 0

    private fun ensureUniqueFocusId(
        v: View
    ) {

        if (
            v.id == View.NO_ID ||
            v.id ==
            R.id.recommendationCard
        ) {

            v.id =
                View.generateViewId()
        }
    }

    private fun wireHomeRowFocus() {

        val filmCount =
            recommendationContainer.childCount

        val appCount =
            appsContainer.childCount

        val rows =
            channelCardRows()

        val customFirst =
            rows
                .firstOrNull()
                ?.getChildAt(0)

        for (
        i in 0 until filmCount
        ) {

            val child =
                recommendationContainer
                    .getChildAt(i)

            ensureUniqueFocusId(child)

            child.nextFocusUpId =
                activeTabButton().id

            if (appCount > 0) {

                val first =
                    appsContainer
                        .getChildAt(0)

                ensureUniqueFocusId(first)

                child.nextFocusDownId =
                    first.id

            } else if (
                customFirst != null
            ) {

                ensureUniqueFocusId(
                    customFirst
                )

                child.nextFocusDownId =
                    customFirst.id
            }
        }

        for (
        i in 0 until appCount
        ) {

            val child =
                appsContainer
                    .getChildAt(i)

            ensureUniqueFocusId(child)

            if (filmCount > 0) {

                val film =
                    recommendationContainer
                        .getChildAt(
                            i.coerceAtMost(
                                filmCount - 1
                            )
                        )

                ensureUniqueFocusId(film)

                child.nextFocusUpId =
                    film.id

            } else {

                child.nextFocusUpId =
                    activeTabButton().id
            }

            if (
                customFirst != null
            ) {

                ensureUniqueFocusId(
                    customFirst
                )

                child.nextFocusDownId =
                    customFirst.id
            }
        }

        for (
        (rIndex, row)
        in rows.withIndex()
        ) {

            val prevRow =
                rows.getOrNull(
                    rIndex - 1
                )

            val nextRow =
                rows.getOrNull(
                    rIndex + 1
                )

            for (
            j in 0 until row.childCount
            ) {

                val card =
                    row.getChildAt(j)

                ensureUniqueFocusId(card)

                if (
                    prevRow != null
                ) {

                    val up =
                        prevRow.getChildAt(0)

                    ensureUniqueFocusId(up)

                    card.nextFocusUpId =
                        up.id

                } else if (
                    appCount > 0
                ) {

                    val app =
                        appsContainer.getChildAt(0)

                    ensureUniqueFocusId(app)

                    card.nextFocusUpId =
                        app.id

                } else if (
                    filmCount > 0
                ) {

                    val film0 =
                        recommendationContainer
                            .getChildAt(0)

                    ensureUniqueFocusId(
                        film0
                    )

                    card.nextFocusUpId =
                        film0.id

                } else {

                    card.nextFocusUpId =
                        activeTabButton().id
                }

                if (
                    nextRow != null
                ) {

                    val down =
                        nextRow.getChildAt(0)

                    ensureUniqueFocusId(
                        down
                    )

                    card.nextFocusDownId =
                        down.id

                } else {

                    card.nextFocusDownId =
                        card.id
                }
            }
        }

        appsGrid.nextFocusUpId =
            activeTabButton().id

        weatherContent.nextFocusUpId =
            activeTabButton().id

        filesContent.nextFocusUpId =
            activeTabButton().id
    }

    // ─────────────────────────────────────────────
    // GLOBAL KEY HANDLING
    // ─────────────────────────────────────────────

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        if (
            event.action ==
            KeyEvent.ACTION_DOWN &&
            event.keyCode ==
            KeyEvent.KEYCODE_BACK
        ) {

            if (sidebarOpen) {

                if (
                    !settingsSidebar
                        .handleBackPress()
                ) {

                    settingsSidebar
                        .closeSidebar()
                }

                return true
            }

            // Channel management panel
            if (channelManage.handleBack()) {
                return true
            }

            // Search bar expand / keyboard → collapse dulu
            if (topBar.handleBackSearch()) {
                return true
            }

            // File manager: naik folder / tutup sidebar files dulu
            if (
                activeTab == Tab.FILES &&
                fileManager.handleBack()
            ) {
                return true
            }

            // Sudah di root launcher (Home/Apps/Weather/Files root):
            // jangan finish / recreate — layar hitam. Cukup telan event.
            return true
        }

        if (
            event.action !=
            KeyEvent.ACTION_DOWN
        ) {
            return super.dispatchKeyEvent(
                event
            )
        }

        if (
            sidebarOpen ||
            fileManager.isSidebarOpen
        ) {
            return super.dispatchKeyEvent(
                event
            )
        }

        val focused =
            currentFocus

        if (
            activeTab == Tab.APPS &&
            focused != null &&
            isInAppsGrid(focused)
        ) {

            if (
                handleGridDpad(
                    focused,
                    event.keyCode
                )
            ) {
                return true
            }
        }

        when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_UP -> {

                if (
                    focused != null &&
                    isTopBar(focused)
                ) {
                    return true
                }

                if (
                    focused != null &&
                    isInCustomChannelRow(
                        focused
                    ) &&
                    activeTab == Tab.HOME
                ) {

                    val rIdx =
                        channelRowIndexOf(
                            focused
                        )

                    val col =
                        channelColIndexOf(
                            focused
                        )

                    if (
                        rIdx > 0 &&
                        focusChannelCell(
                            rIdx - 1,
                            col,
                            dir = -1,
                            preferFirst = true
                        )
                    ) {
                    } else if (
                        appsContainer.childCount > 0
                    ) {

                        appsContainer
                            .getChildAt(
                                col.coerceAtMost(
                                    appsContainer.childCount - 1
                                )
                            )
                            ?.requestFocus()

                        homeScroll.smoothScrollTo(
                            0,
                            0
                        )

                    } else {

                        focusFilmNearApp(
                            focused
                        )

                        homeScroll.smoothScrollTo(
                            0,
                            0
                        )
                    }

                    return true
                }

                if (
                    focused != null &&
                    isInAppsRow(focused) &&
                    activeTab == Tab.HOME
                ) {

                    focusFilmNearApp(
                        focused
                    )

                    return true
                }

                if (
                    focused != null &&
                    isInFilmRow(focused) &&
                    activeTab == Tab.HOME
                ) {

                    activeTabButton()
                        .requestFocus()

                    return true
                }

                if (
                    focused != null &&
                    isInAppsGrid(focused)
                ) {

                    appsButton
                        .requestFocus()

                    return true
                }

                if (
                    activeTab == Tab.FILES &&
                    fileManager.isToolbar(
                        focused
                    )
                ) {

                    filesButton
                        .requestFocus()

                    return true
                }

                if (
                    activeTab == Tab.FILES &&
                    focused != null &&
                    fileManager.isInList(
                        focused
                    )
                ) {

                    if (
                        fileManager.isFirstRow(
                            focused
                        )
                    ) {

                        fileManager
                            .focusMenuButton()

                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {

                if (
                    focused === appsButton ||
                    (
                            focused != null &&
                                    isTopBar(focused) &&
                                    activeTab == Tab.APPS
                            )
                ) {

                    if (
                        activeTab == Tab.APPS
                    ) {

                        focusFirstGridItem()

                        return true
                    }
                }

                if (
                    (
                            focused === homeButton ||
                                    (
                                            focused != null &&
                                                    isTopBar(focused) &&
                                                    activeTab == Tab.HOME
                                            )
                            ) &&
                    activeTab == Tab.HOME
                ) {

                    if (
                        recommendationContainer
                            .childCount > 0
                    ) {

                        recommendationContainer
                            .getChildAt(0)
                            .requestFocus()

                        return true
                    }
                }

                if (
                    focused != null &&
                    isInFilmRow(focused) &&
                    activeTab == Tab.HOME
                ) {

                    if (
                        appsContainer
                            .childCount > 0
                    ) {

                        appsContainer
                            .getChildAt(0)
                            ?.requestFocus()

                    } else {

                        firstCustomChannelCard()
                            ?.requestFocus()
                    }

                    return true
                }

                if (
                    focused != null &&
                    isInAppsRow(focused) &&
                    activeTab == Tab.HOME
                ) {

                    val card =
                        firstCustomChannelCard()

                    if (card != null) {

                        card.requestFocus()

                        return true
                    }
                }

                if (
                    focused != null &&
                    isInCustomChannelRow(
                        focused
                    ) &&
                    activeTab == Tab.HOME
                ) {

                    val rIdx =
                        channelRowIndexOf(
                            focused
                        )

                    val col =
                        channelColIndexOf(
                            focused
                        )

                    if (rIdx >= 0) {

                        focusChannelCell(
                            rIdx + 1,
                            col,
                            dir = +1,
                            preferFirst = true
                        )
                    }

                    return true
                }

                if (
                    focused === filesButton &&
                    activeTab == Tab.FILES
                ) {

                    fileManager
                        .focusMenuButton()

                    return true
                }

                if (
                    activeTab == Tab.FILES &&
                    fileManager.isToolbar(
                        focused
                    )
                ) {

                    fileManager
                        .focusFirstItem()

                    return true
                }
            }
        }

        return super.dispatchKeyEvent(
            event
        )
    }

    // ─────────────────────────────────────────────
    // APPS GRID
    // ─────────────────────────────────────────────

    private fun handleGridDpad(
        focused: View,
        keyCode: Int
    ): Boolean {

        val cols =
            appsGrid.numColumns
                .coerceAtLeast(1)

        // Adapter count (includes optional "Other apps" card), not full package list
        val count =
            appsGrid.adapter?.count ?: return false

        if (count == 0) {
            return false
        }

        // Resolve position: Int tag for normal cells, "other_apps" → last index
        val pos: Int = when (val tag = focused.tag) {
            is Int -> tag
            "other_apps" -> count - 1
            else -> return false
        }

        if (pos !in 0 until count) {
            return false
        }

        val row = pos / cols
        val col = pos % cols

        val targetPos: Int? =
            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (pos < count - 1) pos + 1 else null

                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (pos > 0) pos - 1 else null

                KeyEvent.KEYCODE_DPAD_UP ->
                    if (row > 0) {
                        // Same column in previous row (previous rows are always complete)
                        (row - 1) * cols + col
                    } else {
                        // Leave grid → Shell / Apps tab button
                        appsButton.requestFocus()
                        return true
                    }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val nextRowStart = (row + 1) * cols
                    if (nextRowStart >= count) {
                        // No row below — stay on current item
                        null
                    } else {
                        // Prefer same column; if next row is short, land on its last item
                        val sameCol = nextRowStart + col
                        if (sameCol < count) sameCol else count - 1
                    }
                }

                else ->
                    return false
            }

        if (targetPos == null) {
            // Consume the key so default focus search does not jump to Shell
            return true
        }

        return focusGridPosition(
            targetPos
        )
    }

    private fun focusGridPosition(
        position: Int
    ): Boolean {

        for (
        i in 0 until
                appsGrid.childCount
        ) {

            val child =
                appsGrid.getChildAt(i)

            if (
                child?.tag ==
                position
            ) {

                child.requestFocus()

                return true
            }
        }

        appsGrid.setSelection(
            position
        )

        appsGrid.post {

            for (
            i in 0 until
                    appsGrid.childCount
            ) {

                val child =
                    appsGrid.getChildAt(i)

                if (
                    child?.tag ==
                    position
                ) {

                    child.requestFocus()

                    return@post
                }
            }

            val rel =
                position -
                        appsGrid.firstVisiblePosition

            if (
                rel in 0 until
                appsGrid.childCount
            ) {

                appsGrid
                    .getChildAt(rel)
                    ?.requestFocus()
            }
        }

        return true
    }

    private fun focusFirstGridItem():
            Boolean {

        if (
            appsGrid.visibility !=
            View.VISIBLE
        ) {
            return false
        }

        if (
            appsGrid.childCount > 0
        ) {

            appsGrid
                .getChildAt(0)
                ?.requestFocus()

            return true
        }

        appsGrid.requestFocus()

        appsGrid.post {

            appsGrid
                .getChildAt(0)
                ?.requestFocus()
        }

        return true
    }

    private fun focusFilmNearApp(
        from: View
    ) {

        if (
            recommendationContainer
                .childCount == 0
        ) {

            activeTabButton()
                .requestFocus()

            return
        }

        recommendationContainer
            .getChildAt(0)
            ?.requestFocus()

        homeScroll.smoothScrollTo(
            0,
            0
        )
    }

    private fun isInFilmRow(
        view: View
    ): Boolean {

        var v: View? =
            view

        while (v != null) {

            if (
                v === recommendationContainer
            ) {
                return true
            }

            if (
                v.parent ===
                recommendationContainer
            ) {
                return true
            }

            v =
                v.parent as? View
        }

        return false
    }

    private fun isInAppsRow(
        view: View
    ): Boolean {

        var v: View? =
            view

        while (v != null) {

            if (
                v === appsContainer
            ) {
                return true
            }

            if (
                v === appsGrid
            ) {
                return false
            }

            v =
                v.parent as? View
        }

        return false
    }

    private fun isInCustomChannelRow(
        view: View
    ): Boolean {

        var v: View? =
            view

        while (v != null) {

            if (
                v === customChannelsSection
            ) {
                return true
            }

            v =
                v.parent as? View
        }

        return false
    }

    private fun isInAppsGrid(
        view: View
    ): Boolean {

        var v: View? =
            view

        while (v != null) {

            if (
                v === appsGrid
            ) {
                return true
            }

            v =
                v.parent as? View
        }

        return false
    }

    private fun isTopBar(
        view: View
    ): Boolean {

        var v: View? =
            view

        while (v != null) {

            if (
                v.id ==
                R.id.topBar
            ) {
                return true
            }

            v =
                v.parent as? View
        }

        return false
    }

    // ─────────────────────────────────────────────
    // TABS
    // ─────────────────────────────────────────────

    internal fun showTab(
        tab: Tab,
        animate: Boolean = true
    ) {

        if (
            tab == activeTab &&
            !animate
        ) {

            applyTabStyle(tab)

            setPanelVisibility(tab)

            if (
                tab == Tab.FILES
            ) {

                fileManager.openDirectory(
                    fileManager.currentDir,
                    focusFirst = false
                )
            }

            return
        }

        if (
            tab == activeTab
        ) {

            applyTabStyle(tab)

            return
        }

        if (isAnimatingTab) {
            return
        }

        val oldTab =
            activeTab

        // Arah animasi ikut urutan nav yang user atur, bukan ordinal enum
        val navOrder = settingsSidebar.readNavTabOrder()
        val oldIdx = navOrder.indexOf(oldTab).let { if (it < 0) oldTab.ordinal else it }
        val newIdx = navOrder.indexOf(tab).let { if (it < 0) tab.ordinal else it }
        val goingRight = newIdx > oldIdx

        val outgoing =
            viewForTab(oldTab)

        val incoming =
            viewForTab(tab)

        activeTab =
            tab

        applyTabStyle(tab)

        // Background cuaca full-bleed cuma nempel pas tab Weather aktif — di-set langsung
        // (gak nunggu animasi outgoing/incoming kelar) biar gak nyangkut pas pindah tab lain.
        findViewById<View>(R.id.weatherBgImage)?.visibility =
            if (tab == Tab.WEATHER) View.VISIBLE else View.GONE

        wireHomeRowFocus()

        // Auto-scroll hanya di Home; backdrop ikut scroll di dalam heroSection
        if (tab == Tab.HOME) {
            startCarouselAutoScroll()
        } else {
            stopCarouselAutoScroll()
        }

        if (
            tab == Tab.FILES
        ) {

            fileManager.openDirectory(
                fileManager.currentDir,
                focusFirst = false
            )
        }

        if (tab == Tab.WEATHER) {
            weatherController.onWeatherTabVisible()
        }

        if (!animate) {

            outgoing.visibility =
                View.GONE

            incoming.visibility =
                View.VISIBLE

            incoming.translationX =
                0f

            incoming.alpha =
                1f

            return
        }

        isAnimatingTab =
            true

        val width =
            contentHost.width
                .coerceAtLeast(1)
                .toFloat()

        val outTo =
            if (goingRight) {
                -width * 0.25f
            } else {
                width * 0.25f
            }

        val inFrom =
            if (goingRight) {
                width * 0.25f
            } else {
                -width * 0.25f
            }

        incoming.visibility =
            View.VISIBLE

        incoming.translationX =
            inFrom

        incoming.alpha =
            0f

        outgoing.animate()
            .translationX(outTo)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {

                outgoing.visibility =
                    View.GONE

                outgoing.translationX =
                    0f

                outgoing.alpha =
                    1f
            }
            .start()

        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .withEndAction {

                isAnimatingTab =
                    false
            }
            .start()
    }

    private fun setPanelVisibility(
        tab: Tab
    ) {

        homeContent.visibility =
            if (tab == Tab.HOME) {
                View.VISIBLE
            } else {
                View.GONE
            }

        appsGrid.visibility =
            if (tab == Tab.APPS) {
                View.VISIBLE
            } else {
                View.GONE
            }

        filesContent.visibility =
            if (tab == Tab.FILES) {
                View.VISIBLE
            } else {
                View.GONE
            }

        weatherContent.visibility =
            if (tab == Tab.WEATHER) {
                View.VISIBLE
            } else {
                View.GONE
            }

        findViewById<View>(R.id.weatherBgImage)?.visibility =
            if (tab == Tab.WEATHER) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (tab == Tab.WEATHER) {
            weatherController.onWeatherTabVisible()
        }
    }

    private fun viewForTab(
        tab: Tab
    ): View =
        when (tab) {

            Tab.HOME ->
                homeContent

            Tab.APPS ->
                appsGrid

            Tab.FILES ->
                filesContent

            Tab.WEATHER ->
                weatherContent
        }

    private fun applyTabStyle(
        tab: Tab
    ) {

        homeDot.visibility =
            if (tab == Tab.HOME) {
                View.VISIBLE
            } else {
                View.GONE
            }

        appsDot.visibility =
            if (tab == Tab.APPS) {
                View.VISIBLE
            } else {
                View.GONE
            }

        filesDot.visibility =
            if (tab == Tab.FILES) {
                View.VISIBLE
            } else {
                View.GONE
            }

        weatherDot.visibility =
            if (tab == Tab.WEATHER) {
                View.VISIBLE
            } else {
                View.GONE
            }

        homeButtonLabel.setTypeface(
            null,
            if (tab == Tab.HOME) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )

        appsButtonLabel.setTypeface(
            null,
            if (tab == Tab.APPS) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )

        filesButtonLabel.setTypeface(
            null,
            if (tab == Tab.FILES) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )

        weatherButtonLabel.setTypeface(
            null,
            if (tab == Tab.WEATHER) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )

        if (!homeButton.isFocused) {
            homeButtonLabel.setTextColor(
                colorWhite
            )
        }

        if (!appsButton.isFocused) {
            appsButtonLabel.setTextColor(
                colorWhite
            )
        }

        if (!filesButton.isFocused) {
            filesButtonLabel.setTextColor(
                colorWhite
            )
        }

        if (!weatherButton.isFocused) {
            weatherButtonLabel.setTextColor(
                colorWhite
            )
        }
    }

    // ─────────────────────────────────────────────
    // RESUME / RESULT / DESTROY
    // ─────────────────────────────────────────────

    // Refresh berkala: cek TvProvider tiap beberapa menit selagi launcher
    // di foreground, diff-only lewat loadRecommendations()/loadCustomChannelRow()
    // (UI cuma update kalau datanya emang beda), lalu prune poster disk cache
    // biar poster yg udah gak dipake (konten lama kegusur dari limit 10/channel)
    // ke-hapus, bukan numpuk.
    private val recommendationsRefreshIntervalMs = 3 * 60 * 1000L
    private val posterPruneDelayMs = 8_000L

    private val recommendationsRefreshRunnable: Runnable =
        Runnable {
            activePosterUris.clear()

            loadRecommendations()
            loadCustomChannelRow()

            handler.postDelayed(
                { prunePosterDiskCache() },
                posterPruneDelayMs
            )

            handler.postDelayed(
                recommendationsRefreshRunnable,
                recommendationsRefreshIntervalMs
            )
        }

    /** Saat user tekan Home di remote: bawa ke Start tab yang dipilih. */
    private var goToStartTabOnResume = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        goToStartTabOnResume = true
    }

    override fun onResume() {

        super.onResume()

        loadApplications()

        updateNetworkIndicator()

        if (sidebarOpen) {
            settingsSidebar.populateChannelList()
        }

        handler.removeCallbacks(recommendationsRefreshRunnable)
        handler.postDelayed(
            recommendationsRefreshRunnable,
            recommendationsRefreshIntervalMs
        )

        if (goToStartTabOnResume) {
            goToStartTabOnResume = false
            if (sidebarOpen) settingsSidebar.closeSidebar()
            val tab = settingsSidebar.readSavedDefaultTab()
            showTab(tab, animate = false)
            activeTabButton().requestFocus()
        }

        if (activeTab == Tab.HOME) {
            startCarouselAutoScroll()
        }
    }

    override fun onPause() {

        super.onPause()

        handler.removeCallbacks(recommendationsRefreshRunnable)
        stopCarouselAutoScroll()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        settingsSidebar.onActivityResult(
            requestCode,
            resultCode,
            data
        )
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(
            null
        )

        imageExecutor.shutdownNow()

        posterCache.evictAll()

        try {

            unregisterReceiver(
                packageReceiver
            )

        } catch (_: Exception) {
        }

        super.onDestroy() }
    }