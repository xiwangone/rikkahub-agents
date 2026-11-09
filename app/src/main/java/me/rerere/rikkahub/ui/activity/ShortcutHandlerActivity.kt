package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import java.io.File

class ShortcutHandlerActivity : ComponentActivity() {
    private var photoURI: Uri? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                finish()
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                photoURI?.let {
                    val intent =
                        Intent(this, RouteActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, it)
                        }
                    startActivity(intent)
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只接受 manifest 里声明的快捷方式入口 VIEW rikkahub://shortcut，
        // 其他 action 或 data 直接结束，不拉起相机授权。
        val data = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || data?.scheme != "rikkahub" || data?.host != "shortcut") {
            finish()
            return
        }
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "shortcut_camera_image_${System.currentTimeMillis()}.jpg")
        photoURI = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", imageFile)
        photoURI?.let {
            takePictureLauncher.launch(it)
        } ?: finish()
    }
}
