package com.appsventory.digiapps.launchpad4.data

/**
 * Semua nama file/kunci SharedPreferences dalam satu tempat, supaya tidak ada
 * key yang typo/duplikat tersebar di berbagai screen.
 */
object PrefsKeys {
    const val PREFS_FILE = "digi_home"

    const val FAVORITES = "favorite_packages"
    /** Content Carousel (baris Top picks di Home) aktif/nonaktif. */
    const val BANNER = "banner_enabled"
    /**
     * Channel ID tunggal yang dipilih untuk Content Carousel (single-select).
     * Disimpan sebagai string Long; kosong = belum dipilih.
     */
    const val CAROUSEL_CHANNEL_ID = "carousel_channel_id"
    /** Legacy multi-select (masih dibaca sekali untuk migrasi). */
    const val ENABLED_TV_CHANNEL_IDS = "enabled_tv_channel_ids"

    /** Custom Channel (baris di bawah Favorite) aktif/nonaktif. */
    const val CUSTOM_CHANNEL_ENABLED = "custom_channel_enabled"
    /** Channel ID tunggal (legacy). */
    const val CUSTOM_CHANNEL_ID = "custom_channel_id"
    /** Multi-select channel IDs untuk Custom Channel (koma-separated), gaya Google TV. */
    const val CUSTOM_CHANNEL_IDS = "custom_channel_ids"
    /** Ordered list of all managed custom-channel IDs (enabled + disabled). */
    const val CUSTOM_CHANNEL_ORDER = "custom_channel_order"
    /** Disabled custom-channel IDs (still listed in ORDER / management panel). */
    const val CUSTOM_CHANNEL_DISABLED_IDS = "custom_channel_disabled_ids"

    const val WALLPAPER_MODE = "wallpaper_mode" // "default" | "image" | "gradient"
    const val WALLPAPER_URI = "wallpaper_uri"
    const val WALLPAPER_GRADIENT_INDEX = "wallpaper_gradient_index"
    const val CLOCK_24H = "clock_24h"
    const val DEFAULT_TAB = "default_tab"
    /** Urutan tab nav yang tampil, koma-separated. Contoh: HOME,APPS,FILES,WEATHER. Tidak ada di list = disembunyikan. */
    const val NAV_TAB_ORDER = "nav_tab_order"
    const val FILES_VIEW_MODE = "files_view_mode" // "list" | "grid"
    const val FILES_SORT_MODE = "files_sort_mode" // "name" | "size" | "type"
    /** Format: name|host|share;name2|host2|share2 */
    const val SAMBA_SHARES = "samba_shares"

    const val PERMISSIONS_PROMPTED = "permissions_prompted"

    const val PINNED_APPS = "pinned_apps"

    /** JSON array of user Shell shortcuts (ShellShortcut.toJson). */
    const val SHELL_SHORTCUTS = "shell_shortcuts"

    /**
     * Home weather widget on/off. Effective only when Content Carousel ([BANNER]) is off.
     * When carousel is on, the widget is forced hidden regardless of this flag.
     */
    const val WEATHER_WIDGET_ENABLED = "weather_widget_enabled"

    /** Absolute path to a wallpaper image copied into app-private storage (survives reboot). */
    const val WALLPAPER_LOCAL_PATH = "wallpaper_local_path"

    const val REQUEST_PICK_WALLPAPER = 4201
    const val FILES_GRID_COLS = 5
}
