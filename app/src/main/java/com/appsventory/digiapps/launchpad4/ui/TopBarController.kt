package com.appsventory.digiapps.launchpad4.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.appsventory.digiapps.launchpad4.MainActivity
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.ui.search.SearchResultsController

/**
 * Shell top bar: Profile ↔ Navigation ↔ Shortcuts, plus Search activation.
 *
 * Focus model (centralized):
 * - Ordinary nav items (Home/Apps/Files/Weather): focus → showTab immediately.
 * - Search: focus only highlights the icon; OK expands input + floating panel + IME.
 * - Back while Search active: (1) dismiss IME  (2) clear query, collapse, restore Shell focus.
 *
 * Search visibility, query text, keyboard, and result focus are independent states
 * coordinated only through [SearchState].
 */
class TopBarController(private val activity: MainActivity) {

    private enum class SearchState {
        /** Icon only — part of normal Shell nav. */
        IDLE,
        /** Bar expanded, panel visible, IME hidden. */
        EXPANDED,
        /** Input focused, IME visible. */
        KEYBOARD
    }

    private var searchState = SearchState.IDLE
    private val searchResults by lazy {
        SearchResultsController(activity).also { ctrl ->
            ctrl.onRequestClose = { closeSearchFully(clearQuery = true) }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var imeVisible = false
    /** When true, TextWatcher ignores changes (used during close/clear). */
    private var suppressQueryCallback = false
    private val hiddenTabs = mutableListOf<View>()

    private val focusTextDark by lazy { activity.getColor(R.color.focus_text_dark) }
    private val white by lazy { activity.getColor(R.color.white) }

    private val imeLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = activity.window?.decorView ?: return@OnGlobalLayoutListener
        val r = Rect()
        root.getWindowVisibleDisplayFrame(r)
        val screenH = root.height
        val keypadH = screenH - r.bottom
        val nowVisible = keypadH > screenH * 0.15
        if (imeVisible && !nowVisible && searchState == SearchState.KEYBOARD) {
            // System consumed BACK to hide IME — move to EXPANDED without clearing query
            enterExpandedFromKeyboard()
        }
        imeVisible = nowVisible
    }

    fun setupTopBarActions() {
        val profileButton = activity.findViewById<View>(R.id.profileButton)
        val searchButton = activity.findViewById<ImageView>(R.id.searchButton)
        val searchOverlay = activity.findViewById<LinearLayout>(R.id.searchOverlay)
        val searchOverlayIcon = activity.findViewById<ImageView>(R.id.searchOverlayIcon)
        val searchInput = activity.findViewById<EditText>(R.id.searchInput)
        val homeIcon = activity.findViewById<ImageView>(R.id.homeIcon)
        val settingsButton = activity.findViewById<ImageView>(R.id.settingsButton)

        activity.window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(imeLayoutListener)

        fun tint(icon: ImageView) {
            icon.setOnFocusChangeListener { _, hasFocus ->
                icon.setColorFilter(if (hasFocus) focusTextDark else white)
            }
        }
        tint(homeIcon)
        tint(settingsButton)
        tint(activity.networkIndicator)
        searchOverlayIcon?.setColorFilter(white)
        searchButton.setColorFilter(white)

        // ── Shell focus chain: Profile ↔ Nav ↔ Shortcuts ──
        wireShellFocusChain()

        // ── Ordinary nav tabs: focus selects page immediately ──
        fun bindTab(btn: View, tab: MainActivity.Tab, label: TextView) {
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Leaving Search icon toward another tab while Search is open → close Search
                    if (searchState != SearchState.IDLE) {
                        closeSearchFully(clearQuery = true)
                    }
                    activity.showTab(tab)
                    label.setTextColor(focusTextDark)
                } else {
                    label.setTextColor(white)
                }
            }
            // OK not required for page switch; still allow click
            btn.setOnClickListener {
                activity.showTab(tab)
                btn.requestFocus()
            }
        }
        bindTab(activity.homeButton, MainActivity.Tab.HOME, activity.homeButtonLabel)
        bindTab(activity.appsButton, MainActivity.Tab.APPS, activity.appsButtonLabel)
        bindTab(activity.filesButton, MainActivity.Tab.FILES, activity.filesButtonLabel)
        bindTab(activity.weatherButton, MainActivity.Tab.WEATHER, activity.weatherButtonLabel)

        // ── Search icon: focus only highlights; OK activates ──
        searchButton.setOnFocusChangeListener { _, hasFocus ->
            searchButton.setColorFilter(if (hasFocus) focusTextDark else white)
            // Do NOT expand on focus
        }
        searchButton.setOnClickListener { activateSearch() }
        searchButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    activateSearch()
                    true
                }
                else -> false // Left/Right use nextFocus* chain
            }
        }

        // ── Search input ──
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressQueryCallback) return
                if (searchState == SearchState.IDLE) return
                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val q = s?.toString().orEmpty()
                val r = Runnable {
                    if (searchState == SearchState.IDLE) return@Runnable
                    searchResults.onQueryChanged(q)
                }
                debounceRunnable = r
                mainHandler.postDelayed(r, 180L)
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                val q = searchInput.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    // openWebSearch closes Search via onRequestClose
                    searchResults.openWebSearch(q, closeAfter = true)
                }
                true
            } else false
        }
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    searchResults.focusFirstResult()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> {
                    // Stay in input / let IME handle cursor — never dismiss Search
                    true
                }
                else -> false
            }
        }

        profileButton.setOnClickListener {
            Toast.makeText(activity, "Profil segera hadir", Toast.LENGTH_SHORT).show()
        }

        homeIcon.setOnClickListener { activity.settingsSidebar.openSidebar() }
        homeIcon.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                activity.settingsSidebar.openSidebar(); true
            } else false
        }
        settingsButton.setOnClickListener {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "Pengaturan tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
        }
        activity.networkIndicator.setOnClickListener {
            try {
                activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, "Pengaturan jaringan tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }
        activity.clockTime.setOnClickListener { openDateTimeSettings() }
        activity.clockAppName.setOnClickListener { activity.openAppInfo(activity.packageName) }
        fun clockTint(tv: TextView) {
            tv.setOnFocusChangeListener { _, hasFocus ->
                tv.setTextColor(if (hasFocus) focusTextDark else white)
            }
        }
        clockTint(activity.clockTime)
        clockTint(activity.clockAppName)
    }

    /**
     * Profile ↔ visible Navigation ↔ visible Shortcuts (action pill).
     * Dynamic shell shortcuts (tag shell_sc_*) are included when present.
     * Does not hard-loop the right edge of Navigation when shortcuts exist —
     * that was trapping focus and blocking Navigation → Shortcuts.
     */
    private fun wireShellFocusChain() {
        val profile = activity.findViewById<View>(R.id.profileButton) ?: return
        val search = activity.findViewById<View>(R.id.searchButton)
        val home = activity.homeButton
        val apps = activity.appsButton
        val files = activity.filesButton
        val weather = activity.weatherButton

        val nav = listOfNotNull(search, home, apps, files, weather)
            .filter { it.visibility == View.VISIBLE }

        // Shortcuts: prefer live action-pill children (dynamic + any still-visible static)
        val pill = activity.findViewById<LinearLayout>(R.id.actionPill)
        val shortcuts = mutableListOf<View>()
        if (pill != null) {
            for (i in 0 until pill.childCount) {
                val c = pill.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.isFocusable) {
                    shortcuts += c
                }
            }
        }
        if (shortcuts.isEmpty()) {
            // Fallback to legacy static ids if still visible
            listOfNotNull(
                activity.findViewById(R.id.homeIcon),
                activity.findViewById(R.id.settingsButton),
                activity.networkIndicator
            ).filter { it.visibility == View.VISIBLE }.let { shortcuts += it }
        }

        fun chain(views: List<View>) {
            for (i in views.indices) {
                if (views[i].id == View.NO_ID) views[i].id = View.generateViewId()
                if (i > 0) views[i].nextFocusLeftId = views[i - 1].id
                if (i < views.lastIndex) views[i].nextFocusRightId = views[i + 1].id
            }
        }
        val all = listOf(profile) + nav + shortcuts
        if (all.isEmpty()) return
        all.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        // generateViewById doesn't exist - use generateViewId
        chain(all)
        // Left edge only — right edge stays open if we later attach clock, etc.
        all.first().nextFocusLeftId = all.first().id
        if (shortcuts.isEmpty()) {
            // No shortcuts: keep focus on last nav item rather than escaping
            all.last().nextFocusRightId = all.last().id
        }
        // Also ask MainActivity to re-link nav → shortcuts → clock after dynamic rebuild
        try {
            activity.refreshShellShortcuts()
        } catch (_: Exception) {
            // safe if called before shortcuts are ready
        }
    }

    // ─── Search activation ───

    private fun activateSearch() {
        val searchButton = activity.findViewById<ImageView>(R.id.searchButton) ?: return
        val searchOverlay = activity.findViewById<LinearLayout>(R.id.searchOverlay)
        val searchInput = activity.findViewById<EditText>(R.id.searchInput) ?: return

        showOverlay(searchOverlay, searchButton)
        searchResults.show()
        searchState = SearchState.KEYBOARD

        searchInput.isFocusable = true
        searchInput.isFocusableInTouchMode = true
        searchInput.isCursorVisible = true
        searchInput.nextFocusLeftId = searchInput.id
        searchInput.nextFocusRightId = searchInput.id
        searchInput.nextFocusUpId = searchInput.id

        searchInput.post {
            searchInput.requestFocus()
            searchInput.setSelection(searchInput.text?.length ?: 0)
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            val q = searchInput.text?.toString().orEmpty()
            searchResults.onQueryChanged(q)
        }
    }

    private fun enterExpandedFromKeyboard() {
        val searchInput = activity.findViewById<EditText>(R.id.searchInput) ?: return
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (_: Exception) {}
        // Stay EXPANDED with the input still focusable so the next Back closes
        // Search immediately (no lost-focus / invisible-button intermediate state).
        searchState = SearchState.EXPANDED
        searchInput.isFocusable = true
        searchInput.isFocusableInTouchMode = true
        searchInput.isCursorVisible = true
        if (!searchInput.hasFocus()) {
            searchInput.requestFocus()
        }
    }

    /**
     * Back handling for Search.
     * KEYBOARD → dismiss IME, stay EXPANDED.
     * EXPANDED → clear query, collapse, focus Search icon.
     */
    fun handleBackSearch(): Boolean {
        if (searchState == SearchState.IDLE && !searchResults.isVisible()) return false

        return when (searchState) {
            SearchState.KEYBOARD -> {
                // If the system IME already ate the previous Back and is gone,
                // this Back must fully close — not re-enter EXPANDED.
                if (!imeVisible) {
                    closeSearchFully(clearQuery = true)
                    restoreFocusToSearchIcon()
                } else {
                    enterExpandedFromKeyboard()
                }
                true
            }
            SearchState.EXPANDED -> {
                closeSearchFully(clearQuery = true)
                restoreFocusToSearchIcon()
                true
            }
            SearchState.IDLE -> {
                if (searchResults.isVisible()) {
                    searchResults.hide()
                    restoreFocusToSearchIcon()
                    true
                } else false
            }
        }
    }

    /** Focus Search nav item directly — never route through Profile. */
    private fun restoreFocusToSearchIcon() {
        val searchButton = activity.findViewById<ImageView>(R.id.searchButton) ?: return
        searchButton.alpha = 1f
        searchButton.visibility = View.VISIBLE
        // Tint for focused white pill background before requestFocus fires listener
        searchButton.setColorFilter(focusTextDark)
        searchButton.requestFocus()
        // Re-assert after any concurrent layout from showNavTabs / shortcut rebuild
        searchButton.post {
            if (searchState == SearchState.IDLE && !searchButton.isFocused) {
                searchButton.setColorFilter(focusTextDark)
                searchButton.requestFocus()
            }
        }
    }

    private fun closeSearchFully(clearQuery: Boolean) {
        val searchButton = activity.findViewById<ImageView>(R.id.searchButton)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        val searchInput = activity.findViewById<EditText>(R.id.searchInput)

        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null
        searchState = SearchState.IDLE
        searchResults.hide()
        if (clearQuery && searchInput != null) {
            suppressQueryCallback = true
            try {
                searchInput.setText("")
            } finally {
                suppressQueryCallback = false
            }
        }
        hideOverlay(searchOverlay, searchInput, searchButton)
        // Restore Shell nextFocus links without forcing focus onto Profile
        wireShellFocusChain()
        // Do not set white here when we are about to focus Search — restoreFocusToSearchIcon
        // applies the focused tint. Only reset when Search is not the focus target.
        if (searchButton != null && !searchButton.isFocused) {
            searchButton.setColorFilter(white)
        }
    }

    private fun hideNavTabs(searchButton: View?) {
        if (hiddenTabs.isNotEmpty()) return
        val pill = activity.findViewById<LinearLayout>(R.id.navPill) ?: return
        for (i in 0 until pill.childCount) {
            val child = pill.getChildAt(i)
            if (child === searchButton) {
                child.alpha = 0f
            } else if (child.visibility == View.VISIBLE) {
                hiddenTabs.add(child)
                child.visibility = View.INVISIBLE
            }
        }
    }

    private fun showNavTabs(searchButton: View?) {
        for (v in hiddenTabs) v.visibility = View.VISIBLE
        hiddenTabs.clear()
        searchButton?.alpha = 1f
    }

    private fun showOverlay(overlay: View?, searchButton: View?) {
        if (overlay == null) return
        hideNavTabs(searchButton)
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(160L).start()
        } else {
            overlay.alpha = 1f
        }
    }

    private fun hideOverlay(overlay: View?, searchInput: EditText?, searchButton: ImageView?) {
        if (overlay == null) return
        searchInput?.isFocusable = false
        searchInput?.isFocusableInTouchMode = false
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput?.windowToken, 0)
        } catch (_: Exception) {}
        showNavTabs(searchButton)
        searchButton?.visibility = View.VISIBLE
        searchButton?.alpha = 1f
        searchButton?.setColorFilter(white)
        overlay.animate().cancel()
        if (overlay.visibility == View.VISIBLE) {
            overlay.animate().alpha(0f).setDuration(120L).withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
            }.start()
        }
    }

    private fun openDateTimeSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "Pengaturan tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
