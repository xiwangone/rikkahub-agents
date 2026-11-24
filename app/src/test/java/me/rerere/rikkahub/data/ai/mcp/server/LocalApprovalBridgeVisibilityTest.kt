package me.rerere.rikkahub.data.ai.mcp.server

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 审批通知的锁屏可见性回归测试。
 *
 * [LocalApprovalBridge] 的通知内容是工具入参摘要——VISIBILITY_PUBLIC 时锁屏上
 * 明文可见工具参数。断言发出的通知是 VISIBILITY_PRIVATE，随系统“锁屏隐藏敏感
 * 内容”遮蔽。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalApprovalBridgeVisibilityTest {
    @Test
    fun `approval notification is private on the lock screen`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        // SDK 34 上 postApprovalNotification 先查 POST_NOTIFICATIONS 权限，未授
        // 权时直接返回不发通知：测试里先授给应用，否则断言不到任何通知。
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val bridge = LocalApprovalBridge(context)
        val tool = Tool(
            name = "visibility_probe_tool",
            description = "probe",
            needsApproval = { true },
            execute = { emptyList() },
        )
        // requireApproval 在发通知后挂起等决策：先让它跑到发通知处，断言后再取消。
        val job = launch {
            bridge.requireApproval(
                tool,
                buildJsonObject { put("secret_arg", "do-not-show-on-lock-screen") },
                "req-visibility-1",
            )
        }
        runCurrent()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.single().visibility)

        job.cancelAndJoin()
    }
}
