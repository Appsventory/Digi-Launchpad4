package com.appsventory.digiapps.launchpad4.ui.util

import android.view.ViewGroup

/**
 * Sumber kebenaran TUNGGAL untuk "siapa yang sedang mengunci fokus D-pad ke satu overlay
 * (sidebar/panel)". Sebelum ini, Settings-sidebar dan Files-sidebar sama-sama langsung
 * set `mainContent.descendantFocusability` sendiri-sendiri — kalau overlay A tutup while
 * overlay B masih terbuka, si A bisa tidak sengaja "buka kunci" background padahal B masih
 * butuh dikunci, dan fokus D-pad bisa "kabur" keluar dari frame overlay yang aktif.
 *
 * Sekarang setiap overlay WAJIB lewat [lock]/[unlock] dengan token unik (biasanya `this`
 * controller-nya) — root cuma benar-benar di-unblock kalau TIDAK ADA overlay lain yang
 * masih pegang lock.
 */
object FocusLock {
    private val holders = HashMap<ViewGroup, MutableSet<Any>>()

    fun lock(root: ViewGroup?, owner: Any) {
        if (root == null) return
        val set = holders.getOrPut(root) { mutableSetOf() }
        set.add(owner)
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    fun unlock(root: ViewGroup?, owner: Any) {
        if (root == null) return
        val set = holders[root] ?: return
        set.remove(owner)
        if (set.isEmpty()) {
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }
}
