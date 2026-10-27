package me.rerere.rikkahub.ui.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * 扫码导入的契约与结果类型，取代原先的 `io.github.g00fy2.quickie`（Quickie）。
 *
 * 类型名与形状**刻意**与 Quickie 的 `QRResult` / `ScanQRCode` 对齐 —— 四个分支名、
 * `QRSuccess.content.rawValue` 的取值路径、以及契约输出为非空 `QRResult`（调用点没有 `!!`）
 * 都保持一致，于是 `SettingProviderPage.handleQRResult` 一行不用改，换扫描器只换两个 import。
 */
sealed interface QRResult {
    data class QRError(val error: Exception) : QRResult

    data object QRMissingPermission : QRResult

    data class QRSuccess(val content: ScannedCode) : QRResult

    data object QRUserCanceled : QRResult
}

/** 解码结果里被用到的只有 `rawValue`（沿用 ML Kit `Barcode` 的同名字段，便于对读）。 */
data class ScannedCode(
    val rawValue: String?,
)

/** 启动 [QRScanActivity] 并把跨 Activity 的 extras 还原成 [QRResult]。 */
class ScanQRCode : ActivityResultContract<Unit, QRResult>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Intent(context, QRScanActivity::class.java)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): QRResult {
        // 返回键 / 进程被杀都走 RESULT_CANCELED：Quickie 在这里同样给 QRUserCanceled
        if (resultCode != Activity.RESULT_OK) return QRResult.QRUserCanceled
        return when (intent?.getIntExtra(QRScanActivity.EXTRA_OUTCOME, QRScanActivity.OUTCOME_CANCELED)) {
            QRScanActivity.OUTCOME_SUCCESS ->
                QRResult.QRSuccess(ScannedCode(intent.getStringExtra(QRScanActivity.EXTRA_RAW_VALUE)))

            QRScanActivity.OUTCOME_ERROR ->
                QRResult.QRError(Exception(intent.getStringExtra(QRScanActivity.EXTRA_ERROR) ?: "scan failed"))

            QRScanActivity.OUTCOME_NO_PERMISSION -> QRResult.QRMissingPermission

            else -> QRResult.QRUserCanceled
        }
    }
}
