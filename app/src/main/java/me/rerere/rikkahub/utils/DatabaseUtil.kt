package me.rerere.rikkahub.utils

import android.database.CursorWindow
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            AppLog.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: ReflectiveOperationException) {
            // 反射失败面：NoSuchFieldException / IllegalAccessException 等，均为
            // ReflectiveOperationException 子类；Android 无 SecurityManager，不存在 SecurityException 路径
            e.printStackTrace()
        }
        // 已fork io.requery.android.database 修改了window size，避免无法反射修改final字段
//        try {
//            val field =
//                io.requery.android.database.CursorWindow::class.java.getDeclaredField("sDefaultCursorWindowSize")
//            field.isAccessible = true
//            val oldValue = field.get(null) as Int
//            field.set(null, size)
//            AppLog.i(TAG, "setCursorWindowSize: set $oldValue to $size")
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}
