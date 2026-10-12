package com.appsventory.digiapps.launchpad4.ui.weather

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Weather UI. Thread murni (bukan shared executor).
 * Status + Toast selalu tampilkan error supaya debug mudah.
 * Semua teks layar ini bahasa Inggris (universal) — pakai varian *En() dari WeatherRepository.
 */
class WeatherController(private val activity: MainActivity) {

    private val repo = WeatherRepository(activity)
    private val density get() = activity.resources.displayMetrics.density
    private val loading = AtomicBoolean(false)
    private var loadedOnce = false

    fun onWeatherTabVisible() {
        loading.set(false) // reset kalau activity re-entry

        // Selalu mulai dari atas — kalau scrollY tersimpan dari kunjungan sebelumnya,
        // NEXT 24 HOURS bisa "nyembul" di bawah meski spacer sudah benar.
        activity.findViewById<View>(R.id.weatherContent)?.scrollTo(0, 0)

        val cached = try {
            repo.readCached()
        } catch (e: Exception) {
            Log.e(TAG, "cache", e)
            null
        }

        if (cached != null) {
            bind(cached)
            loadedOnce = true
            setStatus("Cache OK · ${WeatherRepository.sourceLabelEn(cached.locationSource)}")
        } else {
            activity.findViewById<TextView>(R.id.weatherCityText)?.text = "…"
            setStatus("Fetching…")
        }

        val needRelocate = cached != null &&
                kotlin.math.abs(cached.latitude - WeatherRepository.DEFAULT_LAT) < 0.5 &&
                kotlin.math.abs(cached.longitude - WeatherRepository.DEFAULT_LON) < 0.5

        if (loadedOnce && repo.isCacheFresh() && !needRelocate) return

        refresh(force = needRelocate || cached == null)
    }

    fun refresh(force: Boolean) {
        if (!loading.compareAndSet(false, true)) {
            setStatus("Busy… ${repo.lastDebug}")
            return
        }

        setStatus("1/3 location…")

        // Thread murni — tidak antri di ioExecutor app
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                activity.runOnUiThread { setStatus("2/3 Open-Meteo…") }

                val snap = repo.getWeather(forceRefresh = force)
                val ms = System.currentTimeMillis() - t0

                activity.runOnUiThread {
                    bind(snap)
                    loadedOnce = true
                    loading.set(false)
                    val src = if (snap.fromCache) "cache" else "live"
                    setStatus("OK $src ${ms}ms · ${WeatherRepository.sourceLabelEn(snap.locationSource)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "weather fail", e)
                val ms = System.currentTimeMillis() - t0
                val msg = (e.message ?: e.javaClass.simpleName).take(80)
                activity.runOnUiThread {
                    loading.set(false)
                    setStatus("FAILED ${ms}ms · $msg")
                    activity.findViewById<TextView>(R.id.weatherCityText)?.text = "Error"
                    activity.findViewById<TextView>(R.id.weatherConditionText)?.text = msg
                    Toast.makeText(activity, "Weather: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }, "weather-fetch").start()
    }

    private fun setStatus(text: String) {
        activity.findViewById<TextView>(R.id.weatherStatusText)?.text = text
        Log.i(TAG, text)
    }

    /** "Tue, Sep 16, 05:01pm" — dipakai di baris lokasi, ikut jam device saat data di-bind. */
    private fun nowLabel(): String {
        val now = Date()
        val head = SimpleDateFormat("EEE, MMM d, hh:mm", Locale.ENGLISH).format(now)
        val ampm = SimpleDateFormat("a", Locale.ENGLISH).format(now).lowercase(Locale.ENGLISH)
        return "$head$ampm"
    }

    private fun bind(snap: WeatherRepository.Snapshot) {
        val cur = snap.current
        WeatherBackground.apply(activity, cur.weatherCode)

        activity.findViewById<TextView>(R.id.weatherCityText)?.text =
            "${snap.cityLabel}, ${WeatherRepository.sourceLabelEn(snap.locationSource)} / ${nowLabel()}"

        val conditionEn = WeatherRepository.weatherLabelEn(cur.weatherCode)

        activity.findViewById<TextView>(R.id.weatherTempText)?.text =
            "${cur.tempC.toInt()}°C"
        activity.findViewById<TextView>(R.id.weatherConditionText)?.text = conditionEn
        activity.findViewById<TextView>(R.id.weatherFeelsText)?.text =
            "Feels like ${cur.feelsC.toInt()}°"
        activity.findViewById<TextView>(R.id.weatherHumidityText)?.text =
            "${cur.humidity}%"
        activity.findViewById<TextView>(R.id.weatherWindText)?.text =
            "${cur.windKmh.toInt()} km/h"
        activity.findViewById<TextView>(R.id.weatherPrecipText)?.text =
            if (cur.precipMm > 0.05) String.format(Locale.US, "%.1f mm", cur.precipMm) else "—"

        // Card ke-5 di "TODAY'S DETAILS": status/kondisi sekarang — icon dinamis per
        // kode cuaca (dulu selalu ic_weather_now statis, sekarang ikut cur.weatherCode).
        activity.findViewById<ImageView>(R.id.weatherDetailNowIcon)
            ?.setImageResource(WeatherRepository.weatherIconRes(cur.weatherCode, cur.isDay))
        activity.findViewById<TextView>(R.id.weatherDetailNowText)?.text = conditionEn

        val today = snap.daily.firstOrNull()
        activity.findViewById<TextView>(R.id.weatherSunriseText)?.text =
            today?.sunrise?.takeLast(5) ?: "--:--"
        activity.findViewById<TextView>(R.id.weatherSunsetText)?.text =
            today?.sunset?.takeLast(5) ?: "--:--"
        activity.findViewById<TextView>(R.id.weatherTodayRainText)?.text =
            today?.let {
                if (it.precipSum > 0.05) String.format(Locale.US, "%.1f mm", it.precipSum) else "0 mm"
            } ?: "--"
        activity.findViewById<TextView>(R.id.weatherWindDirText)?.text =
            WeatherRepository.windDirLabelEn(cur.windDir)
        // Putar icon kompas sesuai derajat angin sekarang (0°=utara, searah jarum jam).
        activity.findViewById<ImageView>(R.id.weatherWindDirIcon)?.rotation = cur.windDir.toFloat()

        val hourlyViews = bindHourly(snap.hourly, cur.isDay)
        val dailyViews = bindDaily(snap.daily)
        wireVerticalFocus(hourlyViews, dailyViews)
        adjustPage1Centering()
    }

    /**
     * Center blok page1 content (lokasi + suhu + 5 menu) secara vertikal.
     * Status text (Cache OK) di atas TIDAK ikut di-center.
     *
     * Stabil di setiap kunjungan tab:
     * 1) Reset spacer ke 0 + scroll ke atas (supaya ukurannya tidak "ngikut" state lama)
     * 2) Ukur di frame berikutnya (post) setelah layout final
     * 3) Set top/bottom spacer, lalu scroll ke atas lagi
     * Tanpa ini, buka ulang tab bisa dapat tinggi salah → NEXT 24 HOURS nyembul.
     */
    private fun adjustPage1Centering() {
        val scroll = activity.findViewById<View>(R.id.weatherContent) ?: return
        val content = activity.findViewById<View>(R.id.weatherPage1Content) ?: return
        val topSpacer = activity.findViewById<View>(R.id.weatherTopSpacer) ?: return
        val bottomSpacer = activity.findViewById<View>(R.id.weatherBottomSpacer) ?: return
        val status = activity.findViewById<View>(R.id.weatherStatusText)

        fun setSpacerHeight(view: View, height: Int) {
            val lp = view.layoutParams
            if (lp.height != height) {
                lp.height = height
                view.layoutParams = lp
            }
        }

        // Reset dulu — kalau pakai tinggi spacer lama, free-space jadi salah di re-entry.
        setSpacerHeight(topSpacer, 0)
        setSpacerHeight(bottomSpacer, 0)
        scroll.scrollTo(0, 0)

        scroll.post {
            if (scroll.height == 0 || content.height == 0) {
                // Layout belum siap; coba sekali lagi di frame berikutnya.
                scroll.post { applyPage1Centering(scroll, content, topSpacer, bottomSpacer, status) }
                return@post
            }
            applyPage1Centering(scroll, content, topSpacer, bottomSpacer, status)
        }
    }

    private fun applyPage1Centering(
        scroll: View,
        content: View,
        topSpacer: View,
        bottomSpacer: View,
        status: View?
    ) {
        if (scroll.height == 0 || content.height == 0) return

        val statusH = status?.let {
            val marginBottom = (it.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0
            it.height + marginBottom
        } ?: 0

        // clipToPadding=false → hanya paddingTop yang menggeser awal konten.
        val viewportH = (scroll.height - scroll.paddingTop - statusH).coerceAtLeast(0)
        val contentH = content.height
        val free = (viewportH - contentH).coerceAtLeast(0)
        val topH = free / 2
        val bottomH = free - topH

        fun setSpacerHeight(view: View, height: Int) {
            val lp = view.layoutParams
            if (lp.height != height) {
                lp.height = height
                view.layoutParams = lp
            }
        }
        setSpacerHeight(topSpacer, topH)
        setSpacerHeight(bottomSpacer, bottomH)

        // Setelah spacer berubah, layout reflow — pastikan tetap di puncak.
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /**
     * Sambung chain fokus D-pad: hero -> baris jam -> baris hari, dan baliknya.
     * Juga jebak fokus horizontal di ujung tiap baris (kartu pertama/terakhir) —
     * dpad kiri/kanan di ujung diam di tempat, gak dilempar ke layout terdekat.
     */
    private fun wireVerticalFocus(hourlyViews: List<View>, dailyViews: List<View>) {
        val hero = activity.findViewById<View>(R.id.weatherHeroCard) ?: return

        trapHorizontalEdges(hourlyViews)
        trapHorizontalEdges(dailyViews)

        // Naik dari hero harus balik ke tab Weather yang lagi aktif — sebelumnya gak
        // pernah di-set di sini, jadi Android nebak sendiri pakai focus-search bawaan
        // (nyasar ke profileButton/wifi, gak konsisten). weatherContent.nextFocusUpId
        // di MainActivity gak ngaruh karena itu ScrollView, bukan view yang beneran
        // fokus — yang fokus ya hero ini, jadi harus di-set di sini.
        hero.nextFocusUpId = R.id.weatherButton

        // Begitu hero fokus lagi (masuk pertama kali ATAU naik dari baris jam/hari),
        // paksa scroll balik ke atas — biar tampilan balik "full" kayak semula,
        // gak nyangkut ngambang di tengah nunjukin baris jam separuh.
        hero.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activity.findViewById<ScrollView>(R.id.weatherContent)?.smoothScrollTo(0, 0)
            }
        }

        when {
            hourlyViews.isNotEmpty() -> {
                hero.nextFocusDownId = hourlyViews.first().id
                val downTarget = dailyViews.firstOrNull()?.id ?: hourlyViews.first().id
                for (v in hourlyViews) {
                    v.nextFocusUpId = hero.id
                    v.nextFocusDownId = downTarget
                }
            }
            dailyViews.isNotEmpty() -> hero.nextFocusDownId = dailyViews.first().id
            else -> hero.nextFocusDownId = hero.id
        }

        val upTarget = hourlyViews.firstOrNull()?.id ?: hero.id
        for (v in dailyViews) {
            v.nextFocusUpId = upTarget
            v.nextFocusDownId = v.id
        }
    }

    /** Kartu pertama: nextFocusLeft = diri sendiri. Kartu terakhir: nextFocusRight = diri sendiri. */
    private fun trapHorizontalEdges(views: List<View>) {
        if (views.isEmpty()) return
        views.first().nextFocusLeftId = views.first().id
        views.last().nextFocusRightId = views.last().id
    }

    private fun bindHourly(list: List<WeatherRepository.Hourly>, isDay: Boolean): List<View> {
        val row = activity.findViewById<LinearLayout>(R.id.weatherHourlyRow) ?: return emptyList()
        row.removeAllViews()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val views = mutableListOf<View>()
        val lastIndex = list.lastIndex
        for ((index, h) in list.withIndex()) {
            val label = try {
                if (index == 0) "Now" else timeFmt.format(parseFmt.parse(h.timeIso)!!)
            } catch (_: Exception) {
                h.timeIso.takeLast(5)
            }
            val card = chipCard(
                top = label,
                iconRes = WeatherRepository.weatherIconRes(h.weatherCode, isDay),
                bottom = "${h.tempC.toInt()}°",
                sub = if (h.precipProb > 0) "${h.precipProb}%" else null,
                edgePivotX = edgePivotFor(index, lastIndex)
            )
            row.addView(card)
            views.add(card)
        }
        return views
    }

    private fun bindDaily(list: List<WeatherRepository.Daily>): List<View> {
        val row = activity.findViewById<LinearLayout>(R.id.weatherDailyRow) ?: return emptyList()
        row.removeAllViews()
        val dayFmt = SimpleDateFormat("EEE", Locale.ENGLISH)
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val views = mutableListOf<View>()
        val lastIndex = list.lastIndex
        for ((index, d) in list.withIndex()) {
            val label = try {
                if (index == 0) "Today"
                else dayFmt.format(parseFmt.parse(d.dateIso)!!)
            } catch (_: Exception) {
                d.dateIso
            }
            val card = dayCard(
                day = label,
                iconRes = WeatherRepository.weatherIconRes(d.weatherCode, true),
                max = "${d.tempMax.toInt()}°",
                min = "${d.tempMin.toInt()}°",
                rain = if (d.precipProbMax > 0) "${d.precipProbMax}%" else null,
                edgePivotX = edgePivotFor(index, lastIndex)
            )
            row.addView(card)
            views.add(card)
        }
        return views
    }

    /** Kartu pertama scale dari kiri (pivot 0), kartu terakhir dari kanan (pivot 1), sisanya tengah. */
    private fun edgePivotFor(index: Int, lastIndex: Int): Float = when {
        index == 0 -> 0f
        index == lastIndex -> 1f
        else -> 0.5f
    }

    private fun chipCard(top: String, iconRes: Int, bottom: String, sub: String?, edgePivotX: Float): View {
        val pad = (14 * density).toInt()
        val cardH = (140 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, (pad * 1.1f).toInt(), pad, pad)
            background = cardBg(activity.resources.getDimension(R.dimen.style_card_blur_radius))
            val lp = LinearLayout.LayoutParams((92 * density).toInt(), cardH)
            lp.marginEnd = (12 * density).toInt()
            lp.bottomMargin = (16 * density).toInt()
            layoutParams = lp
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
        }
        box.addView(TextView(activity).apply {
            text = top
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 11f
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        })
        box.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            val lp = LinearLayout.LayoutParams((30 * density).toInt(), (30 * density).toInt())
            lp.topMargin = (8 * density).toInt()
            lp.bottomMargin = (6 * density).toInt()
            lp.gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp
        })
        box.addView(TextView(activity).apply {
            text = bottom
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        val badge = rainBadge(sub ?: "0%", (6 * density).toInt())
        if (sub == null) badge.visibility = View.INVISIBLE
        box.addView(badge)
        attachCardFocusAnimation(box, edgePivotX, radiusDp = 20f)
        return box
    }

    private fun dayCard(
        day: String,
        iconRes: Int,
        max: String,
        min: String,
        rain: String?,
        edgePivotX: Float
    ): View {
        val pad = (16 * density).toInt()
        val cardH = (180 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(pad, (pad * 0.9f).toInt(), pad, (pad * 0.9f).toInt())
            background = cardBg(activity.resources.getDimension(R.dimen.style_card_blur_radius))
            val lp = LinearLayout.LayoutParams((118 * density).toInt(), cardH)
            lp.marginEnd = (14 * density).toInt()
            lp.bottomMargin = (24 * density).toInt()
            layoutParams = lp
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
        }
        box.addView(TextView(activity).apply {
            text = day
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
            letterSpacing = 0.02f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            val lp = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            lp.topMargin = (8 * density).toInt()
            lp.bottomMargin = (6 * density).toInt()
            lp.gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp
        })
        // Max/min di satu baris, dipisah garis pendek — gravity center horizontal.
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                text = max
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(View(activity).apply {
                val lp = LinearLayout.LayoutParams((1 * density).toInt(), (14 * density).toInt())
                lp.marginStart = (6 * density).toInt()
                lp.marginEnd = (6 * density).toInt()
                layoutParams = lp
                setBackgroundColor(Color.parseColor("#40FFFFFF"))
            })
            addView(TextView(activity).apply {
                text = min
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 14f
            })
        })
        // Selalu reserved space untuk rain badge — INVISIBLE kalau null biar tinggi seragam.
        val badge = rainBadge(rain ?: "0%", (8 * density).toInt())
        if (rain == null) badge.visibility = View.INVISIBLE
        box.addView(badge)
        attachCardFocusAnimation(box, edgePivotX, radiusDp = 22f)
        return box
    }

    /** Badge kecil biru buat probabilitas hujan — dari sekadar "💧 40%" jadi pill nempel. */
    private fun rainBadge(percent: String, marginTop: Int): View {
        val padH = (8 * density).toInt()
        val padV = (3 * density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor("#80026BFF"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = marginTop
            layoutParams = lp
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_water_drop)
                val ilp = LinearLayout.LayoutParams((11 * density).toInt(), (11 * density).toInt())
                ilp.marginEnd = (3 * density).toInt()
                layoutParams = ilp
            })
            addView(TextView(activity).apply {
                text = percent
                setTextColor(Color.parseColor("#FFE1F3FF"))
                textSize = 10.5f
                letterSpacing = 0.06f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    /**
     * Scale-on-focus + background solid + teks/icon gelap saat fokus
     * (teks putih di bg putih solid tidak terbaca). Unfocus restore frosted + putih.
     */
    private fun attachCardFocusAnimation(view: View, edgePivotX: Float, radiusDp: Float) {
        fun applyPivot() {
            view.pivotX = view.width * edgePivotX
            view.pivotY = view.height / 2f
        }
        view.post { applyPivot() }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPivot() }

        val normalBg = cardBg(radiusDp)
        val focusBg = cardBgFocused(radiusDp)
        view.background = normalBg

        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            if (hasFocus) {
                v.background = focusBg
                applyCardContentColors(v, focused = true)
                v.translationZ = CARD_FOCUS_Z
                v.elevation = CARD_FOCUS_Z
                v.animate()
                    .scaleX(CARD_FOCUS_SCALE)
                    .scaleY(CARD_FOCUS_SCALE)
                    .alpha(1f)
                    .setDuration(CARD_FOCUS_DURATION)
                    .start()
                ensureCardVisibleWithMargin(v)
            } else {
                v.background = normalBg
                applyCardContentColors(v, focused = false)
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(CARD_FOCUS_ALPHA)
                    .setDuration(CARD_FOCUS_DURATION)
                    .start()
                v.translationZ = 0f
                v.elevation = 0f
            }
        }
    }

    /**
     * Pastikan kartu fokus punya ruang di bawah di dalam weatherContent ScrollView.
     * Android hanya scroll sampai bounds layout terlihat — tidak hitung scale 1.15x
     * maupun margin visual. Kita scroll tambahan manual.
     */
    private fun ensureCardVisibleWithMargin(card: View) {
        val scroll = activity.findViewById<ScrollView>(R.id.weatherContent) ?: return
        scroll.post {
            val cardLoc = IntArray(2)
            val scrollLoc = IntArray(2)
            card.getLocationOnScreen(cardLoc)
            scroll.getLocationOnScreen(scrollLoc)
            val cardBottom = cardLoc[1] + card.height
            val scrollBottom = scrollLoc[1] + scroll.height
            // Scale 1.15 dari pivot tengah → tumbuh ~7.5% ke bawah + margin visual 28dp
            val scaleExtra = (card.height * (CARD_FOCUS_SCALE - 1f) / 2f).toInt()
            val margin = (28 * density).toInt()
            val need = cardBottom + scaleExtra + margin - scrollBottom
            if (need > 0) {
                scroll.smoothScrollBy(0, need)
            }
        }
    }

    /** Rekursif: TextView + ImageView di dalam kartu → gelap saat fokus, putih saat normal. */
    private fun applyCardContentColors(root: View, focused: Boolean) {
        val textPrimary = if (focused) Color.parseColor("#90000000") else Color.WHITE
        val textSecondary = if (focused) Color.parseColor("#80000000") else Color.parseColor("#B3FFFFFF")
        val textMuted = if (focused) Color.parseColor("#40000000") else Color.parseColor("#99FFFFFF")
        val iconTint = if (focused) Color.parseColor("#111112") else Color.WHITE

        fun walk(v: View) {
            when (v) {
                is TextView -> {
                    val t = v.text?.toString().orEmpty()
                    // Label hari/jam (Today, Sun, 01:00) → secondary; max bold → primary; min → muted
                    val size = v.textSize / v.resources.displayMetrics.scaledDensity
                    val color = when {
                        size >= 15.5f -> textPrimary   // max temp / main temp
                        size >= 13.5f -> textMuted     // min temp
                        size >= 11.5f -> textSecondary // day/hour label ~12sp
                        else -> textSecondary         // small labels
                    }
                    v.setTextColor(color)
                }
                is ImageView -> {
                    v.colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                }
                is android.view.ViewGroup -> {
                    // Rain badge: keep blue-ish pill, only tint its children via walk
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
    }

    private fun cardBg(radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(
            ContextCompat.getColor(
                activity,
                R.color.style_card_blur
            )
        )
        cornerRadius = radiusDp
        setStroke((1 * density).toInt(),
            ContextCompat.getColor(activity, R.color.style_card_blur_stroke)
        )
    }

    /** Background solid saat fokus — lebih kontras di atas background ilustrasi. */
    private fun cardBgFocused(radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(
            ContextCompat.getColor(activity, R.color.style_card_blur_hover)
        )
        cornerRadius = radiusDp
        setStroke((1 * density).toInt(),
            ContextCompat.getColor(activity, R.color.style_card_blur_stroke_hover)
        )
    }

    companion object {
        private const val TAG = "WeatherController"

        // Sama dengan konstanta SHELF_FOCUS_* di MainActivity (kartu carousel/shelf halaman lain).
        private const val CARD_FOCUS_SCALE = 1.15f
        private const val CARD_FOCUS_DURATION = 120L
        private const val CARD_FOCUS_ALPHA = 0.92f
        private const val CARD_FOCUS_Z = 100f
    }
}
