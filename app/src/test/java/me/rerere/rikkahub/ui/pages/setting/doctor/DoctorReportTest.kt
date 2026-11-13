package me.rerere.rikkahub.ui.pages.setting.doctor

import android.app.Application
import android.content.Context
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [DoctorReport.format] 的格式化覆盖 —— Doctor 页「复制报告」与 Telegram /doctor 共用的纯文本报告。
 *
 * 报告标题、分类名、行标题现在都走字符串资源（需要 Context），所以用 Robolectric 提供真实
 * 资源；断言一律从同一 Context 取期望文本，不写死某一语言的措辞。`Generated` 行依赖墙钟，
 * 不断言；其余（标题、严重度汇总、分类分组、严重度标记、空分类跳过、顺序、无尾随空白）都断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DoctorReportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // 行标题必须是字符串资源；这里借用分类名资源，只要求能被解析成稳定文本
    private fun check(
        id: String,
        category: DoctorCategory,
        severity: Severity,
        labelRes: Int = R.string.doctor_category_diagnostics,
        detail: String = "detail-$id",
    ) = DoctorCheck(id = id, category = category, labelRes = labelRes, detail = detail, severity = severity)

    private fun categoryName(category: DoctorCategory) = context.getString(category.displayNameRes)

    @Test fun `header line is the supplied header`() {
        val out = DoctorReport.format(context, emptyList(), header = "custom header")
        assertTrue(out.startsWith("custom header\n"))
    }

    @Test fun `default header comes from the string resource`() {
        val out = DoctorReport.format(context, emptyList())
        assertTrue(out.startsWith(context.getString(R.string.doctor_report_header) + "\n"))
    }

    @Test fun `summary counts every severity`() {
        val out = DoctorReport.format(
            context,
            listOf(
                check("a", DoctorCategory.Permissions, Severity.FAIL),
                check("b", DoctorCategory.Permissions, Severity.WARN),
                check("c", DoctorCategory.Permissions, Severity.WARN),
                check("d", DoctorCategory.Services, Severity.OK),
                check("e", DoctorCategory.Services, Severity.INFO),
            ),
        )
        assertTrue(out.contains(context.getString(R.string.doctor_report_summary, "fail=1  warn=2  ok=1  info=1")))
    }

    @Test fun `summary shows zero for absent severities`() {
        val out = DoctorReport.format(context, listOf(check("a", DoctorCategory.Database, Severity.OK)))
        assertTrue(out.contains(context.getString(R.string.doctor_report_summary, "fail=0  warn=0  ok=1  info=0")))
    }

    @Test fun `rows are grouped under their category display name`() {
        val out = DoctorReport.format(
            context,
            listOf(
                check("a", DoctorCategory.Permissions, Severity.OK),
                check("b", DoctorCategory.Database, Severity.FAIL),
            ),
        )
        assertTrue(out.contains("## ${categoryName(DoctorCategory.Permissions)}"))
        assertTrue(out.contains("## ${categoryName(DoctorCategory.Database)}"))
    }

    @Test fun `empty categories are skipped`() {
        val out = DoctorReport.format(context, listOf(check("a", DoctorCategory.Network, Severity.OK)))
        assertTrue(out.contains("## ${categoryName(DoctorCategory.Network)}"))
        assertTrue(!out.contains("## ${categoryName(DoctorCategory.Termux)}"))
    }

    @Test fun `each severity gets its mark`() {
        val label = context.getString(R.string.doctor_category_diagnostics)
        val out = DoctorReport.format(
            context,
            listOf(
                check("a", DoctorCategory.Diagnostics, Severity.OK),
                check("b", DoctorCategory.Diagnostics, Severity.INFO),
                check("c", DoctorCategory.Diagnostics, Severity.WARN),
                check("d", DoctorCategory.Diagnostics, Severity.FAIL),
            ),
        )
        assertTrue(out.contains("[ok]    $label — detail-a"))
        assertTrue(out.contains("[info]  $label — detail-b"))
        assertTrue(out.contains("[warn]  $label — detail-c"))
        assertTrue(out.contains("[fail]  $label — detail-d"))
    }

    @Test fun `categories appear in enum declaration order`() {
        val out = DoctorReport.format(
            context,
            listOf(
                check("a", DoctorCategory.Maintenance, Severity.OK),
                check("b", DoctorCategory.Permissions, Severity.OK),
            ),
        )
        val permIdx = out.indexOf("## ${categoryName(DoctorCategory.Permissions)}")
        val maintIdx = out.indexOf("## ${categoryName(DoctorCategory.Maintenance)}")
        assertTrue(permIdx in 0 until maintIdx)
    }

    @Test fun `output has no trailing whitespace`() {
        val out = DoctorReport.format(context, listOf(check("a", DoctorCategory.Database, Severity.OK)))
        assertEquals(out, out.trimEnd())
    }
}
