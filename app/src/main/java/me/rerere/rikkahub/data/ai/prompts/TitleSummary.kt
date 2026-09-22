package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_TITLE_PROMPT = """
    我会在 `<content>` 块中给你一段对话内容。
    你需要把用户与助手之间的对话总结成一个简短标题。

    1. 只输出标题本身：不要引号、不要前缀（如「标题：」）、不要解释
    2. 不要使用标点或其他特殊符号
    3. 标题不超过 10 个字符
    4. 概括对话的核心主题，而不是罗列细节
    5. 使用 {locale} 语言

    <content>
    {content}
    </content>
""".trimIndent()
