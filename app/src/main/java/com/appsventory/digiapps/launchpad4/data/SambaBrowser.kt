package com.appsventory.digiapps.launchpad4.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 client (hierynomus smbj).
 *
 * Penting: setiap TREE_CONNECT gagal bisa memutus transport.
 * Karena itu probe share dilakukan di koneksi terpisah, dan
 * [connect] selalu buka session fresh untuk share target.
 */
class SambaBrowser {

    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val isShareRoot: Boolean = false,
    )

    data class ShareRef(
        val name: String,
        val host: String,
        val share: String,
        val username: String = "",
        val password: String = "",
        val domain: String = "",
    )

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var current: ShareRef? = null
    /** Cache hasil listShares per host — hindari probe ulang saat Back ke root. */
    private var cachedShareList: List<String>? = null
    private var cachedShareHost: String? = null

    val isConnected: Boolean get() = session != null
    val hasDiskShare: Boolean get() = diskShare != null
    val connectedShare: ShareRef? get() = current

    private val config: SmbConfig by lazy {
        SmbConfig.builder()
            .withTimeout(12, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun buildAuth(ref: ShareRef): AuthenticationContext =
        if (ref.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(ref.username, ref.password.toCharArray(), ref.domain)
        }

    /** Tutup semua resource. Aman dipanggil berkali-kali. */
    @Synchronized
    fun disconnect() {
        try { diskShare?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        diskShare = null
        session = null
        connection = null
        client = null
        current = null
        // cache share list tetap — dipakai saat Back ke daftar share tanpa probe ulang
    }

    /** Buka session fresh ke host (tanpa TREE_CONNECT share). */
    private fun openSession(ref: ShareRef): Session {
        disconnect()
        val c = SMBClient(config)
        val conn = c.connect(ref.host.trim())
        val sess = conn.authenticate(buildAuth(ref))
        client = c
        connection = conn
        session = sess
        current = ref.copy(share = ref.share.trim())
        return sess
    }

    /**
     * Connect langsung ke share spesifik — selalu session baru.
     * Ini jalur paling stabil (bookmark dengan nama share terisi).
     */
    @Synchronized
    fun connect(ref: ShareRef) {
        val shareName = ref.share.trim().trim('\\').trim('/')
        require(shareName.isNotEmpty()) { "Nama share kosong — pakai connectHost + listShares" }
        val sess = openSession(ref)
        diskShare = sess.connectShare(shareName) as DiskShare
        current = ref.copy(share = shareName)
    }

    /** Connect host saja (siap listShares / pilih share). */
    @Synchronized
    fun connectHost(ref: ShareRef) {
        openSession(ref.copy(share = ""))
        diskShare = null
        current = ref.copy(share = "")
    }

    /**
     * TREE_CONNECT ke share setelah connectHost.
     * Kalau transport sudah mati, buka session ulang dulu.
     */
    @Synchronized
    fun connectShare(shareName: String) {
        val name = shareName.trim().trim('\\').trim('/')
        require(name.isNotEmpty()) { "Nama share kosong" }
        val ref = current ?: throw IllegalStateException("Belum terhubung ke host")

        try { diskShare?.close() } catch (_: Exception) {}
        diskShare = null

        fun treeConnect(sess: Session): DiskShare =
            sess.connectShare(name) as DiskShare

        try {
            val sess = session ?: openSession(ref)
            diskShare = treeConnect(sess)
        } catch (e: Exception) {
            // Transport putus / session invalid → fresh reconnect lalu coba lagi
            val sess = openSession(ref)
            diskShare = treeConnect(sess)
        }
        current = ref.copy(share = name)
    }

    /**
     * Enumerasi share di host.
     *
     * Probe dijalankan di **koneksi terpisah** supaya gagal TREE_CONNECT
     * tidak merusak session utama. Setelah selesai, session utama di-refresh.
     */
    @Synchronized
    fun listShares(forceRefresh: Boolean = false): List<String> {
        val ref = current ?: throw IllegalStateException("Belum terhubung ke host")
        val hostKey = ref.host.trim().lowercase()
        if (!forceRefresh && cachedShareList != null && cachedShareHost == hostKey) {
            return cachedShareList!!
        }
        val found = linkedMapOf<String, String>() // lowercase → display name

        fun addShare(name: String) {
            val n = name.trim()
            if (n.isEmpty() || n.endsWith("$")) return
            val key = n.lowercase()
            if (key !in found) found[key] = n
        }

        // Probe di koneksi disposable — tidak sentuh session utama
        val probes = listOf(
            "XStorage", "xstorage", "android", "Android", "media", "Media",
            "public", "share", "video", "music", "photo", "photos",
            "homes", "home", "data", "volume1", "Shared", "documents",
            "downloads", "usb", "disk", "storage", "files", "backup", "nas",
            "Multimedia", "Movies", "TV", "Series",
        )

        for (name in probes) {
            if (name.lowercase() in found) continue
            var probeClient: SMBClient? = null
            var probeConn: Connection? = null
            var probeSess: Session? = null
            try {
                probeClient = SMBClient(config)
                probeConn = probeClient.connect(ref.host.trim())
                probeSess = probeConn.authenticate(buildAuth(ref))
                val s = probeSess.connectShare(name)
                addShare(name)
                try { s.close() } catch (_: Exception) {}
            } catch (_: Exception) {
                // share tidak ada / akses ditolak — lanjut
            } finally {
                try { probeSess?.close() } catch (_: Exception) {}
                try { probeConn?.close() } catch (_: Exception) {}
                try { probeClient?.close() } catch (_: Exception) {}
            }
        }

        // Refresh session utama (bersih setelah probe)
        try {
            openSession(ref.copy(share = ""))
        } catch (_: Exception) {
            // biarkan; connectShare akan reconnect sendiri
        }

        val result = found.values.sortedBy { it.lowercase() }
        cachedShareList = result
        cachedShareHost = hostKey
        return result

    }

    @Synchronized
    fun list(path: String): List<Entry> {
        val share = diskShare ?: throw IllegalStateException("Belum pilih share")
        val listPath = path.trim().trim('/').replace('\\', '/')
        val out = mutableListOf<Entry>()
        val dirAttr = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        for (info in share.list(listPath)) {
            val n = info.fileName ?: continue
            if (n == "." || n == "..") continue
            val isDir = (info.fileAttributes and dirAttr) != 0L
            val childPath = if (listPath.isEmpty()) n else "$listPath/$n"
            out.add(
                Entry(
                    name = n,
                    path = childPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else info.endOfFile,
                )
            )
        }
        return out.sortedWith(
            compareBy<Entry> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    @Synchronized
    fun download(remotePath: String, dest: File) {
        val share = diskShare ?: throw IllegalStateException("Belum pilih share")
        val path = remotePath.trim().trim('/').replace('\\', '/')
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(
                SMB2ShareAccess.FILE_SHARE_READ,
                SMB2ShareAccess.FILE_SHARE_WRITE,
                SMB2ShareAccess.FILE_SHARE_DELETE,
            ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { remote ->
            remote.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        }
    }

    fun parentPath(path: String): String? {
        val p = path.trim().trim('/').replace('\\', '/')
        if (p.isEmpty()) return null
        val idx = p.lastIndexOf('/')
        return if (idx < 0) "" else p.substring(0, idx)
    }
}
