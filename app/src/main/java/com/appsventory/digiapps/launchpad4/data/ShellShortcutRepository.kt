package com.appsventory.digiapps.launchpad4.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.model.ShellShortcut
import org.json.JSONArray

/**
 * Persistence + launch helpers for Shell shortcuts (built-in + user).
 *
 * Built-ins are always present in storage with stable IDs. Protection rules:
 * - Setting Launcher: never deleted, never disabled
 * - Setting System / Internet: never deleted, may be disabled
 * - User: full CRUD
 */
class ShellShortcutRepository(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PrefsKeys.PREFS_FILE, Context.MODE_PRIVATE)
) {
    private val pm: PackageManager = context.packageManager

    /**
     * Load all shortcuts, ensuring the three built-ins exist and Setting Launcher
     * cannot remain disabled even if prefs were corrupted.
     */
    fun loadAll(): List<ShellShortcut> {
        val stored = readRaw()
        val byId = stored.associateBy { it.id }.toMutableMap()

        // Ensure built-ins exist (seed on first run or after prefs wipe)
        for (builtin in ShellShortcut.defaultBuiltIns()) {
            val existing = byId[builtin.id]
            if (existing == null) {
                byId[builtin.id] = builtin
            } else {
                // Enforce protection + preserve user-customized icon/name/order
                byId[builtin.id] = existing.copy(
                    type = builtin.type,
                    action = builtin.action,
                    // Launcher cannot be disabled
                    enabled = if (builtin.type == ShellShortcut.TYPE_LAUNCHER) true else existing.enabled,
                    // Keep stable identity fields
                    id = builtin.id
                )
            }
        }

        // Drop any accidental duplicates of built-in IDs with wrong type
        val merged = byId.values
            .sortedBy { it.sortOrder }
            .mapIndexed { index, s -> s.copy(sortOrder = index) }

        // Persist if we had to seed or repair
        if (stored.size != merged.size ||
            stored.any { s ->
                val m = byId[s.id]
                m == null || m.type != s.type || m.enabled != s.enabled
            } ||
            ShellShortcut.defaultBuiltIns().any { byId[it.id] == null }
        ) {
            saveAll(merged)
        }

        return merged
    }

    fun loadEnabled(): List<ShellShortcut> = loadAll().filter { it.enabled }

    fun loadDisabled(): List<ShellShortcut> = loadAll().filter { !it.enabled }

    fun saveAll(list: List<ShellShortcut>) {
        // Final safety: launcher always enabled and present
        val safe = list
            .map { s ->
                if (s.id == ShellShortcut.ID_LAUNCHER || s.type == ShellShortcut.TYPE_LAUNCHER) {
                    s.copy(enabled = true, type = ShellShortcut.TYPE_LAUNCHER, action = ShellShortcut.ACTION_OPEN_SIDEBAR)
                } else s
            }
            .mapIndexed { index, s -> s.copy(sortOrder = index) }
        val arr = JSONArray()
        safe.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(PrefsKeys.SHELL_SHORTCUTS, arr.toString()).apply()
    }

    fun upsert(shortcut: ShellShortcut) {
        val current = loadAll().toMutableList()
        val idx = current.indexOfFirst { it.id == shortcut.id }
        val toSave = when {
            shortcut.type == ShellShortcut.TYPE_LAUNCHER || shortcut.id == ShellShortcut.ID_LAUNCHER ->
                shortcut.copy(enabled = true, type = ShellShortcut.TYPE_LAUNCHER, action = ShellShortcut.ACTION_OPEN_SIDEBAR)
            shortcut.type == ShellShortcut.TYPE_SYSTEM || shortcut.id == ShellShortcut.ID_SYSTEM ->
                shortcut.copy(type = ShellShortcut.TYPE_SYSTEM, action = ShellShortcut.ACTION_SYSTEM_SETTINGS)
            shortcut.type == ShellShortcut.TYPE_INTERNET || shortcut.id == ShellShortcut.ID_INTERNET ->
                shortcut.copy(type = ShellShortcut.TYPE_INTERNET, action = ShellShortcut.ACTION_WIFI_SETTINGS)
            else -> shortcut.copy(type = ShellShortcut.TYPE_USER, action = ShellShortcut.ACTION_APP)
        }
        if (idx >= 0) {
            current[idx] = toSave
        } else {
            current.add(toSave.copy(sortOrder = current.size))
        }
        saveAll(current)
    }

    fun remove(id: String) {
        val target = loadAll().find { it.id == id } ?: return
        if (!target.canDelete) {
            toast("This shortcut cannot be deleted")
            return
        }
        saveAll(loadAll().filter { it.id != id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = loadAll().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val sc = current[idx]
        if (!sc.canDisable && !enabled) {
            toast("Setting Launcher cannot be disabled")
            return
        }
        current[idx] = sc.copy(enabled = enabled)
        saveAll(current)
    }

    /** Move any shortcut (active or inactive) by delta within its section list. */
    fun moveInSection(id: String, delta: Int, activeSection: Boolean) {
        val all = loadAll().toMutableList()
        val section = all.filter { it.enabled == activeSection }.toMutableList()
        val other = all.filter { it.enabled != activeSection }
        val i = section.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, section.lastIndex)
        if (i == j) return
        val item = section.removeAt(i)
        section.add(j, item)
        // Keep active block first, then inactive (stable global order)
        val rebuilt = if (activeSection) section + other else {
            // When moving within inactive, preserve active order then new inactive order
            all.filter { it.enabled } + section
        }
        saveAll(rebuilt)
    }

    /** Move within the full ordered list (used when all are treated in one order). */
    fun moveEnabled(id: String, delta: Int) {
        moveInSection(id, delta, activeSection = true)
    }

    // ─── Launch ───────────────────────────────────────────────

    fun resolveLauncherActivity(packageName: String): String? {
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(packageName)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val ri = try {
            pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL).firstOrNull()
                ?: pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL).firstOrNull()
        } catch (_: Exception) {
            pm.queryIntentActivities(leanback, 0).firstOrNull()
                ?: pm.queryIntentActivities(launcher, 0).firstOrNull()
        } ?: return null
        val info = ri.activityInfo ?: return null
        val full = info.name
        return if (full.startsWith(packageName)) {
            "." + full.removePrefix(packageName)
        } else {
            full
        }
    }

    fun componentFor(shortcut: ShellShortcut): ComponentName? {
        val pkg = shortcut.packageName.trim()
        if (pkg.isEmpty()) return null
        val act = shortcut.activityName.trim()
        if (act.isEmpty()) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
            return launch.component
        }
        val className = when {
            act.startsWith(".") -> pkg + act
            act.contains(".") -> act
            else -> "$pkg.$act"
        }
        return ComponentName(pkg, className)
    }

    /**
     * Launch the shortcut. Built-in actions are handled here; app shortcuts use PackageManager.
     * [openSidebar] is provided by the caller for ACTION_OPEN_SIDEBAR.
     */
    fun launch(
        shortcut: ShellShortcut,
        showErrors: Boolean = true,
        openSidebar: (() -> Unit)? = null
    ): Boolean {
        when (shortcut.action) {
            ShellShortcut.ACTION_OPEN_SIDEBAR -> {
                openSidebar?.invoke()
                return true
            }
            ShellShortcut.ACTION_SYSTEM_SETTINGS -> {
                return try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    if (showErrors) toast("System settings not found")
                    false
                }
            }
            ShellShortcut.ACTION_WIFI_SETTINGS -> {
                return try {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: ActivityNotFoundException) {
                        if (showErrors) toast("Network settings not found")
                        false
                    }
                }
            }
        }

        val pkg = shortcut.packageName.trim()
        if (pkg.isEmpty()) {
            if (showErrors) toast("No package configured")
            return false
        }
        try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            if (showErrors) toast("App not installed: $pkg")
            return false
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val component = componentFor(shortcut)
        if (component != null) {
            intent.component = component
            if (shortcut.activityName.isNotBlank()) {
                intent.categories?.clear()
            }
        } else {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                if (showErrors) toast("No launchable activity for $pkg")
                return false
            }
            return try {
                context.startActivity(launch)
                true
            } catch (e: Exception) {
                if (showErrors) toast("Cannot launch: ${e.message}")
                false
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                try {
                    context.startActivity(launch)
                    true
                } catch (_: Exception) {
                    if (showErrors) toast("Cannot launch activity")
                    false
                }
            } else {
                if (showErrors) toast("Activity not found or not exported")
                false
            }
        } catch (e: SecurityException) {
            if (showErrors) toast("Activity not accessible (not exported?)")
            false
        }
    }

    // ─── Icons ────────────────────────────────────────────────

    fun iconFor(shortcut: ShellShortcut): Drawable? {
        val ref = shortcut.iconRef
        if (ref.startsWith("res:")) {
            val name = ref.removePrefix("res:")
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) {
                return try {
                    context.getDrawable(id)
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (shortcut.packageName.isNotBlank()) {
            try {
                return pm.getApplicationIcon(shortcut.packageName)
            } catch (_: Exception) { /* fall through */ }
        }
        return try {
            context.getDrawable(R.drawable.ic_android_robot)
        } catch (_: Exception) {
            null
        }
    }

    fun drawableIdForRef(iconRef: String): Int {
        if (!iconRef.startsWith("res:")) return 0
        val name = iconRef.removePrefix("res:")
        return context.resources.getIdentifier(name, "drawable", context.packageName)
    }

    /** Built-in icons offered in the icon picker (verified drawable names). */
    fun builtInIconNames(): List<String> = listOf(
        // System & settings
        "ic_settings",
        "ic_settings_android",
        "ic_settings_gear",
        "ic_settings_home",
        "ic_settings_image",
        "ic_settings_bookmark",
        "ic_home",
        "ic_search",
        "ic_info",
        "ic_person",
        "ic_add",
        "ic_grid",
        "ic_android_robot",
        // Network
        "ic_wifi",
        "ic_wifi_off",
        "ic_ethernet",
        // Weather
        "ic_weather_clear_day",
        "ic_weather_clear_night",
        "ic_weather_cloudy",
        "ic_weather_partly_cloudy_day",
        "ic_weather_rain",
        "ic_weather_snow",
        "ic_weather_thunderstorm",
        "ic_weather_fog",
        "ic_weather_wind",
        "ic_thermostat",
        "ic_water_drop",
        "ic_location_pin",
        // Media providers (existing project assets)
        "ic_provider_youtube",
        "ic_provider_jellyfin",
        "ic_provider_smarttube",
        "ic_rain",
        "ic_wind"
    )

    private fun readRaw(): List<ShellShortcut> {
        val raw = prefs.getString(PrefsKeys.SHELL_SHORTCUTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ShellShortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(ShellShortcut.fromJson(obj))
            }
            list.sortedBy { it.sortOrder }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
