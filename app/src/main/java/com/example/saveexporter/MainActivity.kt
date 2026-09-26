package com.example.saveexporter

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var pendingExportFile: File? = null

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmThenImportFromUri(uri)
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) saveExportedFileToUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Package: $packageName\n\n" +
                "Export: chọn thư mục/file cần backup, lưu thành bản có ngày giờ.\n" +
                "Import: lấy từ file ngoài hoặc từ 1 bản backup đã export trước đó."
            textSize = 15f
        }

        val exportButton = Button(this).apply {
            text = "Export Save"
            setOnClickListener { exportSave() }
        }

        val importButton = Button(this).apply {
            text = "Import Save"
            setOnClickListener { chooseImportSource() }
        }

        val saveElsewhereButton = Button(this).apply {
            text = "Lưu bản export gần nhất vào vị trí khác"
            setOnClickListener {
                val file = pendingExportFile
                if (file == null) {
                    statusText.text = "Chưa có bản export nào trong phiên này. Hãy Export trước."
                } else {
                    createDocumentLauncher.launch(file.name)
                }
            }
        }

        layout.addView(exportButton)
        layout.addView(importButton)
        layout.addView(saveElsewhereButton)
        layout.addView(statusText)
        setContentView(layout)
    }

    // ---------------------- EXPORT ----------------------

    private fun exportSave() {
        val internalDataDir = filesDir.parentFile
        if (internalDataDir == null) {
            statusText.text = "Không tìm thấy thư mục data nội bộ"
            return
        }
        showFileBrowser(internalDataDir)
    }

    /**
     * Trình duyệt file: tap để đi vào thư mục, nhấn giữ để chọn/bỏ chọn
     * file hoặc cả thư mục (không cần đi vào bên trong). Có thể chọn
     * nhiều mục ở nhiều cấp thư mục khác nhau trước khi export.
     */
    private fun showFileBrowser(root: File) {
        val selected = mutableSetOf<File>()
        var currentDir = root
        var entriesInView: List<File?> = emptyList() // null = mục ".." để lên cấp cha

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pathText = TextView(this).apply {
            textSize = 12f
            setPadding(24, 16, 24, 8)
        }

        val hintText = TextView(this).apply {
            text = "Chạm: mở thư mục / chọn file · Giữ: chọn để export hoặc xóa"
            textSize = 11f
            setPadding(24, 0, 24, 8)
        }

        val listHeightPx = (resources.displayMetrics.heightPixels * 0.5).toInt()
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeightPx)
        }

        val exportButton = Button(this).apply { text = "Export đã chọn (0)" }

        container.addView(pathText)
        container.addView(hintText)
        container.addView(listView)
        container.addView(exportButton)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Duyệt & chọn để export")
            .setView(container)
            .setNegativeButton("Đóng", null)
            .create()

        fun refresh() {
            val relPath = currentDir.relativeTo(root).path
            pathText.text = "📂 /${if (relPath.isEmpty()) "" else relPath}"

            val children = currentDir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?: emptyList()

            val labels = mutableListOf<String>()
            val entries = mutableListOf<File?>()

            if (currentDir != root) {
                labels.add(".. (lên thư mục cha)")
                entries.add(null)
            }
            for (entry in children) {
                val mark = if (selected.contains(entry)) "✓ " else "   "
                val icon = if (entry.isDirectory) "📁" else "📄"
                val sizeLabel = if (!entry.isDirectory) "  (${formatSize(entry.length())})" else ""
                labels.add("$mark$icon ${entry.name}$sizeLabel")
                entries.add(entry)
            }

            entriesInView = entries
            adapter.clear()
            adapter.addAll(labels)
            adapter.notifyDataSetChanged()
            exportButton.text = "Export đã chọn (${selected.size})"
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val entry = entriesInView[position]) {
                null -> {
                    currentDir = currentDir.parentFile ?: root
                    refresh()
                }
                else -> if (entry.isDirectory) {
                    currentDir = entry
                    refresh()
                } else {
                    if (!selected.remove(entry)) selected.add(entry)
                    refresh()
                }
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entriesInView[position] ?: return@setOnItemLongClickListener true
            val toggleLabel = if (selected.contains(entry)) "Bỏ chọn" else "Chọn để export"
            AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(arrayOf(toggleLabel, "Xóa vĩnh viễn")) { _, which ->
                    when (which) {
                        0 -> {
                            if (!selected.remove(entry)) selected.add(entry)
                            refresh()
                        }
                        1 -> {
                            val warnExtra = if (entry.isDirectory) " và toàn bộ nội dung bên trong nó" else ""
                            AlertDialog.Builder(this)
                                .setTitle("Xác nhận xóa")
                                .setMessage("Xóa \"${entry.name}\"$warnExtra? Không thể hoàn tác.")
                                .setPositiveButton("Xóa") { _, _ ->
                                    Thread {
                                        val success = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                                        selected.remove(entry)
                                        runOnUiThread {
                                            if (!success) {
                                                Toast.makeText(this, "Không xóa được \"${entry.name}\"", Toast.LENGTH_SHORT).show()
                                            }
                                            refresh()
                                        }
                                    }.start()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    }
                }
                .show()
            true
        }

        exportButton.setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(this, "Chưa chọn file/thư mục nào", Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                statusText.text = "Đang chuẩn bị export..."
                Thread {
                    val filesToExport = selected.flatMap { f ->
                        if (f.isFile) listOf(f) else f.walkTopDown().filter { it.isFile }.toList()
                    }
                    val relPaths = filesToExport.map { it.relativeTo(root).path }.distinct()
                    if (relPaths.isEmpty()) {
                        runOnUiThread { statusText.text = "Các thư mục đã chọn không có file nào." }
                    } else {
                        doExport(relPaths)
                    }
                }.start()
            }
        }

        refresh()
        dialog.show()
    }

    private fun doExport(selectedRelPaths: List<String>) {
        statusText.text = "Đang export..."
        Thread {
            try {
                val internalDataDir = filesDir.parentFile
                    ?: throw IllegalStateException("Không tìm thấy thư mục data nội bộ")

                val exportRoot = getExternalFilesDir(null)
                    ?: throw IllegalStateException("Không tìm thấy external storage")

                val backupsDir = File(exportRoot, "backups")
                backupsDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val stagingFolder = File(exportRoot, "staging_$timestamp")
                stagingFolder.deleteRecursively()
                stagingFolder.mkdirs()

                for (relPath in selectedRelPaths) {
                    val src = File(internalDataDir, relPath)
                    val dst = File(stagingFolder, relPath)
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }

                val zipFile = File(backupsDir, "save_$timestamp.zip")
                zipDirectory(stagingFolder, zipFile)
                stagingFolder.deleteRecursively()
                pendingExportFile = zipFile

                runOnUiThread {
                    statusText.text = "Xong!\n\n" +
                        "Đã export ${selectedRelPaths.size} file\n\n" +
                        "File backup: ${zipFile.absolutePath}\n\n" +
                        "Lấy ra máy tính bằng:\n" +
                        "adb pull \"${zipFile.absolutePath}\""

                    AlertDialog.Builder(this)
                        .setTitle("Export xong")
                        .setMessage("Bạn có muốn lưu file zip này vào một vị trí cụ thể (Tải xuống, Google Drive, thẻ nhớ ngoài...) không?")
                        .setPositiveButton("Chọn vị trí lưu") { _, _ ->
                            createDocumentLauncher.launch(zipFile.name)
                        }
                        .setNegativeButton("Để sau", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi export: ${e.message}" }
            }
        }.start()
    }

    // ---------------------- SAVE TO CUSTOM LOCATION ----------------------

    private fun saveExportedFileToUri(uri: Uri) {
        val file = pendingExportFile
        if (file == null) {
            statusText.text = "Không tìm thấy file export để lưu."
            return
        }
        statusText.text = "Đang lưu vào vị trí đã chọn..."
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Không mở được vị trí lưu")

                runOnUiThread {
                    statusText.text = "Đã lưu \"${file.name}\" vào vị trí bạn chọn."
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi khi lưu: ${e.message}" }
            }
        }.start()
    }

    // ---------------------- IMPORT ----------------------

    private fun chooseImportSource() {
        AlertDialog.Builder(this)
            .setTitle("Import từ đâu?")
            .setItems(arrayOf("Chọn file / zip khác", "Chọn từ backup đã export trong app")) { _, which ->
                when (which) {
                    0 -> pickFileLauncher.launch("*/*")
                    1 -> showBackupList()
                }
            }
            .show()
    }

    private fun showBackupList() {
        val exportRoot = getExternalFilesDir(null)
        val backupsDir = File(exportRoot, "backups")
        val backups = backupsDir.listFiles()
            ?.filter { it.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (backups.isEmpty()) {
            statusText.text = "Chưa có bản backup nào được export trong app này."
            return
        }

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
        val labels = backups.map { "${it.name}\n${dateFormat.format(Date(it.lastModified()))}  (${formatSize(it.length())})" }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn bản backup để import")
            .setItems(labels) { _, which ->
                confirmThenImportFromFile(backups[which])
            }
            .show()
    }

    private fun confirmThenImportFromUri(uri: Uri) {
        val internalDataDir = filesDir.parentFile
        if (internalDataDir == null) {
            statusText.text = "Không tìm thấy thư mục data nội bộ"
            return
        }
        chooseDestinationFolder(internalDataDir) { targetDir, label ->
            AlertDialog.Builder(this)
                .setTitle("Xác nhận Import")
                .setMessage("Import vào \"$label\" sẽ ghi đè dữ liệu trùng tên tại đó. Tiếp tục?")
                .setPositiveButton("Import") { _, _ -> importFromUri(uri, targetDir) }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun confirmThenImportFromFile(file: File) {
        val internalDataDir = filesDir.parentFile
        if (internalDataDir == null) {
            statusText.text = "Không tìm thấy thư mục data nội bộ"
            return
        }
        chooseDestinationFolder(internalDataDir) { targetDir, label ->
            AlertDialog.Builder(this)
                .setTitle("Xác nhận Import")
                .setMessage("Import \"${file.name}\" vào \"$label\" sẽ ghi đè dữ liệu trùng tên tại đó. Tiếp tục?")
                .setPositiveButton("Import") { _, _ -> importFromFile(file, targetDir) }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun chooseDestinationFolder(root: File, onChosen: (File, String) -> Unit) {
        Thread {
            val folders = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
            runOnUiThread {
                val labels = mutableListOf("📁 (Thư mục gốc - $packageName)")
                labels.addAll(folders.map { it.name })
                AlertDialog.Builder(this)
                    .setTitle("Chọn thư mục để import vào")
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == 0) {
                            onChosen(root, "(Thư mục gốc)")
                        } else {
                            val folder = folders[which - 1]
                            onChosen(folder, folder.name)
                        }
                    }
                    .show()
            }
        }.start()
    }

    private fun importFromUri(uri: Uri, targetDir: File) {
        statusText.text = "Đang import..."
        Thread {
            try {
                targetDir.mkdirs()

                val displayName = getFileName(uri) ?: "imported_file"
                val looksLikeZip = displayName.endsWith(".zip", ignoreCase = true) || isZipStream(uri)

                if (looksLikeZip) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        unzipStreamInto(input, targetDir)
                    }
                } else {
                    val outFile = File(targetDir, displayName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }

                runOnUiThread {
                    statusText.text = "Import thành công vào:\n${targetDir.absolutePath}\n\n" +
                        "Hãy force stop rồi mở lại game."
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi import: ${e.message}" }
            }
        }.start()
    }

    private fun importFromFile(file: File, targetDir: File) {
        statusText.text = "Đang import..."
        Thread {
            try {
                targetDir.mkdirs()

                FileInputStream(file).use { input ->
                    unzipStreamInto(input, targetDir)
                }

                runOnUiThread {
                    statusText.text = "Import \"${file.name}\" thành công vào:\n${targetDir.absolutePath}\n\n" +
                        "Hãy force stop rồi mở lại game."
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi import: ${e.message}" }
            }
        }.start()
    }

    // ---------------------- HELPERS ----------------------

    private fun unzipStreamInto(input: InputStream, targetDir: File) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun calculateSize(file: File): Long {
        return if (file.isFile) {
            file.length()
        } else {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun isZipStream(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(2)
                val read = input.read(header)
                read == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
