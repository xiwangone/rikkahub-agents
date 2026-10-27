package me.rerere.rikkahub.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException as ZxingNotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.rikkahub.data.log.AppLog

/**
 * ZXing 版的扫码 Activity，取代 Quickie 的 `ScanQRCodeActivity`（它背后是 ML Kit
 * `com.google.mlkit:barcode-scanning`，为这一个功能背着 ~4.72MB 的 `libbarhopper_v3.so`
 * 和 ~0.83MB 的模型文件）。
 *
 * 取帧走 CameraX（`camera-core` 本来就在依赖里，Quickie 也用它），解码走仓内**已有**的
 * `com.google.zxing:core` —— 二维码「生成」（`ui/components/ui/QRCode.kt`）和「从相册图片
 * 解析」（`utils/ImageUtils.kt:233 decodeQRCodeFromBitmap`）用的就是同一个库、同一组类
 * （`MultiFormatReader` + `RGBLuminanceSource` + `HybridBinarizer`），所以这里不新增解码器依赖。
 *
 * 相机权限沿用清单里已有的 `android.permission.CAMERA`（`AndroidManifest.xml:12`），运行时申请
 * 时机与被替换掉的 Quickie 一致：进界面申请、拒绝即回传 [QRResult.QRMissingPermission]，不新增权限。
 */
class QRScanActivity : ComponentActivity() {
    companion object {
        private const val TAG = "QRScanActivity"
        private const val REQUEST_CAMERA = 1001

        const val EXTRA_OUTCOME = "me.rerere.rikkahub.ui.scanner.OUTCOME"
        const val EXTRA_RAW_VALUE = "me.rerere.rikkahub.ui.scanner.RAW_VALUE"
        const val EXTRA_ERROR = "me.rerere.rikkahub.ui.scanner.ERROR"

        const val OUTCOME_CANCELED = 0
        const val OUTCOME_SUCCESS = 1
        const val OUTCOME_ERROR = 2
        const val OUTCOME_NO_PERMISSION = 3
    }

    private val finished = AtomicBoolean(false)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val multiFormatReader = MultiFormatReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // 端侧 QR 常见的低对比度/小尺寸场景靠它兜住；代价是单帧耗时上升，
        // 但 ImageAnalysis 用 KEEP_ONLY_LATEST，慢一帧只是丢帧，不会堆积内存。
        DecodeHintType.TRY_HARDER to true,
    )

    private var previewView: PreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 相机预览会熄屏即废，解码窗口很短，保持亮屏；取景框的底色由 Theme.Rikkahub.Scanner 给
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                preview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(createViewfinder())
        }
        setContentView(root)
        previewView = preview

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        // 拒绝（含「不再询问」）立刻带原因退出，不留在一片黑的界面里 —— 与 Quickie 行为等价
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finishWith(QRResult.QRMissingPermission)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdownNow()
        previewView = null
        super.onDestroy()
    }

    // CameraX 绑定失败的诱因（无后置摄头 / 被占用 / 初始化失败）无法穷举，统一抬到上层弹 toast
    @Suppress("TooGenericExceptionCaught")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = try {
                    providerFuture.get()
                } catch (error: Exception) {
                    finishWith(QRResult.QRError(error))
                    return@addListener
                }
                val view = previewView
                if (view == null || isFinishing || isDestroyed) {
                    provider.unbindAll()
                    return@addListener
                }
                val preview = CameraPreview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { image -> analyze(image) } }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                } catch (error: Exception) {
                    // 无后置摄像头 / 相机被其他进程占用：把异常交回上层弹既有 toast，
                    // 不在此处自己造提示文案（避免新增 i18n 键）
                    finishWith(QRResult.QRError(error))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /**
     * 只取 Y 平面（灰度）—— `PlanarYUVLuminanceSource` 吃的就是它，比先转 Bitmap 再
     * `RGBLuminanceSource` 省掉一次全帧色空间转换与一块 int[] 分配。
     */
    // 解码失败也要分两类：NotFound 是“这一帧没码”（高频、正常）；其余异常记录后继续下一帧
    @Suppress("TooGenericExceptionCaught")
    private fun analyze(image: ImageProxy) {
        if (finished.get()) {
            image.close()
            return
        }
        if (image.format != ImageFormat.YUV_420_888) {
            // 不是我们要求的格式就丢掉这一帧，让下一帧继续
            image.close()
            return
        }
        var decoded: String? = null
        try {
            decoded = decodeYuv(image)
        } catch (_: ZxingNotFoundException) {
            // 绝大多数帧都到这里：没找到码，继续等下一帧
        } catch (error: Exception) {
            AppLog.w(TAG, "QR decode failed", error)
        } finally {
            image.close()
        }
        val text = decoded
        if (text != null) {
            runOnUiThread {
                finishWith(QRResult.QRSuccess(ScannedCode(text)))
            }
        }
    }

    private fun decodeYuv(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        if (rowStride < width || width <= 0) return null
        // 最后一行常常缺行尾填充：按可读取字节数夹一下行数，避免 BufferUnderflowException
        val height = minOf(image.height, buffer.limit() / rowStride)
        if (height <= 0) return null

        // 按 rowStride 重排成紧凑的 width*height：PlanarYUVLuminanceSource 的行内偏移
        // 只按 dataWidth 递进，直接塞带填充的原始平面会整幅斜切
        val y = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(y, row * width, width)
        }
        val source = PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return multiFormatReader.decode(bitmap, hints).text
    }

    /** 取景框：纯代码画的方框，不引入 drawable / string 资源（原 Quickie 的界面文案也随之消失）。 */
    private fun createViewfinder(): FrameLayout {
        val density = resources.displayMetrics.density
        val drawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((4 * density).toInt(), Color.WHITE)
        }
        val side = (220 * density).toInt()
        return FrameLayout(this).apply {
            background = drawable
            layoutParams = FrameLayout.LayoutParams(side, side, Gravity.CENTER)
        }
    }

    private fun finishWith(result: QRResult) {
        // 幂等：解码线程、权限回调、返回键可能在同一瞬间都想到要收尾
        if (!finished.compareAndSet(false, true)) return
        val intent = Intent().apply {
            val outcome = when (result) {
                is QRResult.QRSuccess -> {
                    putExtra(EXTRA_RAW_VALUE, result.content.rawValue)
                    OUTCOME_SUCCESS
                }

                is QRResult.QRError -> {
                    putExtra(EXTRA_ERROR, result.error.message ?: result.error.javaClass.simpleName)
                    OUTCOME_ERROR
                }

                QRResult.QRMissingPermission -> OUTCOME_NO_PERMISSION
                QRResult.QRUserCanceled -> OUTCOME_CANCELED
            }
            putExtra(EXTRA_OUTCOME, outcome)
        }
        setResult(RESULT_OK, intent)
        finish()
    }
}
