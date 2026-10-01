package me.rerere.rikkahub.ui.components.charts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chart_display 参数解析（[ChartSpec.fromJson]）的回归测试。
 *
 * 解析保持宽松：非法输入回退 null（图表卡片不渲染，但表格视图与文本仍在），
 * 新增图型时这条性质必须继续成立。
 */
class ChartSpecTest {

    private fun parse(json: String): ChartSpec? = ChartSpec.fromJson(Json.parseToJsonElement(json))

    @Test
    fun parsesLineChartWithCategoryLabelsAndSeries() {
        val spec = parse(
            """
            {"style":"line","title":"访问量",
             "x_axis":{"title":"月份","data":["一月","二月"]},
             "y_axis":{"title":"次","format":".0f"},
             "series":[{"name":"A","values":[1,2]},{"name":"B","values":[3,4]}]}
            """.trimIndent()
        )
        assertNotNull(spec)
        assertEquals(ChartStyle.Line, spec!!.style)
        assertEquals("访问量", spec.title)
        assertEquals("月份", spec.xAxis.title)
        assertEquals(2, spec.categoryCount)
        assertEquals("一月", spec.categoryLabel(0))
        assertEquals(2, spec.series.size)
        assertEquals("A", spec.series[0].name)
        assertEquals("次", spec.yAxis.title)
    }

    @Test
    fun parsesPieDonutAndAreaStyles() {
        val pie = parse("""{"style":"pie","x_axis":{"data":["a","b","c"]},"series":[{"values":[1,2,3]}]}""")
        assertEquals(ChartStyle.Pie, pie!!.style)
        assertEquals(3, pie.categoryCount)

        val donut = parse("""{"style":"donut","x_axis":{"data":["a","b"]},"series":[{"values":[2,8]}]}""")
        assertEquals(ChartStyle.Donut, donut!!.style)

        val area = parse("""{"style":"area","x_axis":{"data":["a","b"]},"series":[{"values":[1,5]}]}""")
        assertEquals(ChartStyle.Area, area!!.style)
    }

    @Test
    fun returnsNullForUnknownOrMissingStyle() {
        assertNull(parse("""{"style":"radar","series":[{"values":[1]}]}"""))
        assertNull(parse("""{"series":[{"values":[1]}]}"""))
        assertNull(ChartSpec.fromJson(JsonPrimitive("not an object")))
    }

    @Test
    fun returnsNullWhenNoUsableSeries() {
        assertNull(parse("""{"style":"line","series":[]}"""))
        assertNull(parse("""{"style":"line"}"""))
        // 折线图给了 points 而没有 values：不符合该图型的数据形状，回退 null
        assertNull(parse("""{"style":"line","series":[{"points":[{"x":1,"y":2}]}]}"""))
        assertNull(parse("""{"style":"pie","series":[{"values":[]}]}"""))
    }

    @Test
    fun parsesScatterPoints() {
        val spec = parse(
            """{"style":"scatter","series":[{"points":[{"x":1,"y":2},{"x":3,"y":4}]}]}"""
        )
        assertNotNull(spec)
        assertEquals(ChartStyle.Scatter, spec!!.style)
        assertEquals(2, spec.series[0].points.size)
        assertEquals(3.0, spec.series[0].points[1].x, 1e-9)
        assertEquals(4.0, spec.series[0].points[1].y, 1e-9)
    }

    @Test
    fun dropsNonNumericValuesAndNonNumericPoints() {
        val spec = parse(
            """{"style":"bar","series":[{"values":[1,"x",null,2.5]}]}"""
        )
        assertNotNull(spec)
        assertEquals(listOf(1.0, 2.5), spec!!.series[0].values)

        val scatter = parse("""{"style":"scatter","series":[{"points":[{"x":1,"y":2},{"x":"a","y":3},{ }]}]}""")
        assertNotNull(scatter)
        assertEquals(1, scatter!!.series[0].points.size)
    }

    @Test
    fun parsesAxisRangeAndLogScale() {
        val spec = parse(
            """{"style":"line","y_axis":{"min":0,"max":100,"scale":"log","format":"%.1f"},
                "x_axis":{"data":["a"]},"series":[{"values":[10]}]}"""
        )
        assertNotNull(spec)
        assertEquals(0.0, spec!!.yAxis.min!!, 1e-9)
        assertEquals(100.0, spec.yAxis.max!!, 1e-9)
        assertEquals(ChartScaleType.Log, spec.yAxis.scale)
        assertEquals("%.1f", spec.yAxis.format)
        assertEquals(ChartScaleType.Linear, spec.xAxis.scale)
    }

    @Test
    fun parsesHexColorsAndIgnoresInvalidOnes() {
        val spec = parse(
            """{"style":"line","series":[
                {"values":[1,2],"color":"#F80"},
                {"values":[3,4],"color":"red"},
                {"values":[5,6],"color":"#FF8800"}]}"""
        )
        assertNotNull(spec)
        assertNotNull(spec!!.series[0].color)
        assertNull(spec.series[1].color)
        assertEquals(spec.series[0].color, spec.series[2].color)
    }

    @Test
    fun ignoresBlankTitleAndTrailingGapsInCategoryCount() {
        val spec = parse(
            """{"style":"line","title":"   ","x_axis":{"data":["a"]},
                "series":[{"values":[1,2,3]}]}"""
        )
        assertNotNull(spec)
        assertNull(spec!!.title)
        // 类目数取 x_axis.data 与最长系列的较大值，标签缺失时退回序号
        assertEquals(3, spec.categoryCount)
        assertEquals("a", spec.categoryLabel(0))
        assertEquals("2", spec.categoryLabel(1))
        assertTrue(spec.series[0].values.size == 3)
    }
}
