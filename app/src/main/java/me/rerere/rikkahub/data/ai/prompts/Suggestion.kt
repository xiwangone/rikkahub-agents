package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_SUGGESTION_PROMPT = """
    我会在 `<content>` 块中给你一段对话内容，包含用户与助手之间的交流。
    你需要扮演**用户**，针对助手的回复生成 3~5 条合适且贴合上下文的回复建议，帮助助手改进回答。

    规则：
    1. 直接给出建议，不要任何格式、不要 markdown 列表，用换行分隔即可
    2. 使用 {locale} 语言
    3. 每条建议都必须成立（与上下文相符）
    4. 每条建议不超过 10 个字符
    5. 模仿用户此前的说话风格
    6. 扮演**用户**，不是助手！

    <content>
    {content}
    </content>
""".trimIndent()
