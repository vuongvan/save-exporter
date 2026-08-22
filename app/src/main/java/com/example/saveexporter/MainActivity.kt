package com.example.saveexporter

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Nhấn nút để export toàn bộ dữ liệu app (bao gồm file save)."
            textSize = 15f
        }

        val exportButton = Button(this).apply {
            text = "Export Save"
            setOnClickListener { exportSave() }
        }

        layout.addView(exportButton)
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
}
