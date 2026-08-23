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

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) confirmThenImportFromUri(uri)
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

        layout.addView(exportButton)
        layout.addView(importButton)
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
                statusText.text = "Chọn thư mục/file cần export"
                AlertDialog.Builder(this)
                    .setTitle("Chọn thư mục/file để export")
                    .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                    }
                    .setPositiveButton("Export") { _, _ ->
                        val selected = items.filterIndexed { index, _ -> checkedItems[index] }.map { it.name }
                        if (selected.isEmpty()) {
                            statusText.text = "Chưa chọn gì để export."
                        } else {
                            doExport(selected)
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }.start()
    }

    private fun doExport(selectedNames: List<String>) {
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

                for (name in selectedNames) {
                    val src = File(internalDataDir, name)
                    val dst = File(stagingFolder, name)
                    src.copyRecursively(dst, overwrite = true)
                }

                val zipFile = File(backupsDir, "save_$timestamp.zip")
                zipDirectory(stagingFolder, zipFile)
                stagingFolder.deleteRecursively()

                runOnUiThread {
                    statusText.text = "Xong!\n\n" +
                        "Đã export: ${selectedNames.joinToString(", ")}\n\n" +
                        "File backup: ${zipFile.absolutePath}\n\n" +
                        "Lấy ra máy tính bằng:\n" +
                        "adb pull \"${zipFile.absolutePath}\""
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi export: ${e.message}" }
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
        AlertDialog.Builder(this)
            .setTitle("Xác nhận Import")
            .setMessage("Thao tác này sẽ ghi đè lên dữ liệu hiện tại của app. Tiếp tục?")
            .setPositiveButton("Import") { _, _ -> importFromUri(uri) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirmThenImportFromFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận Import")
            .setMessage("Import \"${file.name}\" sẽ ghi đè lên dữ liệu hiện tại của app. Tiếp tục?")
            .setPositiveButton("Import") { _, _ -> importFromFile(file) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        statusText.text = "Đang import..."
        Thread {
            try {
                val internalDataDir = filesDir.parentFile
                    ?: throw IllegalStateException("Không tìm thấy thư mục data nội bộ")

                val displayName = getFileName(uri) ?: "imported_file"
                val looksLikeZip = displayName.endsWith(".zip", ignoreCase = true) || isZipStream(uri)

                if (looksLikeZip) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        unzipStreamInto(input, internalDataDir)
                    }
                } else {
                    val outFile = File(internalDataDir, displayName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }

                runOnUiThread {
                    statusText.text = "Import thành công vào:\n${internalDataDir.absolutePath}\n\n" +
                        "Hãy force stop rồi mở lại game."
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lỗi import: ${e.message}" }
            }
        }.start()
    }

    private fun importFromFile(file: File) {
        statusText.text = "Đang import..."
        Thread {
            try {
                val internalDataDir = filesDir.parentFile
                    ?: throw IllegalStateException("Không tìm thấy thư mục data nội bộ")

                FileInputStream(file).use { input ->
                    unzipStreamInto(input, internalDataDir)
                }

                runOnUiThread {
                    statusText.text = "Import \"${file.name}\" thành công vào:\n${internalDataDir.absolutePath}\n\n" +
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
