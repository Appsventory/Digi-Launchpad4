package com.appsventory.digiapps.launchpad4.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sumber data daftar aplikasi terinstal + cache visual (icon/banner/label).
 *
 * PENTING (lihat catatan performa asli): query PackageManager itu MAHAL — bisa makan
 * puluhan-ratusan ms, apalagi kalau device banyak app terinstal. Semua scan + sorting
 * dijalankan di [ioExecutor] (background thread), tidak pernah di main thread, supaya
 * launcher tidak nge-freeze sesaat tiap kali daftar app di-refresh (onCreate/onResume/
 * package-changed broadcast). Icon/banner juga di-cache in-memory per package supaya
 * GridView tidak decode ulang tiap kali item di-bind (mis. saat scroll).
 */
class AppRepository(private val appContext: Context) {

    private val pm: PackageManager = appContext.packageManager
    private val ownPackage: String = appContext.packageName

    /** Executor tunggal dipakai bersama oleh seluruh app untuk kerja I/O ringan (scan PM, HTTP, decode bitmap). */
    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanInFlight = false
    private var scanPending = false

    /** Cache icon/banner/label per package. */
    private val visualCache = HashMap<String, Triple<Drawable?, Drawable?, CharSequence>>()

    /** Package yang resolve lewat LEANBACK_LAUNCHER — dipakai buat pisahin app "made for TV"
     *  dari app biasa (sideload/phone) di sidebar Select app. Diisi ulang tiap [scanInstalledApps]. */
    @Volatile
    private var leanbackPackages: Set<String> = emptySet()

    /** True kalau app ini "dibuat untuk TV" (punya activity dengan CATEGORY_LEANBACK_LAUNCHER). */
    fun isTvApp(pkg: String): Boolean = leanbackPackages.contains(pkg)

    fun clearVisualCache() {
        visualCache.clear()
    }

    /** (banner, icon, label) — banner null berarti pakai mode icon+nama. */
    fun visualsFor(application: ApplicationInfo): Triple<Drawable?, Drawable?, CharSequence> =
        visualCache.getOrPut(application.packageName) {
            val banner: Drawable? = try { application.loadBanner(pm) } catch (_: Exception) { null }
            val icon: Drawable? = if (banner == null) {
                try { application.loadIcon(pm) } catch (_: Exception) { null }
            } else null
            val label: CharSequence = try {
                application.loadLabel(pm)
            } catch (_: Exception) {
                application.packageName
            }
            Triple(banner, icon, label)
        }

    fun launchIntentFor(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)

    fun applicationInfoOrNull(pkg: String): ApplicationInfo? =
        try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }

    fun labelFor(app: ApplicationInfo): String = app.loadLabel(pm).toString()

    /**
     * Muat semua app yang bisa diluncurkan (LAUNCHER + LEANBACK_LAUNCHER), lebih andal
     * di Android TV daripada getInstalledApplications saja. [onResult] selalu dipanggil
     * di main thread. Jika dipanggil beruntun sebelum scan sebelumnya selesai, request
     * baru di-antre (bukan numpuk beberapa scan sekaligus).
     */
    fun loadApplications(onResult: (List<ApplicationInfo>) -> Unit) {
        if (scanInFlight) { scanPending = true; return }
        scanInFlight = true
        ioExecutor.execute {
            val resolved = scanInstalledApps()
            mainHandler.post {
                onResult(resolved)
                scanInFlight = false
                if (scanPending) {
                    scanPending = false
                    loadApplications(onResult)
                }
            }
        }
    }

    private fun scanInstalledApps(): List<ApplicationInfo> {
        val packages = linkedSetOf<String>()
        val leanback = linkedSetOf<String>()
        fun collect(category: String, trackLeanback: Boolean = false) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (_: Exception) {
                pm.queryIntentActivities(intent, 0)
            }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == ownPackage) continue
                packages.add(pkg)
                if (trackLeanback) leanback.add(pkg)
            }
        }
        collect(Intent.CATEGORY_LAUNCHER)
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, trackLeanback = true)
        leanbackPackages = leanback
        return packages.mapNotNull { pkg ->
            try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    /**
     * Broader discovery for the Shell Shortcut app picker: launcher apps PLUS
     * other installed packages visible under QUERY_ALL_PACKAGES / <queries>.
     * Packages without a launcher Activity are included; the caller leaves
     * activityName blank so the user can enter a target manually.
     */
    fun loadAllDiscoverableApplications(onResult: (List<ApplicationInfo>) -> Unit) {
        ioExecutor.execute {
            val byPkg = linkedMapOf<String, ApplicationInfo>()
            // 1) Launcher + Leanback entries (same as scanInstalledApps)
            for (ai in scanInstalledApps()) {
                byPkg[ai.packageName] = ai
            }
            // 2) All packages PackageManager will reveal to us
            try {
                @Suppress("DEPRECATION")
                val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (ai in all) {
                    if (ai.packageName == ownPackage) continue
                    byPkg.putIfAbsent(ai.packageName, ai)
                }
            } catch (_: Exception) { /* visibility restricted — keep launcher set */ }
            val sorted = byPkg.values.sortedBy {
                try { pm.getApplicationLabel(it).toString().lowercase() } catch (_: Exception) { it.packageName }
            }
            mainHandler.post { onResult(sorted) }
        }
    }

    fun hasLauncherActivity(packageName: String): Boolean =
        launchIntentFor(packageName) != null
}
