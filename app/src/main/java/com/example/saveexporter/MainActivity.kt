package com.example.saveexporter

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Export: sao lưu toàn bộ data app ra ngoài.\nImport: chọn 1 file (hoặc zip) để đưa vào lại data app."
            textSize = 15f
        }

        val exportButton = Button(this).apply {
            text = "Export Save"
            setOnClickListener { exportSave() }
        }

        val importButton = Button(this).apply {
            text = "Import Save"
            setOnClickListener { pickFileLauncher.launch("*/*") }
        }

        layout.addView(exportButton)
        layout.addView(importButton)
        layout.addView(statusText)
        setContentView(layout)
    }

    private fun exportSave() {
        statusText.text = "Đang export..."
        Thread {
            try {
                // Thư mục /data/data/<package>/ của chính app này
                val internalDataDir = filesDir.parentFile
                    ?: throw IllegalStateException("Không tìm thấy thư mục data nội bộ")

                // Thư mục ngoài, không cần xin quyền: /sdcard/Android/data/<package>/files/
                val exportRoot = getExternalFilesDir(null)
                    ?: throw IllegalStateException("Không tìm thấy external storage")

                val exportFolder = File(exportRoot, "exported_save")
                exportFolder.deleteRecursively()
                exportFolder.mkdirs()

                internalDataDir.copyRecursively(exportFolder, overwrite = true)

                val zipFile = File(exportRoot, "exported_save.zip")
                zipDirectory(exportFolder, zipFile)

                runOnUiThread {
                    statusText.text = "Xong!\n\n" +
                        "Thư mục: ${exportFolder.absolutePath}\n" +
                        "File zip: ${zipFile.absolutePath}\n\n" +
                        "Dùng lệnh sau trên máy tính để lấy ra:\n" +
                        "adb pull \"${zipFile.absolutePath}\""
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Lỗi: ${e.message}"
                }
            }
        }.start()
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
                        ZipInputStream(input).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val outFile = File(internalDataDir, entry.name)
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
                    runOnUiThread {
                        statusText.text = "Import zip thành công vào:\n${internalDataDir.absolutePath}\n\nHãy mở lại game (force stop trước nếu đang chạy)."
                    }
                } else {
                    val outFile = File(internalDataDir, displayName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                    runOnUiThread {
                        statusText.text = "Import file thành công:\n${outFile.absolutePath}\n\nHãy mở lại game (force stop trước nếu đang chạy)."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Lỗi import: ${e.message}"
                }
            }
        }.start()
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
