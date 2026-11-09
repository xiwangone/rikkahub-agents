package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.RouteActivity

class GeminiOAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只接受 manifest 里声明的跳转入口 VIEW rikkahub://gemini/oauth，
        // 其他 intent 直接结束，不打开设置页。
        val data = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || data?.scheme != "rikkahub" || data?.host != "gemini" || data?.path != "/oauth") {
            finish()
            return
        }
        startActivity(
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(RouteActivity.EXTRA_OPEN_GEMINI_SETTINGS, true)
            }
        )
        finish()
    }
}
