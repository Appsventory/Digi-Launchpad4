package com.appsventory.digiapps.launchpad4

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.appsventory.digiapps.launchpad4.ui.files.FileManagerController

/**
 * File manager standalone — muncul sebagai "Digi Files" di chooser aplikasi lain
 * (VIEW / GET_CONTENT) dan bisa diluncurkan langsung (LAUNCHER).
 *
 * Semua logika browse/filter/sidebar ada di [FileManagerController]
 * (dipakai juga oleh tab Files di [MainActivity]).
 */
class FilesActivity : Activity() {

    private lateinit var fileManager: FileManagerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_files)

        val lockRoot = findViewById<ViewGroup>(R.id.filesMainContent)
        fileManager = FileManagerController(
            this,
            focusLockRoot = lockRoot,
            standalone = true,
        )
        fileManager.setup()
        fileManager.resolveStartDir(intent)
        fileManager.openDirectory(fileManager.currentDir, focusFirst = true)
    }

    @Deprecated("Deprecated in Java")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (fileManager.handleBack()) return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // Sidebar terbuka: biarkan fokus natural (focusLockRoot sudah di-block)
        if (fileManager.isSidebarOpen) return super.dispatchKeyEvent(event)

        val focused = currentFocus
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (fileManager.isToolbar(focused)) return true
            if (focused != null && fileManager.isInList(focused) && fileManager.isFirstRow(focused)) {
                fileManager.focusMenuButton()
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (fileManager.isToolbar(focused)) {
                fileManager.focusFirstItem()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
