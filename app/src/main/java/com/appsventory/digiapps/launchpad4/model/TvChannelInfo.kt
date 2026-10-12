package com.appsventory.digiapps.launchpad4.model

/**
 * Satu "channel" Android TV Home Screen yang dipublish app lain lewat
 * `TvContractCompat` (mis. Jellyfin, Plex, YouTube). Digi Home baca ini,
 * bukan login manual ke tiap provider.
 */
data class TvChannelInfo(
    val id: Long,
    val displayName: String,
    val packageName: String,
    val providerName: String,
    val enabled: Boolean = true
)
