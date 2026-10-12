package com.appsventory.digiapps.launchpad4.model

import android.net.Uri

data class Recommendation(
    val title: String,
    val topPicksTitle: String = "",
    val description: String = "",
    val posterRes: Int? = null,
    /** Poster portrait (COLUMN_POSTER_ART_URI) — vertikal. */
    val posterUri: String? = null,
    /** Thumbnail landscape (COLUMN_THUMBNAIL_URI) — horizontal/backdrop. */
    val thumbnailUri: String? = null,
    val intentUri: String? = null,
    val providerPackageName: String? = null,
    val providerName: String? = null,
    val programType: Int? = null,
    val durationMillis: Long? = null,
    val author: String? = null,
    val interactionCount: Long? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val seasonNumber: String? = null,
    val episodeNumber: String? = null,
    val episodeTitle: String? = null,
) {
    /** Art landscape untuk card kecil Top Picks. TIDAK pakai Backdrop Jellyfin —
     *  poster (Primary) udah di-cache app Jellyfin, jauh lebih cepat load
     *  dibanding Backdrop (resolusi besar, gak di-cache). Di ukuran card kecil,
     *  distorsi poster vertikal dipaksa landscape gak terlalu kentara. */
    val landscapeArtUri: String?
        get() = thumbnailUri?.takeIf { it.isNotBlank() } ?: posterUri

    /** Art landscape khusus hero (background besar). Prioritas Backdrop asli
     *  Jellyfin (baru rewrite dari poster, sekali fetch aja karena cuma 1 hero
     *  aktif) biar gak keliatan crop vertikal di background full-width. */
    val heroArtUri: String?
        get() = thumbnailUri?.takeIf { it.isNotBlank() }
            ?: jellyfinBackdropFromPoster()
            ?: posterUri

    /** Art portrait. Fallback ke thumbnail. */
    val portraitArtUri: String?
        get() = posterUri?.takeIf { it.isNotBlank() } ?: thumbnailUri

    /**
     * Jellyfin Android TV app publish poster (Primary) ke channel, bukan Backdrop.
     * posterUri bukan URL http langsung, tapi dibungkus content:// milik app
     * Jellyfin sendiri (ImageProvider), URL asli ada di query param "src"
     * (URL-encoded). Contoh:
     * content://org.jellyfin.androidtv.integration.provider.ImageProvider
     *   ?src=http%3A%2F%2Fhost%3A8096%2FItems%2F{id}%2FImages%2FPrimary%3Ftag%3D..
     *   &v=0.19.10
     *
     * Decode src, ganti path Images/Primary -> Images/Backdrop/0 (landscape asli
     * dari Jellyfin), encode ulang balik ke bentuk content:// yang sama biar
     * tetap lewat ImageProvider (perlu proxy ini buat auth ke server Jellyfin).
     * Gagal parse / bukan Jellyfin / src gak ada path Primary -> null,
     * fallback ke posterUri mentah seperti sebelumnya (tidak ada regresi).
     */
    private fun jellyfinBackdropFromPoster(): String? {
        val pkg = providerPackageName?.lowercase() ?: return null
        if (!pkg.contains("jellyfin")) return null
        val poster = posterUri?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val outer = Uri.parse(poster)
            val src = outer.getQueryParameter("src") ?: return null
            if (!src.contains("/Images/Primary")) return null
            val newSrc = src.replace("/Images/Primary", "/Images/Backdrop/0")
            val builder = outer.buildUpon().clearQuery()
            for (key in outer.queryParameterNames) {
                val value = if (key == "src") newSrc else outer.getQueryParameter(key)
                builder.appendQueryParameter(key, value)
            }
            builder.build().toString()
        } catch (_: Exception) {
            null
        }
    }
}
