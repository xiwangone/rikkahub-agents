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

    @Test
    fun serializeRoundTripsEntries() {
        val original = listOf(
            ModelCapabilityOverrides.Entry(
                match = "sensenova-6.8-flash",
                input = setOf(Modality.TEXT, Modality.IMAGE),
                output = setOf(Modality.TEXT),
                abilities = setOf(ModelAbility.TOOL, ModelAbility.REASONING),
                contextLength = 262144,
            ),
            ModelCapabilityOverrides.Entry(match = "only-abilities", abilities = setOf(ModelAbility.REASONING)),
        )
        val text = ModelCapabilityOverrides.serializeEntries(original)
        assertEquals(original, parse(text))
    }

    @Test
    fun upsertReplacesSameMatchIgnoringCaseAndTrims() {
        val base = listOf(
            ModelCapabilityOverrides.Entry(match = "gpt-4o", input = setOf(Modality.TEXT)),
            ModelCapabilityOverrides.Entry(match = "kimi-k3", input = setOf(Modality.TEXT)),
        )
        val updated = ModelCapabilityOverrides.upsertInList(
            base,
            ModelCapabilityOverrides.Entry(
                match = "  GPT-4o ",
                input = setOf(Modality.TEXT, Modality.IMAGE),
            ),
        )
        assertEquals(2, updated.size)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), updated.first().input)
        assertEquals("GPT-4o", updated.first().match)
        val appended = ModelCapabilityOverrides.upsertInList(
            updated,
            ModelCapabilityOverrides.Entry(match = "new-model", abilities = setOf(ModelAbility.TOOL)),
        )
        assertEquals(3, appended.size)
        assertEquals("new-model", appended.last().match)
    }

    @Test
    fun removeDropsMatchingEntryIgnoringCase() {
        val base = listOf(
            ModelCapabilityOverrides.Entry(match = "gpt-4o", input = setOf(Modality.TEXT)),
            ModelCapabilityOverrides.Entry(match = "kimi-k3", input = setOf(Modality.TEXT)),
        )
        val removed = ModelCapabilityOverrides.removeFromList(base, " GPT-4O ")
        assertEquals(listOf(base[1]), removed)
        assertEquals(base, ModelCapabilityOverrides.removeFromList(base, "not-there"))
    }

    @Test
    fun longestMatchWinsOverEarlierShortPrefix() {
        // 短串排在前、长串排在后：命中最长，而不是数组第一条。
        val short = ModelCapabilityOverrides.Entry(match = "mimo", abilities = setOf(ModelAbility.TOOL))
        val long = ModelCapabilityOverrides.Entry(
            match = "mimo-v2.5-pro",
            abilities = setOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
        val picked = ModelCapabilityOverrides.longestMatch(listOf(short, long), "mimo-v2.5-pro")
        assertEquals(long, picked)
    }

    @Test
    fun longestMatchBreaksTieByDeclarationOrder() {
        // 等长（同为子串命中）时保留先声明者，语义与本机表在前的拼接顺序一致。
        val first = ModelCapabilityOverrides.Entry(match = "aa-bb", abilities = setOf(ModelAbility.TOOL))
        val second = ModelCapabilityOverrides.Entry(match = "bb-cc", abilities = setOf(ModelAbility.REASONING))
        val picked = ModelCapabilityOverrides.longestMatch(listOf(first, second), "aa-bb-cc")
        assertEquals(first, picked)
    }

    @Test
    fun longestMatchIgnoresNonMatchingEntries() {
        val entries = listOf(ModelCapabilityOverrides.Entry(match = "kimi-k3", contextLength = 1))
        assertNull(ModelCapabilityOverrides.longestMatch(entries, "gpt-4o"))
    }

    @Test
    fun modelSlotCountSeesEntriesEvenWhenAllInvalid() {
        // 导入校验依赖它区分「显式空表」与「有槽位但全非法」。
        assertEquals(0, ModelCapabilityOverrides.modelSlotCount("""{"models":[]}""") ?: -1)
        assertEquals(2, ModelCapabilityOverrides.modelSlotCount("""{"models":[{},{"input":["text"]}]}""") ?: -1)
        assertNull(ModelCapabilityOverrides.modelSlotCount("not json"))
        assertNull(ModelCapabilityOverrides.modelSlotCount("""{"noModels":[]}"""))
    }
}
