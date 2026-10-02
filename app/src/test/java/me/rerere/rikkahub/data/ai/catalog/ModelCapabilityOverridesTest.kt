package me.rerere.rikkahub.data.ai.catalog

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型能力外置表的**解析**回归测试（不含 IO）。
 *
 * 关键性质：整表非法 → 返回 null（调用方整表忽略，绝不静默生效）；
 * 单条非法 → 跳过该条、保留其余；字段名大小写不敏感。
 */
class ModelCapabilityOverridesTest {

    private fun parse(text: String) = ModelCapabilityOverrides.parseEntries(text)

    @Test
    fun parsesEntriesWithAllFields() {
        val entries = parse(
            """
            {"version":1,"models":[
              {"match":"mimo-v2.6","input":["text","image"],"output":["text"],
               "abilities":["tool","reasoning"],"contextLength":1000000}
            ]}
            """.trimIndent()
        )
        assertEquals(1, entries?.size)
        val e = entries!!.first()
        assertEquals("mimo-v2.6", e.match)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), e.input)
        assertEquals(setOf(Modality.TEXT), e.output)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), e.abilities)
        assertEquals(1_000_000, e.contextLength)
    }

    @Test
    fun fieldNamesAreCaseInsensitive() {
        val entries = parse("""{"models":[{"match":"x","input":["TEXT","Image"],"abilities":["TOOL"]}]}""")
        val e = entries!!.first()
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), e.input)
        assertEquals(setOf(ModelAbility.TOOL), e.abilities)
    }

    @Test
    fun returnsNullForBrokenTable() {
        assertNull(parse("not json at all"))
        assertNull(parse("""{"noModels":[]}"""))
        assertNull(parse("""[1,2,3]"""))
    }

    @Test
    fun skipsInvalidEntryButKeepsTheRest() {
        val entries = parse(
            """
            {"models":[
              {"input":["text"]},
              {"match":"good-one","input":["image"]},
              {"match":"   "}
            ]}
            """.trimIndent()
        )
        assertEquals(1, entries?.size)
        assertEquals("good-one", entries!!.first().match)
    }

    @Test
    fun omittedFieldsStayNullSoTheyDoNotOverride() {
        val e = parse("""{"models":[{"match":"only-abilities","abilities":["reasoning"]}]}""")!!.first()
        assertNull(e.input)
        assertNull(e.output)
        assertNull(e.contextLength)
        assertEquals(setOf(ModelAbility.REASONING), e.abilities)
    }

    @Test
    fun unknownEnumValuesAreDroppedInsteadOfFailing() {
        val e = parse("""{"models":[{"match":"m","input":["text","hologram"]}]}""")!!.first()
        assertEquals(setOf(Modality.TEXT), e.input)
    }
}
