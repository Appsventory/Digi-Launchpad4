package com.appsventory.digiapps.launchpad4.ui.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.graphics.drawable.StateListDrawable
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.appsventory.digiapps.launchpad4.R
import com.appsventory.digiapps.launchpad4.data.PrefsKeys
import com.appsventory.digiapps.launchpad4.data.SambaBrowser
import com.appsventory.digiapps.launchpad4.ui.util.FocusLock
import java.io.File
import java.util.concurrent.Executors

/**
 * File manager bersama untuk:
 *  - Tab Files di [com.appsventory.digiapps.launchpad4.MainActivity] (launcher)
 *  - [com.appsventory.digiapps.launchpad4.FilesActivity] (standalone + intent-filter picker)
 *
 * Layout host wajib punya id yang sama: filesPath, filesList, filesMenuButton,
 * filesUpButton, filesViewToggle, filesSortButton, filesSidebar*, filesFilter*,
 * filesLocInternal / External / AddSamba / filesSambaList.
 *
 * @param focusLockRoot view group di belakang overlay yang di-FOCUS_BLOCK_DESCENDANTS
 *        saat sidebar terbuka (mainContent di launcher; root konten di standalone).
 */
class FileManagerController(
    private val activity: Activity,
    private val focusLockRoot: ViewGroup?,
    /**
     * Id tombol tab "Files" di top bar launcher, supaya menekan D-pad UP dari toolbar
     * file manager balik ke tab bar. Null saat standalone (Digi Files/FilesActivity)
     * karena tidak ada tab bar. Ini logic lintas-screen yang dinamis, makanya diatur
     * di Kotlin — bukan didobel di XML.
     */
    private val tabButtonId: Int? = null,
    /**
     * true = FilesActivity (tampilkan header judul + tombol close).
     * false = tab di MainActivity (header disembunyikan).
     * Satu layout [view_files_page]; beda hanya visibility.
     */
    private val standalone: Boolean = false,
) {
    private val prefs: SharedPreferences =
        activity.getSharedPreferences(PrefsKeys.PREFS_FILE, Activity.MODE_PRIVATE)

    // Colors read once from resources instead of hardcoded hex literals scattered below.
    private val colorWhite = activity.getColor(R.color.white)
    private val colorWhite50 = activity.getColor(R.color.white_50)
    private val colorWhite70 = activity.getColor(R.color.white_70)
    private val colorWhite90 = activity.getColor(R.color.white_90)
    private val themeDarkPrimaryText = activity.getColor(R.color.theme_dark_primary_text)
    private val focusTextDark = activity.getColor(R.color.focus_text_dark)

    private lateinit var filesPath: TextView
    private lateinit var filesList: LinearLayout
    private lateinit var filesMenuButton: View
    private lateinit var filesUpButton: View
    private lateinit var filesViewToggle: TextView
    private lateinit var filesSortButton: TextView
    private lateinit var filesSidebarScrim: View
    private lateinit var filesSidebarPanel: View
    private lateinit var filesSidebarClose: View

    companion object {
        private val DOC_EXTS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "rtf", "odt", "ods", "odp", "csv"
        )
        private val ARCHIVE_EXTS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz"
        )
        private const val APK_EXT = "apk"

        // View mode
        private const val VIEW_LIST = "list"
        private const val VIEW_GRID = "grid"

        // Sort mode
        private const val SORT_NAME = "name"
        private const val SORT_SIZE = "size"
        private const val SORT_TYPE = "type"

        // Filter mode (also reused as Samba raw-string field separators elsewhere)
        private const val FILTER_APK = "apk"
        private const val FILTER_DOC = "doc"
        private const val FILTER_ARCHIVE = "archive"

        // Repeated 8x pre-fix as a raw literal; API 29- devices without a resolvable
        // getExternalStorageDirectory() all fall back to this well-known path.
        private const val FALLBACK_STORAGE_PATH = "/storage/emulated/0"

        /** Primary storage root, with the same fallback used everywhere in this controller. */
        private fun primaryStorageRoot(): File =
            Environment.getExternalStorageDirectory() ?: File(FALLBACK_STORAGE_PATH)
    }

    var currentDir: File = primaryStorageRoot()
        private set

    private var viewMode: String = VIEW_LIST
    private var sortMode: String = SORT_NAME
    private var filterMode: String? = null

    /** Mode browse Samba aktif (null = local storage). */
    private var remoteShare: SambaShare? = null
    private var remotePath: String = ""
    /** true = sedang di level daftar share host (belum masuk share). */
    private var remoteAtShareList: Boolean = false
    private val samba = SambaBrowser()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** Sidebar navigasi kiri sedang terbuka. */
    var isSidebarOpen: Boolean = false
        private set

    val hasActiveFilter: Boolean get() = filterMode != null

    fun setup() {
        filesPath = activity.findViewById(R.id.filesPath)
        filesList = activity.findViewById(R.id.filesList)
        filesMenuButton = activity.findViewById(R.id.filesMenuButton)
        filesUpButton = activity.findViewById(R.id.filesUpButton)
        filesViewToggle = activity.findViewById(R.id.filesViewToggle)
        filesSortButton = activity.findViewById(R.id.filesSortButton)
        filesSidebarScrim = activity.findViewById(R.id.filesSidebarScrim)
        filesSidebarPanel = activity.findViewById(R.id.filesSidebarPanel)
        filesSidebarClose = activity.findViewById(R.id.filesSidebarClose)

        // Satu layout view_files_page: tampilkan header hanya di mode standalone
        activity.findViewById<View?>(R.id.filesHeader)?.visibility =
            if (standalone) View.VISIBLE else View.GONE

        viewMode = prefs.getString(PrefsKeys.FILES_VIEW_MODE, VIEW_LIST) ?: VIEW_LIST
        sortMode = prefs.getString(PrefsKeys.FILES_SORT_MODE, SORT_NAME) ?: SORT_NAME

        filesMenuButton.setOnClickListener { openSidebar() }
        filesMenuButton.setOnKeyListener { _, keyCode, event ->
            if (isActivate(event, keyCode)) { openSidebar(); true } else false
        }
        filesUpButton.setOnClickListener { navigateUp() }
        filesUpButton.setOnKeyListener { _, keyCode, event ->
            if (isActivate(event, keyCode)) { navigateUp(); true } else false
        }
        filesViewToggle.setOnClickListener { toggleViewMode() }
        filesViewToggle.setOnKeyListener { _, keyCode, event ->
            if (isActivate(event, keyCode)) { toggleViewMode(); true } else false
        }
        filesSortButton.setOnClickListener { cycleSortMode() }
        filesSortButton.setOnKeyListener { _, keyCode, event ->
            if (isActivate(event, keyCode)) { cycleSortMode(); true } else false
        }

        filesSidebarClose.setOnClickListener { closeSidebar() }
        filesSidebarScrim.setOnClickListener { closeSidebar() }
        filesSidebarPanel.setOnClickListener { /* consume */ }

        // Toolbar pill: bg putih saat focus → teks gelap biar kebaca
        listOf(filesMenuButton, filesUpButton, filesSortButton, filesViewToggle, filesPath).forEach { btn ->
            if (btn is TextView) {
                btn.setOnFocusChangeListener { v, hasFocus ->
                    (v as TextView).setTextColor(if (hasFocus) focusTextDark else themeDarkPrimaryText)
                }
            }
        }
        activity.findViewById<TextView?>(R.id.filesCloseButton)?.let { closeBtn ->
            closeBtn.setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) focusTextDark else themeDarkPrimaryText)
            }
        }
        activity.findViewById<TextView?>(R.id.filesSidebarClose)?.let { closeBtn ->
            closeBtn.setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) focusTextDark else themeDarkPrimaryText)
            }
        }

        activity.findViewById<View>(R.id.filesFilterApk).setOnClickListener { applyFilter(FILTER_APK) }
        activity.findViewById<View>(R.id.filesFilterDoc).setOnClickListener { applyFilter(FILTER_DOC) }
        activity.findViewById<View>(R.id.filesFilterArchive).setOnClickListener { applyFilter(FILTER_ARCHIVE) }
        activity.findViewById<View>(R.id.filesFilterDownload).setOnClickListener { goToDownloads() }
        activity.findViewById<View>(R.id.filesLocInternal).setOnClickListener { goToInternalStorage() }
        activity.findViewById<View>(R.id.filesLocExternal).setOnClickListener { goToExternalStorage() }
        activity.findViewById<View>(R.id.filesLocAddSamba).setOnClickListener { showAddSambaDialog() }

        filesPath.setOnClickListener {
            if (filterMode != null) clearFilter()
        }

        // Tombol close opsional (hanya di FilesActivity standalone)
        activity.findViewById<View?>(R.id.filesCloseButton)?.setOnClickListener {
            activity.finish()
        }

        updateLabels()
        refreshLocationRows()

        // Hubungkan D-pad UP dari toolbar file manager balik ke tab bar launcher
        // (hanya saat embedded di MainActivity, lihat dokumentasi [tabButtonId]).
        tabButtonId?.let { id ->
            filesMenuButton.nextFocusUpId = id
            filesUpButton.nextFocusUpId = id
            filesPath.nextFocusUpId = id
            filesSortButton.nextFocusUpId = id
            filesViewToggle.nextFocusUpId = id
        }
    }

    private fun isActivate(event: KeyEvent, keyCode: Int): Boolean =
        event.action == KeyEvent.ACTION_DOWN &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)

    // ─── Sidebar (pola SettingsSidebarController) ───

    fun openSidebar() {
        if (isSidebarOpen) return
        refreshLocationRows()
        isSidebarOpen = true
        FocusLock.lock(focusLockRoot, this)
        filesSidebarScrim.visibility = View.VISIBLE
        filesSidebarScrim.alpha = 0f
        filesSidebarScrim.animate().alpha(1f).setDuration(200).start()
        val w = filesSidebarPanel.width.takeIf { it > 0 }?.toFloat()
            ?: (320 * activity.resources.displayMetrics.density)
        filesSidebarPanel.translationX = -w
        filesSidebarPanel.animate().translationX(0f).setDuration(220).start()
        activity.findViewById<View>(R.id.filesFilterApk).post {
            activity.findViewById<View>(R.id.filesFilterApk).requestFocus()
        }
    }

    fun closeSidebar() {
        if (!isSidebarOpen) return
        isSidebarOpen = false
        FocusLock.unlock(focusLockRoot, this)
        val w = filesSidebarPanel.width.toFloat().coerceAtLeast(1f)
        filesSidebarPanel.animate().translationX(-w).setDuration(200).withEndAction {
            filesSidebarScrim.visibility = View.GONE
        }.start()
        filesSidebarScrim.animate().alpha(0f).setDuration(200).start()
        filesMenuButton.requestFocus()
    }

    /**
     * Back hierarchy: sidebar → filter → naik folder.
     * @return true jika dikonsumsi.
     */
    fun handleBack(): Boolean {
        if (isSidebarOpen) {
            closeSidebar()
            return true
        }
        if (filterMode != null) {
            clearFilter()
            return true
        }
        if (remoteShare != null) {
            if (remoteAtShareList) {
                exitRemote()
                openDirectory(primaryStorageRoot(), focusFirst = true)
                return true
            }
            val parent = samba.parentPath(remotePath)
            if (parent != null) {
                openRemoteDirectory(parent, focusFirst = true)
                return true
            }
            // Root share → daftar share (cache) atau keluar ke local
            val hostOnly = loadSambaShares().any {
                it.host == remoteShare!!.host && it.share.isBlank()
            }
            if (hostOnly) {
                reloadShareList(forceRefresh = false)
                return true
            }
            exitRemote()
            openDirectory(primaryStorageRoot(), focusFirst = true)
            return true
        }
        val parent = currentDir.parentFile
        if (parent != null && parent.canRead()) {
            openDirectory(parent, focusFirst = true)
            return true
        }
        return false
    }

    // ─── Locations ───

    private fun refreshLocationRows() {
        val externalRow = activity.findViewById<View>(R.id.filesLocExternal)
        val secondary = findSecondaryStorage()
        externalRow.visibility = if (secondary != null) View.VISIBLE else View.GONE
        externalRow.tag = secondary

        val sambaList = activity.findViewById<LinearLayout>(R.id.filesSambaList)
        sambaList.removeAllViews()
        for (share in loadSambaShares()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_hover_solid)
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply { bottomMargin = dp(6) }
                addView(TextView(activity).apply {
                    text = "🌐"
                    textSize = 18f
                    setPadding(0, 0, dp(12), 0)
                })
                addView(TextView(activity).apply {
                    text = share.name
                    setTextColor(themeDarkPrimaryText)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                setOnClickListener {
                    closeSidebar()
                    connectAndOpenShare(share)
                }
                setOnLongClickListener {
                    showSambaItemMenu(share)
                    true
                }
            }
            sambaList.addView(row)
        }
        wireLocationFocusChain()
    }

    /**
     * Rantai fokus vertikal LOKASI (setelah filter):
     *   Download → Internal → [External] → [Samba…] → Add Samba
     * Jangan ever set nextFocusUp Internal ke Add — itu bikin fokus “terkunci”
     * di section lokasi dan loncat aneh ke bawah.
     */
    private fun wireLocationFocusChain() {
        val download = activity.findViewById<View>(R.id.filesFilterDownload)
        val internal = activity.findViewById<View>(R.id.filesLocInternal)
        val external = activity.findViewById<View>(R.id.filesLocExternal)
        val addBtn = activity.findViewById<View>(R.id.filesLocAddSamba)
        val sambaList = activity.findViewById<LinearLayout>(R.id.filesSambaList)

        // Urutan visual top→bottom di section lokasi (+ sambungan ke filter)
        val chain = mutableListOf<View>()
        chain.add(internal)
        if (external.visibility == View.VISIBLE) chain.add(external)
        for (i in 0 until sambaList.childCount) {
            sambaList.getChildAt(i)?.let { chain.add(it) }
        }
        chain.add(addBtn)

        for (v in chain) {
            if (v.id == View.NO_ID) v.id = View.generateViewId()
            v.nextFocusLeftId = v.id
            v.nextFocusRightId = v.id
        }

        // Hubungkan filter → lokasi
        download.nextFocusDownId = internal.id
        internal.nextFocusUpId = download.id

        for (i in chain.indices) {
            val cur = chain[i]
            cur.nextFocusUpId = when {
                i == 0 -> download.id
                else -> chain[i - 1].id
            }
            cur.nextFocusDownId = when {
                i == chain.lastIndex -> cur.id // ujung bawah: stay
                else -> chain[i + 1].id
            }
        }
    }

    /**
     * Format prefs: name|host|share|user|pass (user/pass opsional).
     * Entry dipisah ";".
     */
    data class SambaShare(
        val name: String,
        val host: String,
        val share: String,
        val username: String = "",
        val password: String = "",
    )

    private fun loadSambaShares(): List<SambaShare> {
        val raw = prefs.getString(PrefsKeys.SAMBA_SHARES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size >= 3 && p[0].isNotBlank() && p[1].isNotBlank()) {
                SambaShare(
                    name = p[0].trim(),
                    host = p[1].trim(),
                    share = p[2].trim(),
                    username = p.getOrNull(3)?.trim().orEmpty(),
                    password = p.getOrNull(4)?.trim().orEmpty(),
                )
            } else null
        }
    }

    private fun saveSambaShares(list: List<SambaShare>) {
        val encoded = list.joinToString(";") {
            "${it.name}|${it.host}|${it.share}|${it.username}|${it.password}"
        }
        prefs.edit().putString(PrefsKeys.SAMBA_SHARES, encoded).apply()
    }

    private fun showAddSambaDialog() {
        val pad = dp(20)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun field(hint: String) = EditText(activity).apply {
            this.hint = hint
            setTextColor(colorWhite)
            setHintTextColor(colorWhite50)
            textSize = 15f
            setSingleLine()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val nameEt = field("Nama tampilan (mis. NAS Rumah)")
        val hostEt = field("Host / IP (mis. 192.168.1.10)")
        val shareEt = field("Nama share (opsional — kosong = semua share)")
        val userEt = field("Username (kosong = guest)")
        val passEt = field("Password (opsional)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(nameEt)
        container.addView(hostEt)
        container.addView(shareEt)
        container.addView(userEt)
        container.addView(passEt)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Tambah Network (Samba)")
            .setView(container)
            .setPositiveButton(activity.getString(R.string.action_save)) { _, _ ->
                val name = nameEt.text.toString().trim().ifBlank { hostEt.text.toString().trim() }
                val host = hostEt.text.toString().trim()
                val share = shareEt.text.toString().trim()
                val user = userEt.text.toString().trim()
                val pass = passEt.text.toString()

                if (host.isBlank()) {
                    Toast.makeText(activity, "Host wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val list = loadSambaShares().toMutableList()
                list.add(
                    SambaShare(
                        name = name.ifBlank {
                            if (share.isBlank()) host else "$host/$share"
                        },
                        host = host,
                        share = share,
                        username = user,
                        password = pass,
                    )
                )

                saveSambaShares(list)
                refreshLocationRows()
                Toast.makeText(activity, "Samba disimpan: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .create()

        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val buttonBar = positiveButton.parent as View

        val params = buttonBar.layoutParams as ViewGroup.MarginLayoutParams
        params.bottomMargin = dp(12)
        buttonBar.layoutParams = params

        dialog.window?.apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(
                        ContextCompat.getColor(
                            activity,
                            R.color.bg_setting_sidebar_panel
                        )
                    )
                    cornerRadius = dp(12).toFloat()
                }
            )

            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            attributes = attributes.apply {
                dimAmount = 0.6f
            }
        }

        // Focus style tombol untuk Android TV / D-pad
        val buttonFocused = GradientDrawable().apply {
            setColor(
                ContextCompat.getColor(
                    activity,
                    R.color.setting_card
                )
            )
            cornerRadius = activity.resources.getDimension(R.dimen.setting_card_radius)
        }

        val buttonNormal = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = activity.resources.getDimension(R.dimen.setting_card_radius)
        }

        val buttonPressed = GradientDrawable().apply {
            setColor(
                ContextCompat.getColor(
                    activity,
                    R.color.setting_card_state_pressed_stroke
                )
            )
            cornerRadius = activity.resources.getDimension(R.dimen.setting_card_radius)
        }

        fun applyButtonFocus(button: Button) {
            val drawable = StateListDrawable()

            drawable.addState(
                intArrayOf(android.R.attr.state_focused),
                buttonFocused
            )
            drawable.addState(
                intArrayOf(android.R.attr.state_pressed),
                buttonPressed
            )
            drawable.addState(
                intArrayOf(),
                buttonNormal
            )
            button.background = drawable
            button.setPadding(dp(5), 0, dp(5), 0)
        }

        applyButtonFocus(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
        applyButtonFocus(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }

    private fun showSambaItemMenu(share: SambaShare) {
        val label = buildString {
            append(share.name)
            append('\n')
            append("\\\\")
            append(share.host)
            if (share.share.isNotBlank()) {
                append("\\")
                append(share.share)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setItems(arrayOf("Edit", "Hapus")) { _, which ->
                when (which) {
                    0 -> showEditSambaDialog(share)
                    1 -> confirmRemoveSamba(share)
                }
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .show()
    }

    private fun showEditSambaDialog(existing: SambaShare) {
        val pad = dp(20)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(hint: String, value: String = "") = EditText(activity).apply {
            this.hint = hint
            setText(value)
            setTextColor(colorWhite)
            setHintTextColor(colorWhite50)
            textSize = 15f
            setSingleLine()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = activity.getDrawable(R.drawable.bg_hover_solid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val nameEt = field("Nama tampilan", existing.name)
        val hostEt = field("Host / IP", existing.host)
        val shareEt = field("Nama share (opsional — kosong = semua share)", existing.share)
        val userEt = field("Username (kosong = guest)", existing.username)
        val passEt = field("Password (opsional)", existing.password).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(nameEt)
        container.addView(hostEt)
        container.addView(shareEt)
        container.addView(userEt)
        container.addView(passEt)

        AlertDialog.Builder(activity)
            .setTitle("Edit Samba")
            .setView(container)
            .setPositiveButton(activity.getString(R.string.action_save)) { _, _ ->
                val host = hostEt.text.toString().trim()
                if (host.isBlank()) {
                    Toast.makeText(activity, "Host wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val shareName = shareEt.text.toString().trim()
                val updated = SambaShare(
                    name = nameEt.text.toString().trim().ifBlank {
                        if (shareName.isBlank()) host else "$host/$shareName"
                    },
                    host = host,
                    share = shareName,
                    username = userEt.text.toString().trim(),
                    password = passEt.text.toString(),
                )
                val list = loadSambaShares().toMutableList()
                val idx = list.indexOfFirst {
                    it.name == existing.name && it.host == existing.host && it.share == existing.share
                }
                if (idx >= 0) list[idx] = updated else list.add(updated)
                saveSambaShares(list)
                refreshLocationRows()
                Toast.makeText(activity, "Disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .show()
    }

    private fun confirmRemoveSamba(share: SambaShare) {
        val path = buildString {
            append("\\\\")
            append(share.host)
            if (share.share.isNotBlank()) {
                append("\\")
                append(share.share)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Hapus Samba?")
            .setMessage("${share.name}\n$path")
            .setPositiveButton("Hapus") { _, _ ->
                saveSambaShares(loadSambaShares().filterNot {
                    it.name == share.name && it.host == share.host && it.share == share.share
                })
                refreshLocationRows()
                Toast.makeText(activity, "Dihapus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .show()
    }

    // ─── Filter & lokasi cepat ───

    private fun applyFilter(mode: String) {
        filterMode = if (filterMode == mode) null else mode
        exitRemote()
        closeSidebar()
        // Filter selalu dari root internal (+ external jika ada), bukan folder aktif
        if (filterMode != null) {
            val internal = primaryStorageRoot()
            openDirectory(internal, focusFirst = true)
        } else {
            openDirectory(currentDir, focusFirst = true)
        }
        val label = when (mode) {
            FILTER_APK -> "APK"
            FILTER_DOC -> "Dokumen"
            FILTER_ARCHIVE -> "Archive"
            else -> mode
        }
        Toast.makeText(
            activity,
            if (filterMode == null) "Filter dimatikan" else "Filter: $label (semua storage)",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun clearFilter() {
        if (filterMode == null) return
        filterMode = null
        val internal = primaryStorageRoot()
        openDirectory(internal, focusFirst = true)
        Toast.makeText(activity, "Filter dimatikan", Toast.LENGTH_SHORT).show()
    }

    private fun goToDownloads() {
        filterMode = null
        exitRemote()
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = when {
            dl != null && (dl.isDirectory || dl.mkdirs()) -> dl
            else -> File(
                primaryStorageRoot(),
                "Download"
            )
        }
        closeSidebar()
        openDirectory(target, focusFirst = true)
    }

    private fun goToInternalStorage() {
        filterMode = null
        exitRemote()
        val internal = primaryStorageRoot()
        closeSidebar()
        openDirectory(internal, focusFirst = true)
    }

    private fun goToExternalStorage() {
        filterMode = null
        exitRemote()
        val external = (activity.findViewById<View>(R.id.filesLocExternal).tag as? File)
            ?: findSecondaryStorage()
        closeSidebar()
        if (external != null) {
            openDirectory(external, focusFirst = true)
        } else {
            Toast.makeText(activity, "Tidak ada external storage terdeteksi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findSecondaryStorage(): File? {
        val candidates = mutableListOf<File>()
        try {
            val dirs = activity.getExternalFilesDirs(null)
            for (d in dirs) {
                if (d == null) continue
                var root = d
                var guard = 0
                while (root.parentFile != null && guard < 8) {
                    val parent = root.parentFile ?: break
                    if (parent.absolutePath == "/" || parent.absolutePath == "/storage") break
                    if (parent.name == "storage" || parent.name == "mnt") break
                    root = parent
                    guard++
                }
                if (root.name.equals("Android", true) && root.parentFile != null) {
                    root = root.parentFile!!
                }
                if (root.canRead() &&
                    root.absolutePath != FALLBACK_STORAGE_PATH &&
                    !root.absolutePath.startsWith(FALLBACK_STORAGE_PATH)
                ) {
                    candidates.add(root)
                }
            }
        } catch (_: Exception) { }
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() &&
                    f.name != "emulated" && f.name != "self" &&
                    !f.name.startsWith(".")
                ) {
                    candidates.add(f)
                }
            }
        } catch (_: Exception) { }
        return candidates.distinctBy { it.absolutePath }.firstOrNull { it.canRead() }
    }

    // ─── Browse ───

    fun resolveStartDir(intent: Intent?) {
        if (intent == null) return
        intent.data?.path?.let { path ->
            if (path.isNotBlank()) {
                val f = File(path)
                currentDir = when {
                    f.isDirectory -> f
                    f.isFile && f.parentFile != null -> f.parentFile!!
                    else -> currentDir
                }
            }
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extra ->
            val f = File(extra)
            if (f.isDirectory) currentDir = f
            else if (f.isFile && f.parentFile != null) currentDir = f.parentFile!!
        }
    }


    // ─── Samba remote ───


    // ─── Samba remote ───

    private fun exitRemote() {
        if (remoteShare == null && !remoteAtShareList) return
        ioExecutor.execute {
            try { samba.disconnect() } catch (_: Exception) {}
        }
        remoteShare = null
        remotePath = ""
        remoteAtShareList = false
    }

    private fun connectAndOpenShare(share: SambaShare) {
        filterMode = null
        filesList.removeAllViews()
        val targetLabel = if (share.share.isBlank()) {
            "\\\\${share.host}"
        } else {
            "\\\\${share.host}\\${share.share}"
        }
        filesList.addView(msgView("Menghubungkan ke $targetLabel…"))
        filesPath.text = targetLabel
        Toast.makeText(activity, "Menghubungkan…", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            try {
                val ref = SambaBrowser.ShareRef(
                    name = share.name,
                    host = share.host,
                    share = share.share,
                    username = share.username,
                    password = share.password,
                )
                if (share.share.isBlank()) {
                    // Host saja → daftar semua share
                    samba.connectHost(ref)
                    val shares = samba.listShares()
                    activity.runOnUiThread {
                        remoteShare = share
                        remotePath = ""
                        remoteAtShareList = true
                        filesPath.text = "\\\\${share.host}"
                        filesList.removeAllViews()
                        if (shares.isEmpty()) {
                            filesList.addView(
                                msgView(
                                    "Tidak ada share terdeteksi.\n" +
                                        "Edit koneksi dan isi nama share manual, atau cek user/password."
                                )
                            )
                            filesUpButton.post { filesUpButton.requestFocus() }
                        } else {
                            renderShareList(shares)
                            filesList.post { focusFirstItem() }
                        }
                        Toast.makeText(
                            activity,
                            "Terhubung: ${share.name} (${shares.size} share)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    samba.connect(ref)
                    activity.runOnUiThread {
                        remoteShare = share
                        remotePath = ""
                        remoteAtShareList = false
                        openRemoteDirectory("", focusFirst = true)
                        Toast.makeText(activity, "Terhubung: ${share.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    remoteShare = null
                    remotePath = ""
                    remoteAtShareList = false
                    filesList.removeAllViews()
                    filesList.addView(
                        msgView("Gagal hubung Samba:\n${e.message ?: e.javaClass.simpleName}")
                    )
                    Toast.makeText(
                        activity,
                        "Gagal: ${e.message ?: "koneksi ditolak"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderShareList(shares: List<String>) {
        for (name in shares) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_files_list_item)
                setPadding(dp(12), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                addView(TextView(activity).apply {
                    text = "SHR"
                    textSize = 10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(activity.getColor(R.color.accent_sky))
                    background = activity.getDrawable(R.drawable.bg_files_icon_folder)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(activity).apply {
                        text = name
                        setTextColor(colorWhite)
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(activity).apply {
                        text = "SMB share"
                        setTextColor(colorWhite50)
                        textSize = 12f
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                setOnClickListener { openNamedShare(name) }
            }
            filesList.addView(row)
        }
    }

    private fun openNamedShare(shareName: String) {
        filesList.removeAllViews()
        filesList.addView(msgView("Membuka share $shareName…"))
        ioExecutor.execute {
            try {
                // Coba nama apa adanya; fallback casing umum kalau gagal
                val candidates = linkedSetOf(
                    shareName,
                    shareName.lowercase(),
                    shareName.replaceFirstChar { it.uppercase() },
                    shareName.uppercase(),
                )
                var lastError: Exception? = null
                var opened: String? = null
                for (c in candidates) {
                    try {
                        samba.connectShare(c)
                        opened = c
                        break
                    } catch (ex: Exception) {
                        lastError = ex
                    }
                }
                if (opened == null) throw lastError ?: IllegalStateException("Gagal buka share")
                val finalName = opened
                activity.runOnUiThread {
                    remoteAtShareList = false
                    remotePath = ""
                    remoteShare = remoteShare?.copy(share = finalName)
                    openRemoteDirectory("", focusFirst = true)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Gagal buka share: ${e.message}", Toast.LENGTH_LONG).show()
                    // Kembali ke daftar share
                    ioExecutor.execute {
                        try {
                            val shares = samba.listShares()
                            activity.runOnUiThread {
                                remoteAtShareList = true
                                filesList.removeAllViews()
                                if (shares.isEmpty()) {
                                    filesList.addView(msgView("Tidak ada share"))
                                } else {
                                    renderShareList(shares)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun openRemoteDirectory(path: String, focusFirst: Boolean = false) {
        val share = remoteShare ?: return
        remotePath = path
        remoteAtShareList = false
        filesPath.text = buildString {
            append("\\\\")
            append(share.host)
            append("\\")
            append(share.share)
            if (path.isNotBlank()) {
                append("\\")
                append(path.replace('/', '\\'))
            }
        }
        filesList.removeAllViews()
        filesList.addView(msgView("Memuat…"))

        ioExecutor.execute {
            try {
                val entries = sortRemoteEntries(samba.list(path))
                activity.runOnUiThread {
                    filesList.removeAllViews()
                    if (entries.isEmpty()) {
                        filesList.addView(msgView("Folder kosong"))
                        if (focusFirst) filesUpButton.post { filesUpButton.requestFocus() }
                        return@runOnUiThread
                    }
                    if (viewMode == VIEW_GRID) renderRemoteGrid(entries) else renderRemoteList(entries)
                    if (focusFirst) filesList.post { focusFirstItem() }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    filesList.removeAllViews()
                    filesList.addView(msgView("Gagal baca folder:\n${e.message ?: e.javaClass.simpleName}"))
                    Toast.makeText(activity, e.message ?: "Error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onRemoteActivated(entry: SambaBrowser.Entry) {
        if (entry.isDirectory) {
            openRemoteDirectory(entry.path, focusFirst = true)
            return
        }
        Toast.makeText(activity, "Mengunduh ${entry.name}…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            try {
                val cacheDir = File(activity.cacheDir, "samba_cache").apply { mkdirs() }
                val dest = File(cacheDir, entry.name)
                samba.download(entry.path, dest)
                activity.runOnUiThread {
                    try {
                        val uri = FileProvider.getUriForFile(
                            activity, "${activity.packageName}.fileprovider", dest
                        )
                        val mime = activity.contentResolver.getType(uri) ?: "*/*"
                        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Tidak ada app untuk buka file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Gagal unduh: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderRemoteList(entries: List<SambaBrowser.Entry>) {
        for (e in entries) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_files_list_item)
                setPadding(dp(12), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                addView(TextView(activity).apply {
                    text = if (e.isDirectory) "DIR" else e.name.substringAfterLast('.', "").uppercase().take(3).ifBlank { "FIL" }
                    textSize = 10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (e.isDirectory) activity.getColor(R.color.accent_sky) else colorWhite90)
                    background = activity.getDrawable(
                        if (e.isDirectory) R.drawable.bg_files_icon_folder else R.drawable.bg_files_icon_file
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(activity).apply {
                        text = e.name
                        setTextColor(colorWhite)
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(activity).apply {
                        text = if (e.isDirectory) "Folder" else formatFileSize(e.size)
                        setTextColor(colorWhite50)
                        textSize = 12f
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                setOnClickListener { onRemoteActivated(e) }
            }
            filesList.addView(row)
        }
    }

    private fun renderRemoteGrid(entries: List<SambaBrowser.Entry>) {
        val cols = PrefsKeys.FILES_GRID_COLS
        var rowLayout: LinearLayout? = null
        for ((index, e) in entries.withIndex()) {
            if (index % cols == 0) {
                rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                filesList.addView(rowLayout)
            }
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_files_grid_item)
                setPadding(dp(10), dp(14), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5); marginEnd = dp(5)
                }
                addView(TextView(activity).apply {
                    text = if (e.isDirectory) "DIR" else e.name.substringAfterLast('.', "").uppercase().take(3).ifBlank { "FIL" }
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (e.isDirectory) activity.getColor(R.color.accent_sky) else colorWhite90)
                    background = activity.getDrawable(
                        if (e.isDirectory) R.drawable.bg_files_icon_folder else R.drawable.bg_files_icon_file
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                })
                addView(TextView(activity).apply {
                    text = e.name
                    setTextColor(colorWhite90)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                })
                setOnClickListener { onRemoteActivated(e) }
            }
            rowLayout!!.addView(cell)
        }
        val remainder = entries.size % cols
        if (remainder != 0 && rowLayout != null) {
            for (i in remainder until cols) {
                rowLayout.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        marginStart = dp(5); marginEnd = dp(5)
                    }
                    isFocusable = false
                })
            }
        }
    }

    fun openDirectory(dir: File, focusFirst: Boolean = false) {
        // Buka folder lokal → pastikan mode remote mati
        if (remoteShare != null) exitRemote()
        currentDir = dir
        filesPath.text = buildString {
            if (filterMode != null) {
                append("Semua storage")
                when (filterMode) {
                    FILTER_APK -> append("  ·  APK")
                    FILTER_DOC -> append("  ·  Dokumen")
                    FILTER_ARCHIVE -> append("  ·  Archive")
                }
            } else {
                append(dir.absolutePath)
            }
        }
        filesList.removeAllViews()

        val files = try {
            // Filter: scan root internal + external, bukan hanya folder aktif
            if (filterMode != null) collectFilteredFilesFromStorageRoots()
            else dir.listFiles()?.let { sortFiles(it) }
        } catch (_: Exception) { null }

        if (files == null) {
            filesList.addView(msgView("Tidak bisa membaca folder (izin penyimpanan diperlukan)"))
            if (focusFirst) filesUpButton.post { filesUpButton.requestFocus() }
            return
        }
        if (files.isEmpty()) {
            val empty = if (filterMode != null) "Tidak ada file cocok filter" else "Folder kosong"
            filesList.addView(msgView(empty))
            if (focusFirst) filesUpButton.post { filesUpButton.requestFocus() }
            return
        }

        if (viewMode == VIEW_GRID) renderGrid(files) else renderList(files)
        if (focusFirst) filesList.post { focusFirstItem() }
    }

    fun navigateUp() {
        if (filterMode != null) {
            clearFilter()
            return
        }
        if (remoteShare != null) {
            if (remoteAtShareList) {
                exitRemote()
                openDirectory(primaryStorageRoot(), focusFirst = true)
                return
            }
            val parent = samba.parentPath(remotePath)
            if (parent != null) {
                openRemoteDirectory(parent, focusFirst = true)
            } else {
                val hostOnly = loadSambaShares().any {
                    it.host == remoteShare!!.host && it.share.isBlank()
                }
                if (hostOnly) {
                    reloadShareList(forceRefresh = false)
                } else {
                    exitRemote()
                    openDirectory(primaryStorageRoot(), focusFirst = true)
                }
            }
            return
        }
        val parent = currentDir.parentFile
        if (parent != null && parent.canRead()) {
            openDirectory(parent, focusFirst = true)
        } else {
            Toast.makeText(activity, "Sudah di folder teratas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleViewMode() {
        viewMode = if (viewMode == VIEW_GRID) VIEW_LIST else VIEW_GRID
        prefs.edit().putString(PrefsKeys.FILES_VIEW_MODE, viewMode).apply()
        updateLabels()
        refreshCurrentView()
    }

    private fun cycleSortMode() {
        sortMode = when (sortMode) {
            SORT_NAME -> SORT_SIZE
            SORT_SIZE -> SORT_TYPE
            else -> SORT_NAME
        }
        prefs.edit().putString(PrefsKeys.FILES_SORT_MODE, sortMode).apply()
        updateLabels()
        refreshCurrentView()
    }

    /** Refresh tampilan aktif tanpa keluar mode Samba. */
    private fun refreshCurrentView(focusFirst: Boolean = false) {
        when {
            remoteShare != null && remoteAtShareList -> reloadShareList(forceRefresh = false)
            remoteShare != null -> openRemoteDirectory(remotePath, focusFirst = focusFirst)
            else -> openDirectory(currentDir, focusFirst = focusFirst)
        }
    }

    private fun reloadShareList(forceRefresh: Boolean) {
        val share = remoteShare ?: return
        remoteAtShareList = true
        remotePath = ""
        filesPath.text = "\\${share.host}"
        filesList.removeAllViews()
        if (!forceRefresh) {
            // Coba cache dulu — UI tetap responsif
            filesList.addView(msgView("Memuat…"))
        } else {
            filesList.addView(msgView("Memuat share…"))
        }
        ioExecutor.execute {
            try {
                val shares = samba.listShares(forceRefresh = forceRefresh)
                activity.runOnUiThread {
                    filesList.removeAllViews()
                    if (shares.isEmpty()) {
                        filesList.addView(msgView("Tidak ada share"))
                    } else {
                        renderShareList(shares)
                        filesList.post { focusFirstItem() }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    filesList.removeAllViews()
                    filesList.addView(msgView("Gagal muat share: ${e.message}"))
                }
            }
        }
    }

    private fun updateLabels() {
        filesViewToggle.text = if (viewMode == VIEW_GRID) "▦ Grid" else "☰ List"
        filesSortButton.text = when (sortMode) {
            SORT_SIZE -> "↕ Ukuran"
            SORT_TYPE -> "↕ Tipe"
            else -> "↕ Nama"
        }
    }

    private fun sortRemoteEntries(entries: List<SambaBrowser.Entry>): List<SambaBrowser.Entry> {
        val dirsFirst = compareBy<SambaBrowser.Entry> { !it.isDirectory }
        return when (sortMode) {
            SORT_SIZE -> entries.sortedWith(dirsFirst.thenBy { it.size }.thenBy { it.name.lowercase() })
            SORT_TYPE -> entries.sortedWith(
                dirsFirst
                    .thenBy { if (it.isDirectory) "" else it.name.substringAfterLast('.', "").lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            else -> entries.sortedWith(dirsFirst.thenBy { it.name.lowercase() })
        }
    }

    private fun sortFiles(files: Array<File>): List<File> {
        val dirsFirst = compareBy<File> { !it.isDirectory }
        return when (sortMode) {
            SORT_SIZE -> files.sortedWith(dirsFirst.thenBy { it.length() }.thenBy { it.name.lowercase() })
            SORT_TYPE -> files.sortedWith(
                dirsFirst
                    .thenBy { if (it.isDirectory) "" else it.extension.lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            else -> files.sortedWith(dirsFirst.thenBy { it.name.lowercase() })
        }
    }

    private fun matchesFilter(f: File): Boolean {
        val mode = filterMode ?: return true
        if (f.isDirectory) return false
        val ext = f.extension.lowercase()
        return when (mode) {
            FILTER_APK -> ext == APK_EXT
            FILTER_DOC -> ext in DOC_EXTS
            FILTER_ARCHIVE -> ext in ARCHIVE_EXTS
            else -> true
        }
    }

    /** Root penyimpanan yang di-scan saat filter aktif. */
    private fun storageRootsForFilter(): List<File> {
        val roots = mutableListOf<File>()
        val internal = primaryStorageRoot()
        if (internal.canRead()) roots.add(internal)
        findSecondaryStorage()?.let { if (it.canRead()) roots.add(it) }
        return roots.distinctBy { it.absolutePath }
    }

    /**
     * Cari file cocok filter dari root Internal + External (bukan folder aktif).
     * Folder sistem berat (Android/data, obb, cache, …) dilewati.
     */
    private fun collectFilteredFilesFromStorageRoots(maxDepth: Int = 10): List<File> {
        val out = mutableListOf<File>()
        val skipNames = setOf(
            "Android", "data", "obb", ".thumbnails", "cache", "lost+found",
            ".trash", "Trash", "thumbnails"
        )
        fun walk(d: File, depth: Int) {
            if (depth > maxDepth) return
            val children = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (c in children) {
                try {
                    if (c.isFile && matchesFilter(c)) {
                        out.add(c)
                    } else if (c.isDirectory && !c.name.startsWith(".")) {
                        if (c.name in skipNames) continue
                        // Jangan masuk Android/data & Android/obb (berat + sering tidak terbaca)
                        if (c.name == "data" || c.name == "obb") {
                            val parent = c.parentFile?.name
                            if (parent.equals("Android", true)) continue
                        }
                        walk(c, depth + 1)
                    }
                } catch (_: Exception) { /* skip unreadable entry */ }
            }
        }
        for (root in storageRootsForFilter()) {
            walk(root, 0)
        }
        return sortFiles(out.toTypedArray())
    }

    private fun onFileActivated(f: File) {
        when {
            f.isDirectory -> {
                filterMode = null
                openDirectory(f, focusFirst = true)
            }
            f.extension.equals(APK_EXT, ignoreCase = true) -> installApk(f)
            else -> {
                try {
                    val uri = FileProvider.getUriForFile(
                        activity, "${activity.packageName}.fileprovider", f
                    )
                    val mime = activity.contentResolver.getType(uri) ?: "*/*"
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    Toast.makeText(activity, f.name, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                        Toast.makeText(
                            activity,
                            "Izinkan instal dari Digi Home, lalu buka APK lagi",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    } catch (_: Exception) { }
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Gagal buka installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Render ───

    private fun renderList(files: List<File>) {
        for (f in files) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_files_list_item)
                setPadding(dp(12), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                // Icon badge
                addView(TextView(activity).apply {
                    val (label, bg) = when {
                        f.isDirectory -> "DIR" to R.drawable.bg_files_icon_folder
                        f.extension.equals(APK_EXT, true) -> "APK" to R.drawable.bg_files_icon_apk
                        else -> fileTypeLabel(f).take(3) to R.drawable.bg_files_icon_file
                    }
                    text = label
                    textSize = 10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (f.isDirectory) activity.getColor(R.color.accent_sky) else colorWhite90)
                    background = activity.getDrawable(bg)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        marginEnd = dp(12)
                    }
                })
                // Name + meta
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(activity).apply {
                        text = f.name
                        setTextColor(colorWhite)
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(activity).apply {
                        text = when {
                            f.isDirectory -> "Folder"
                            filterMode != null -> {
                                val parent = f.parentFile?.name ?: ""
                                "${fileTypeLabel(f)} · ${formatFileSize(f.length())} · $parent"
                            }
                            else -> "${fileTypeLabel(f)} · ${formatFileSize(f.length())}"
                        }
                        setTextColor(colorWhite50)
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                setOnClickListener { onFileActivated(f) }
            }
            filesList.addView(row)
        }
    }

    private fun renderGrid(files: List<File>) {
        val cols = PrefsKeys.FILES_GRID_COLS
        var rowLayout: LinearLayout? = null
        for ((index, f) in files.withIndex()) {
            if (index % cols == 0) {
                rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                filesList.addView(rowLayout)
            }
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                isFocusable = true
                background = activity.getDrawable(R.drawable.bg_files_grid_item)
                setPadding(dp(10), dp(14), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5); marginEnd = dp(5)
                }
                val (label, bg) = when {
                    f.isDirectory -> "DIR" to R.drawable.bg_files_icon_folder
                    f.extension.equals(APK_EXT, true) -> "APK" to R.drawable.bg_files_icon_apk
                    else -> fileTypeLabel(f).take(3) to R.drawable.bg_files_icon_file
                }
                addView(TextView(activity).apply {
                    text = label
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (f.isDirectory) activity.getColor(R.color.accent_sky) else colorWhite90)
                    background = activity.getDrawable(bg)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                })
                addView(TextView(activity).apply {
                    text = f.name
                    setTextColor(colorWhite90)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                })
                setOnClickListener { onFileActivated(f) }
            }
            rowLayout!!.addView(cell)
        }
        val remainder = files.size % cols
        if (remainder != 0 && rowLayout != null) {
            for (i in remainder until cols) {
                rowLayout.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        marginStart = dp(5); marginEnd = dp(5)
                    }
                    isFocusable = false
                })
            }
        }
    }

    private fun msgView(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(colorWhite50)
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        setPadding(dp(16), dp(48), dp(16), dp(48))
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun fileTypeLabel(f: File): String {
        if (f.isDirectory) return "Folder"
        val ext = f.extension.lowercase()
        return if (ext.isBlank()) "File" else ext.uppercase()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    // ─── Focus helpers (dipakai dispatchKeyEvent host) ───

    fun focusFirstItem() {
        fun find(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i)
                if (c.isFocusable) return c
                if (c is ViewGroup) find(c)?.let { return it }
            }
            return null
        }
        (find(filesList) ?: filesMenuButton).requestFocus()
    }

    fun focusMenuButton() {
        filesMenuButton.requestFocus()
    }

    fun isToolbar(view: View?): Boolean {
        return view === filesMenuButton || view === filesUpButton ||
            view === filesViewToggle || view === filesSortButton || view === filesPath
    }

    fun isInList(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === filesList) return true
            v = v.parent as? View
        }
        return false
    }

    fun isFirstRow(view: View): Boolean {
        if (filesList.childCount == 0) return true
        val firstRow = filesList.getChildAt(0)
        var v: View? = view
        while (v != null) {
            if (v === firstRow) return true
            if (v === filesList) return false
            v = v.parent as? View
        }
        return false
    }
}
