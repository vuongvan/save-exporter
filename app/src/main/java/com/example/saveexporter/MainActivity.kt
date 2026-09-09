package com.example.saveexporter

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
        statusText.text = "Đang quét dữ liệu..."
        Thread {
            val internalDataDir = filesDir.parentFile
            if (internalDataDir == null) {
                runOnUiThread { statusText.text = "Không tìm thấy thư mục data nội bộ" }
                return@Thread
            }

            val items = internalDataDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (items.isEmpty()) {
                runOnUiThread { statusText.text = "Không có gì để export." }
                return@Thread
            }

            val sizes = items.map { calculateSize(it) }
            val labels = items.mapIndexed { i, f -> "${f.name}  (${formatSize(sizes[i])})" }.toTypedArray()
            val checkedItems = BooleanArray(items.size) { true }

            runOnUiThread {
                statusText.text = "Bước 1/2: chọn thư mục/file cấp ngoài"
                AlertDialog.Builder(this)
                    .setTitle("Bước 1/2: chọn thư mục/file")
                    .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                    }
                    .setPositiveButton("Tiếp tục") { _, _ ->
                        val selectedTopLevel = items.filterIndexed { index, _ -> checkedItems[index] }
                        if (selectedTopLevel.isEmpty()) {
                            statusText.text = "Chưa chọn gì để export."
                        } else {
                            chooseFilesToExport(internalDataDir, selectedTopLevel)
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }.start()
    }

    private fun chooseFilesToExport(internalDataDir: File, selectedTopLevel: List<File>) {
        statusText.text = "Đang liệt kê từng file..."
        Thread {
            val allFiles = selectedTopLevel.flatMap { top ->
                if (top.isFile) listOf(top) else top.walkTopDown().filter { it.isFile }.toList()
            }.sortedBy { it.relativeTo(internalDataDir).path }

            if (allFiles.isEmpty()) {
                runOnUiThread { statusText.text = "Không có file nào trong lựa chọn." }
                return@Thread
            }

            val relPaths = allFiles.map { it.relativeTo(internalDataDir).path }
            val sizes = allFiles.map { it.length() }
            val labels = relPaths.mapIndexed { i, p -> "$p  (${formatSize(sizes[i])})" }.toTypedArray()
            val checkedItems = BooleanArray(relPaths.size) { true }

            runOnUiThread {
                statusText.text = "Bước 2/2: bỏ chọn file không cần (${relPaths.size} file)"
                AlertDialog.Builder(this)
                    .setTitle("Bước 2/2: chọn file cụ thể")
                    .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                    }
                    .setPositiveButton("Export") { _, _ ->
                        val selected = relPaths.filterIndexed { index, _ -> checkedItems[index] }
                        if (selected.isEmpty()) {
                            statusText.text = "Chưa chọn file nào để export."
                        } else {
                            doExport(selected)
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }.start()
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
