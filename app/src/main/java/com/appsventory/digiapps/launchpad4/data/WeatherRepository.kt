package com.appsventory.digiapps.launchpad4.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.appsventory.digiapps.launchpad4.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Open-Meteo + cache.
 * Lokasi: device → IP → saved → Jakarta.
 * Cache hanya dipakai jika koordinat masih dekat lokasi terbaru (bukan “Jakarta abadi”).
 */
class WeatherRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_FILE, Context.MODE_PRIVATE)

    /** Debug terakhir untuk UI status. */
    @Volatile var lastDebug: String = ""
        private set

    data class Current(
        val tempC: Double,
        val feelsC: Double,
        val humidity: Int,
        val weatherCode: Int,
        val windKmh: Double,
        val windDir: Int,
        val precipMm: Double,
        val isDay: Boolean,
        val timeIso: String
    )

    data class Hourly(
        val timeIso: String,
        val tempC: Double,
        val precipProb: Int,
        val weatherCode: Int
    )

    data class Daily(
        val dateIso: String,
        val weatherCode: Int,
        val tempMax: Double,
        val tempMin: Double,
        val precipSum: Double,
        val precipProbMax: Int,
        val sunrise: String,
        val sunset: String
    )

    data class Snapshot(
        val cityLabel: String,
        val latitude: Double,
        val longitude: Double,
        val current: Current,
        val hourly: List<Hourly>,
        val daily: List<Daily>,
        val fetchedAtMs: Long,
        val fromCache: Boolean,
        val locationSource: String = ""
    )

    data class ResolvedLocation(
        val city: String,
        val lat: Double,
        val lon: Double,
        val source: String
    )

    fun readCached(): Snapshot? {
        val raw = prefs.getString(CACHE_JSON, null) ?: return null
        val at = prefs.getLong(CACHE_AT, 0L)
        if (raw.isBlank() || at <= 0L) return null
        return try {
            parseSnapshot(JSONObject(raw), at, fromCache = true)
        } catch (_: Exception) {
            null
        }
    }

    fun isCacheFresh(maxAgeMs: Long = DEFAULT_TTL_MS): Boolean {
        val at = prefs.getLong(CACHE_AT, 0L)
        return at > 0L && (System.currentTimeMillis() - at) < maxAgeMs
    }

    fun getWeather(forceRefresh: Boolean = false): Snapshot {
        val loc = resolveLocation()
        lastDebug = "loc=${loc.source} ${loc.city} (${"%.2f".format(loc.lat)}, ${"%.2f".format(loc.lon)})"

        val cached = readCached()
        if (!forceRefresh && cached != null && isCacheFresh()) {
            val samePlace =
                abs(cached.latitude - loc.lat) < 0.45 &&
                    abs(cached.longitude - loc.lon) < 0.45
            if (samePlace) {
                lastDebug += " | cache HIT"
                return cached.copy(
                    cityLabel = loc.city.ifBlank { cached.cityLabel },
                    locationSource = loc.source
                )
            }
            lastDebug += " | cache STALE place"
        } else if (cached == null) {
            lastDebug += " | no cache"
        } else {
            lastDebug += " | cache expired"
        }

        return try {
            val json = fetchOpenMeteo(loc.lat, loc.lon)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString(CACHE_JSON, json.toString())
                .putLong(CACHE_AT, now)
                .putString(CITY, loc.city)
                .putFloat(LAT, loc.lat.toFloat())
                .putFloat(LON, loc.lon.toFloat())
                .putString(SOURCE, loc.source)
                .apply()
            lastDebug += " | API ok"
            parseSnapshot(json, now, fromCache = false)
                .copy(
                    cityLabel = loc.city,
                    latitude = loc.lat,
                    longitude = loc.lon,
                    locationSource = loc.source
                )
        } catch (e: Exception) {
            lastDebug += " | API fail: ${e.message}"
            cached?.copy(locationSource = loc.source)
                ?: throw e
        }
    }

    fun resolveLocation(): ResolvedLocation {
        deviceLocation()?.let {
            lastDebug = "device ok"
            return it
        }
        ipLocation()?.let {
            lastDebug = "ip ok"
            return it
        }
        val savedCity = prefs.getString(CITY, null)
        val savedLat = prefs.getFloat(LAT, Float.NaN)
        val savedLon = prefs.getFloat(LON, Float.NaN)
        if (savedCity != null && !savedLat.isNaN() && !savedLon.isNaN() &&
            // Jangan anggap default Jakarta sebagai “saved real”
            !(abs(savedLat - DEFAULT_LAT.toFloat()) < 0.01f && abs(savedLon - DEFAULT_LON.toFloat()) < 0.01f)
        ) {
            return ResolvedLocation(savedCity, savedLat.toDouble(), savedLon.toDouble(), "saved")
        }
        return ResolvedLocation(DEFAULT_CITY, DEFAULT_LAT, DEFAULT_LON, "default")
    }

    @SuppressLint("MissingPermission")
    private fun deviceLocation(): ResolvedLocation? {
        if (!hasLocationPermission()) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                } catch (_: Exception) {
                    continue
                }
                val loc = try {
                    lm.getLastKnownLocation(p)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            val loc = best ?: return null
            if (System.currentTimeMillis() - loc.time > TimeUnit.HOURS.toMillis(24)) return null
            // Fungsi ini selalu dipanggil dari background thread (WeatherController.refresh),
            // jadi reverse-geo aman di sini — ada timeout + fallback kalau gagal/hang.
            val city = reverseGeocodeRough(loc.latitude, loc.longitude) ?: "Lokasi perangkat"
            ResolvedLocation(city, loc.latitude, loc.longitude, "device")
        } catch (_: Exception) {
            null
        }
    }

    private fun hasLocationPermission(): Boolean {
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return coarse == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED
    }

    /**
     * IP geo — coba HTTPS dulu, fallback HTTP.
     * ip-api.com HTTP / ipapi.co HTTPS
     */
    private fun ipLocation(): ResolvedLocation? {
        // 1) ip-api.com (user sudah proven success)
        try {
            val body = httpGet(
                URL("http://ip-api.com/json/?fields=status,message,city,regionName,lat,lon,country"),
                connectMs = 5000,
                readMs = 5000
            )
            val json = JSONObject(body)
            if (json.optString("status") == "success") {
                val city = listOfNotNull(
                    json.optString("city").ifBlank { null },
                    json.optString("regionName").ifBlank { null }
                ).joinToString(", ").ifBlank { json.optString("country", "IP") }
                return ResolvedLocation(city, json.getDouble("lat"), json.getDouble("lon"), "ip")
            }
        } catch (_: Exception) { }

        // 2) ipapi.co HTTPS fallback
        try {
            val body = httpGet(URL("https://ipapi.co/json/"), connectMs = 5000, readMs = 5000)
            val json = JSONObject(body)
            if (json.has("latitude") && json.has("longitude")) {
                val city = listOfNotNull(
                    json.optString("city").ifBlank { null },
                    json.optString("region").ifBlank { null }
                ).joinToString(", ").ifBlank { json.optString("country_name", "IP") }
                return ResolvedLocation(
                    city,
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    "ip2"
                )
            }
        } catch (_: Exception) { }

        return null
    }

    private fun reverseGeocodeRough(lat: Double, lon: Double): String? {
        return try {
            val body = httpGet(
                URL(
                    "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                        "?latitude=$lat&longitude=$lon&localityLanguage=id"
                ),
                connectMs = 3000,
                readMs = 3000
            )
            val json = JSONObject(body)
            listOfNotNull(
                json.optString("city").ifBlank { null },
                json.optString("locality").ifBlank { null },
                json.optString("principalSubdivision").ifBlank { null }
            ).distinct().take(2).joinToString(", ").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOpenMeteo(lat: Double, lon: Double): JSONObject {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m,wind_direction_10m,precipitation,is_day" +
                "&hourly=temperature_2m,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,precipitation_probability_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=7&wind_speed_unit=kmh"
        )
        val body = httpGet(url, connectMs = 6000, readMs = 8000)
        return JSONObject(body)
    }

    private fun httpGet(url: URL, connectMs: Int, readMs: Int): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DigiHome/1.0")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val brief = body.take(120).replace("\\s+".toRegex(), " ")
                throw IllegalStateException("HTTP $code ${url.host}: $brief")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSnapshot(root: JSONObject, fetchedAtMs: Long, fromCache: Boolean): Snapshot {
        val cur = root.getJSONObject("current")
        val current = Current(
            tempC = cur.optDouble("temperature_2m", 0.0),
            feelsC = cur.optDouble("apparent_temperature", 0.0),
            humidity = cur.optInt("relative_humidity_2m", 0),
            weatherCode = cur.optInt("weather_code", 0),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            windDir = cur.optInt("wind_direction_10m", 0),
            precipMm = cur.optDouble("precipitation", 0.0),
            isDay = cur.optInt("is_day", 1) == 1,
            timeIso = cur.optString("time", "")
        )

        val hourlyObj = root.optJSONObject("hourly")
        val hourly = mutableListOf<Hourly>()
        if (hourlyObj != null) {
            val times = hourlyObj.optJSONArray("time") ?: JSONArray()
            val temps = hourlyObj.optJSONArray("temperature_2m") ?: JSONArray()
            val probs = hourlyObj.optJSONArray("precipitation_probability") ?: JSONArray()
            val codes = hourlyObj.optJSONArray("weather_code") ?: JSONArray()
            val n = minOf(times.length(), 24)
            for (i in 0 until n) {
                hourly.add(
                    Hourly(
                        timeIso = times.optString(i),
                        tempC = temps.optDouble(i, 0.0),
                        precipProb = probs.optInt(i, 0),
                        weatherCode = codes.optInt(i, 0)
                    )
                )
            }
        }

        val dailyObj = root.optJSONObject("daily")
        val daily = mutableListOf<Daily>()
        if (dailyObj != null) {
            val dates = dailyObj.optJSONArray("time") ?: JSONArray()
            val codes = dailyObj.optJSONArray("weather_code") ?: JSONArray()
            val maxs = dailyObj.optJSONArray("temperature_2m_max") ?: JSONArray()
            val mins = dailyObj.optJSONArray("temperature_2m_min") ?: JSONArray()
            val precip = dailyObj.optJSONArray("precipitation_sum") ?: JSONArray()
            val probs = dailyObj.optJSONArray("precipitation_probability_max") ?: JSONArray()
            val sunrise = dailyObj.optJSONArray("sunrise") ?: JSONArray()
            val sunset = dailyObj.optJSONArray("sunset") ?: JSONArray()
            for (i in 0 until dates.length()) {
                daily.add(
                    Daily(
                        dateIso = dates.optString(i),
                        weatherCode = codes.optInt(i, 0),
                        tempMax = maxs.optDouble(i, 0.0),
                        tempMin = mins.optDouble(i, 0.0),
                        precipSum = precip.optDouble(i, 0.0),
                        precipProbMax = probs.optInt(i, 0),
                        sunrise = sunrise.optString(i),
                        sunset = sunset.optString(i)
                    )
                )
            }
        }

        val city = prefs.getString(CITY, DEFAULT_CITY) ?: DEFAULT_CITY
        val lat = prefs.getFloat(LAT, DEFAULT_LAT.toFloat()).toDouble()
        val lon = prefs.getFloat(LON, DEFAULT_LON.toFloat()).toDouble()
        val source = prefs.getString(SOURCE, "") ?: ""
        return Snapshot(
            cityLabel = city,
            latitude = lat,
            longitude = lon,
            current = current,
            hourly = hourly,
            daily = daily,
            fetchedAtMs = fetchedAtMs,
            fromCache = fromCache,
            locationSource = source
        )
    }

    /** Hapus cache (debug / ganti lokasi). */
    fun clearCache() {
        prefs.edit().remove(CACHE_JSON).remove(CACHE_AT).apply()
    }

    companion object {
        private const val CACHE_JSON = "weather_cache_json"
        private const val CACHE_AT = "weather_cache_at"
        private const val CITY = "weather_city"
        private const val LAT = "weather_lat"
        private const val LON = "weather_lon"
        private const val SOURCE = "weather_source"

        const val DEFAULT_CITY = "Jakarta"
        const val DEFAULT_LAT = -6.2088
        const val DEFAULT_LON = 106.8456
        val DEFAULT_TTL_MS: Long = TimeUnit.MINUTES.toMillis(45)

        fun weatherEmoji(code: Int, isDay: Boolean = true): String = when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57 -> "🌦️"
            61, 63, 65, 66, 67 -> "🌧️"
            71, 73, 75, 77 -> "❄️"
            80, 81, 82 -> "🌧️"
            85, 86 -> "🌨️"
            95, 96, 99 -> "⛈️"
            else -> "⛅"
        }

        /**
         * Versi icon XML (bukan emoji) dari weatherEmoji() — dipakai kartu "NOW" di
         * TODAY'S DETAILS supaya beneran dinamis per kode cuaca (bukan gambar statis
         * ic_weather_now yang lama). Gaya sama dengan ic_weather_rain/sunrise/wind
         * yang udah ada (stroke putih, viewport 28x28).
         */
        fun weatherIconRes(code: Int, isDay: Boolean = true): Int = when (code) {
            0 -> if (isDay) R.drawable.ic_weather_clear_day else R.drawable.ic_weather_clear_night
            1, 2 -> if (isDay) R.drawable.ic_weather_partly_cloudy_day else R.drawable.ic_weather_partly_cloudy_night
            3 -> R.drawable.ic_weather_cloudy
            45, 48 -> R.drawable.ic_weather_fog
            51, 53, 55, 56, 57 -> R.drawable.ic_weather_drizzle
            61, 63, 65, 66, 67 -> R.drawable.ic_weather_rain
            71, 73, 75, 77 -> R.drawable.ic_weather_snow
            80, 81, 82 -> R.drawable.ic_weather_rain
            85, 86 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_thunderstorm
            else -> R.drawable.ic_weather_cloudy
        }

        /** Label sumber lokasi buat status text (kanan) — beda dari nama kota (kiri). */
        fun sourceLabel(source: String): String = when (source) {
            "device" -> "Lokasi perangkat"
            "ip", "ip2" -> "Lokasi (IP)"
            "saved" -> "Tersimpan"
            "default" -> "Default"
            else -> "Lokasi perangkat"
        }

        fun windDirLabel(deg: Int): String {
            val dirs = listOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")
            val idx = Math.floorMod(Math.round(deg / 45.0).toInt(), 8)
            return dirs[idx]
        }

        /** Versi Inggris windDirLabel() — dipakai layar Weather (universal, non-lokal). */
        fun windDirLabelEn(deg: Int): String {
            val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val idx = Math.floorMod(Math.round(deg / 45.0).toInt(), 8)
            return dirs[idx]
        }

        fun weatherLabel(code: Int): String = when (code) {
            0 -> "Cerah"
            1 -> "Sebagian cerah"
            2 -> "Berawan tipis"
            3 -> "Berawan"
            45, 48 -> "Berkabut"
            51, 53, 55 -> "Gerimis"
            61, 63, 65 -> "Hujan"
            71, 73, 75 -> "Salju"
            80, 81, 82 -> "Hujan lokal"
            95, 96, 99 -> "Badai petir"
            else -> "Cuaca"
        }

        /** Versi Inggris weatherLabel() — dipakai widget Home (universal, non-lokal). */
        fun weatherLabelEn(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            80, 81, 82 -> "Rain showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Weather"
        }

        /** Versi Inggris sourceLabel() — dipakai widget Home (universal, non-lokal). */
        fun sourceLabelEn(source: String): String = when (source) {
            "device" -> "Location (GPS)"
            "ip", "ip2" -> "Location (IP)"
            "saved" -> "Location (Saved)"
            "default" -> "Location (Default)"
            else -> "Location (GPS)"
        }
    }
}
