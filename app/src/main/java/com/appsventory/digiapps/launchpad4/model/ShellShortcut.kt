package com.appsventory.digiapps.launchpad4.model

import org.json.JSONObject
import java.util.UUID

/**
 * Shortcut shown in the Shell action pill (Group C).
 *
 * Built-in entries use stable IDs ([ID_LAUNCHER], [ID_SYSTEM], [ID_INTERNET]) and are
 * never deleted from storage. User entries may be created, edited, disabled, and removed.
 */
data class ShellShortcut(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val packageName: String = "",
    /** Fully-qualified or relative activity class name; empty = use launcher activity. */
    val activityName: String = "",
    /**
     * Icon representation:
     * - "app" → load icon from [packageName]
     * - "res:<drawable_name>" → built-in drawable, e.g. "res:ic_settings"
     */
    val iconRef: String = "app",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /**
     * Origin / protection class:
     * - [TYPE_LAUNCHER] — Setting Launcher (always enabled, never deletable)
     * - [TYPE_SYSTEM] — Setting System (can disable, never deletable)
     * - [TYPE_INTERNET] — Internet / Wi-Fi (can disable, never deletable)
     * - [TYPE_USER] — user-created
     */
    val type: String = TYPE_USER,
    /**
     * How to execute:
     * - [ACTION_OPEN_SIDEBAR] — open Digi Home settings sidebar
     * - [ACTION_SYSTEM_SETTINGS] — android.provider.Settings.ACTION_SETTINGS
     * - [ACTION_WIFI_SETTINGS] — Wi-Fi / wireless settings
     * - [ACTION_APP] — launch package / activity
     */
    val action: String = ACTION_APP
) {
    val isBuiltIn: Boolean
        get() = type == TYPE_LAUNCHER || type == TYPE_SYSTEM || type == TYPE_INTERNET

    val canDelete: Boolean
        get() = type == TYPE_USER

    val canDisable: Boolean
        get() = type != TYPE_LAUNCHER

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("packageName", packageName)
        put("activityName", activityName)
        put("iconRef", iconRef)
        put("enabled", enabled)
        put("sortOrder", sortOrder)
        put("type", type)
        put("action", action)
    }

    companion object {
        const val ID_LAUNCHER = "builtin_launcher"
        const val ID_SYSTEM = "builtin_system"
        const val ID_INTERNET = "builtin_internet"

        const val TYPE_LAUNCHER = "launcher"
        const val TYPE_SYSTEM = "system"
        const val TYPE_INTERNET = "internet"
        const val TYPE_USER = "user"

        const val ACTION_OPEN_SIDEBAR = "open_sidebar"
        const val ACTION_SYSTEM_SETTINGS = "system_settings"
        const val ACTION_WIFI_SETTINGS = "wifi_settings"
        const val ACTION_APP = "app"

        fun fromJson(obj: JSONObject): ShellShortcut = ShellShortcut(
            id = obj.optString("id", UUID.randomUUID().toString()),
            displayName = obj.optString("displayName", ""),
            packageName = obj.optString("packageName", ""),
            activityName = obj.optString("activityName", ""),
            iconRef = obj.optString("iconRef", "app"),
            enabled = obj.optBoolean("enabled", true),
            sortOrder = obj.optInt("sortOrder", 0),
            type = obj.optString("type", TYPE_USER).ifBlank { TYPE_USER },
            action = obj.optString("action", ACTION_APP).ifBlank { ACTION_APP }
        )

        fun defaultBuiltIns(): List<ShellShortcut> = listOf(
            ShellShortcut(
                id = ID_LAUNCHER,
                displayName = "Setting Launcher",
                iconRef = "res:ic_home",
                enabled = true,
                sortOrder = 0,
                type = TYPE_LAUNCHER,
                action = ACTION_OPEN_SIDEBAR
            ),
            ShellShortcut(
                id = ID_SYSTEM,
                displayName = "Setting System",
                iconRef = "res:ic_settings",
                enabled = true,
                sortOrder = 1,
                type = TYPE_SYSTEM,
                action = ACTION_SYSTEM_SETTINGS
            ),
            ShellShortcut(
                id = ID_INTERNET,
                displayName = "Internet",
                iconRef = "res:ic_wifi",
                enabled = true,
                sortOrder = 2,
                type = TYPE_INTERNET,
                action = ACTION_WIFI_SETTINGS
            )
        )
    }
}
