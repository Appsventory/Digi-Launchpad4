package com.appsventory.digiapps.launchpad4.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import androidx.tvprovider.media.tv.TvContractCompat
import com.appsventory.digiapps.launchpad4.model.Recommendation
import com.appsventory.digiapps.launchpad4.model.TvChannelInfo

class TvChannelRepository(private val appContext: Context) {
    private val resolver get() = appContext.contentResolver
    private fun getApplicationName(packageName: String): String {
        return try {
            val appInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun listChannels(): List<TvChannelInfo> {
        val result = mutableListOf<TvChannelInfo>()
        val projection = arrayOf(
            TvContractCompat.Channels._ID,
            TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
            TvContractCompat.Channels.COLUMN_PACKAGE_NAME,
            TvContractCompat.Channels.COLUMN_TYPE,
        )
        try {
            resolver.query(TvContractCompat.Channels.CONTENT_URI, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val nameIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_DISPLAY_NAME)
                val pkgIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_PACKAGE_NAME)
                val typeIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_TYPE)
                while (c.moveToNext()) {
                    val type = if (typeIdx >= 0) c.getString(typeIdx) else null
                    if (type != null &&
                        type != TvContractCompat.Channels.TYPE_PREVIEW &&
                        type.isNotBlank()
                    ) continue
                    if (idIdx < 0 || pkgIdx < 0) continue
                    val pkg = c.getString(pkgIdx) ?: continue
                    if (pkg == appContext.packageName) continue
                    result.add(
                        TvChannelInfo(
                            id = c.getLong(idIdx),
                            displayName = (if (nameIdx >= 0) c.getString(nameIdx) else null) ?: pkg,
                            packageName = pkg,
                            providerName = getApplicationName(pkg)
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission belum granted
        } catch (_: Exception) {
            // Device tidak dukung TvProvider
        }

        // Prefer ID terbesar (biasanya channel yang paling baru di-publish)
        return result
            .groupBy { it.packageName to it.displayName.trim().lowercase() }
            .map { (_, group) -> group.maxByOrNull { it.id } ?: group.first() }
            .sortedWith(compareBy({ it.packageName }, { it.displayName.lowercase() }))
    }

    /**
     * Ambil program dari channel yang ID-nya ada di [enabledChannelIds].
     * Single-select: kalau set kosong, kembalikan list kosong (user belum pilih channel).
     */
    fun fetchRecommendations(enabledChannelIds: Set<Long>, limitPerChannel: Int = 10): List<Recommendation> {
        if (enabledChannelIds.isEmpty()) return emptyList()
        val channels = listChannels()
        val targets = channels.filter { it.id in enabledChannelIds }
        val recs = mutableListOf<Recommendation>()
        for (channel in targets) {
            recs += fetchProgramsForChannel(channel, limitPerChannel)
        }
        return recs.distinctBy { it.title to (it.posterUri ?: it.intentUri) }
    }

    private fun fetchProgramsForChannel(channel: TvChannelInfo, limit: Int): List<Recommendation> {
        val result = mutableListOf<Recommendation>()
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channel.id)

        // String literal = kontrak publik ContentProvider (hindari @RestrictTo LIBRARY_GROUP)
        val projection = arrayOf(
            COL_TITLE,
            COL_SHORT_DESCRIPTION,
            COL_POSTER_ART_URI,
            COL_THUMBNAIL_URI,
            COL_INTENT_URI,
            COL_TYPE,
            COL_DURATION_MILLIS,
            COL_AUTHOR,
            COL_INTERACTION_COUNT,
            COL_RELEASE_DATE,
            COL_GENRE,
            COL_SEASON_DISPLAY_NUMBER,
            COL_EPISODE_DISPLAY_NUMBER,
            COL_EPISODE_TITLE,
        )

        try {
            resolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
                val titleIdx = c.getColumnIndex(COL_TITLE)
                val descIdx = c.getColumnIndex(COL_SHORT_DESCRIPTION)
                val posterIdx = c.getColumnIndex(COL_POSTER_ART_URI)
                val thumbIdx = c.getColumnIndex(COL_THUMBNAIL_URI)
                val intentIdx = c.getColumnIndex(COL_INTENT_URI)
                val typeIdx = c.getColumnIndex(COL_TYPE)
                val durationIdx = c.getColumnIndex(COL_DURATION_MILLIS)
                val authorIdx = c.getColumnIndex(COL_AUTHOR)
                val interactionIdx = c.getColumnIndex(COL_INTERACTION_COUNT)
                val releaseIdx = c.getColumnIndex(COL_RELEASE_DATE)
                val genreIdx = c.getColumnIndex(COL_GENRE)
                val seasonIdx = c.getColumnIndex(COL_SEASON_DISPLAY_NUMBER)
                val episodeIdx = c.getColumnIndex(COL_EPISODE_DISPLAY_NUMBER)
                val episodeTitleIdx = c.getColumnIndex(COL_EPISODE_TITLE)

                // Sebagian provider nyisain row lama pas republish (channel/program
                // diinsert ulang tanpa bersihin yg lama) -> konten sama muncul >1x.
                // Dedup by title+poster (fallback title+intent kalau poster kosong)
                // biar konten yg sama gak keitung 2x dari limit "10 per channel".
                val seenKeys = HashSet<Pair<String, String?>>()

                var count = 0
                while (c.moveToNext() && count < limit) {
                    val title = (if (titleIdx >= 0) c.getString(titleIdx) else null) ?: continue
                    val poster = if (posterIdx >= 0) c.getString(posterIdx) else null
                    val thumbnail = if (thumbIdx >= 0) c.getString(thumbIdx) else null
                    val intentUriStr = if (intentIdx >= 0) c.getString(intentIdx) else null
                    // Dedup: landscape dulu (thumbnail), lalu poster
                    val dedupKey = title to (thumbnail ?: poster ?: intentUriStr)
                    if (!seenKeys.add(dedupKey)) continue

                    val rec = Recommendation(
                        title = title,
                        topPicksTitle = channel.displayName,
                        description = (if (descIdx >= 0) c.getString(descIdx) else null).orEmpty(),
                        posterUri = poster,
                        thumbnailUri = thumbnail,
                        intentUri = intentUriStr,
                        providerPackageName = channel.packageName,
                        providerName = channel.providerName,
                        programType = if (typeIdx >= 0 && !c.isNull(typeIdx)) c.getInt(typeIdx) else null,
                        durationMillis = if (durationIdx >= 0 && !c.isNull(durationIdx)) c.getLong(durationIdx) else null,
                        author = if (authorIdx >= 0) c.getString(authorIdx) else null,
                        interactionCount = if (interactionIdx >= 0 && !c.isNull(interactionIdx)) c.getLong(interactionIdx) else null,
                        releaseDate = if (releaseIdx >= 0) c.getString(releaseIdx) else null,
                        genre = if (genreIdx >= 0) c.getString(genreIdx) else null,
                        seasonNumber = if (seasonIdx >= 0) c.getString(seasonIdx) else null,
                        episodeNumber = if (episodeIdx >= 0) c.getString(episodeIdx) else null,
                        episodeTitle = if (episodeTitleIdx >= 0) c.getString(episodeTitleIdx) else null,
                    )


                    result.add(rec)
                    count++
                }
            }
        } catch (_: Exception) { /* channel gagal dibaca */ }
        return result
    }

    /** Bangun Intent untuk deep-link dari [Recommendation.intentUri]. Null kalau tidak valid. */
    fun intentFor(recommendation: Recommendation): Intent? {
        val uriStr = recommendation.intentUri ?: return null
        return try {
            Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val COL_TITLE = "title"
        private const val COL_SHORT_DESCRIPTION = "short_description"
        private const val COL_POSTER_ART_URI = "poster_art_uri"
        private const val COL_THUMBNAIL_URI = "thumbnail_uri"
        private const val COL_INTENT_URI = "intent_uri"
        private const val COL_TYPE = "type"
        private const val COL_DURATION_MILLIS = "duration_millis"
        private const val COL_AUTHOR = "author"
        private const val COL_INTERACTION_COUNT = "interaction_count"
        private const val COL_RELEASE_DATE = "release_date"
        private const val COL_GENRE = "genre"
        private const val COL_SEASON_DISPLAY_NUMBER = "season_display_number"
        private const val COL_EPISODE_DISPLAY_NUMBER = "episode_display_number"
        private const val COL_EPISODE_TITLE = "episode_title"
    }
}