package me.rerere.rikkahub.data.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.service.RikkaAccessibilityService

/**
 * Agent 回合结束后的自动回切：当 agent 通过屏幕自动化驱动了目标应用，
 * 回合结束时把用户带回 RikkaHub Agents。
 *
 * 用户主动切走了应用则跳过回切（安全特性，避免抢前台）。
 */
private const val TAG = "GenerationLoop"

internal class AutoReturnHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")   // 有意 catch Exception：startActivity 抛 ActivityNotFoundException / SecurityException，均属 Exception；catch Throwable 会连 OOM 一起吞
    fun handleAutoReturnAfterTurn() {
        if (!AgentTurnTracker.didNavigateAway()) return
        // Only auto-return when the agent actually drove the destination app via screen
        // automation (tap, click_node, set_text, swipe, scroll, global_action). A pure
        // "open Chrome and stay there" request is just launch_app + a text reply — yanking
        // the user back to RikkaHub Agents in that case defeats the purpose of the request.
        if (!AgentTurnTracker.didAutomate()) return
        val destination = AgentTurnTracker.lastDestination()
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = destination != null
            && currentForeground != null
            && currentForeground != destination
            && currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "RikkaHub Agents: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // startActivity 的文档化异常面即 ActivityNotFoundException / SecurityException，
            // 分别收窄捕获；其余 JVM Error（OOM 等）不在此列，照常向上传播。
            AppLog.w(TAG, "auto-return launch failed", e)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "auto-return launch failed", e)
        }
    }
}
