package me.rerere.rikkahub.data.vault

/**
 * 凭证类型标识与自动推断。
 *
 * 取值与通用凭据交换格式（CXF）的类型名对齐（`ssh-key` / `api-key` / `basic-auth` /
 * `totp` / `custom-fields`）。这样做有两个好处：
 * 1. 工具与界面用同一套词汇描述"这是什么凭据"，使用方据此选对方式
 *    （SSH 握手 / 请求头注入 / 表单填充），而不是靠名字猜；
 * 2. 将来做跨工具导入导出时不需要再做一层类型映射。
 *
 * 空串表示"未分类"（历史数据），写入时由 [infer] 尽量补全。
 */
internal object CredentialType {

    /** 未分类（历史数据 / 无法判断）。 */
    const val UNCLASSIFIED = ""

    /** SSH 密钥对（公钥在库、私钥用于握手）。 */
    const val SSH_KEY = "ssh-key"

    /** 接口令牌 / API Key / 通用密钥。 */
    const val API_KEY = "api-key"

    /** 用户名口令类。 */
    const val BASIC_AUTH = "basic-auth"

    /** 一次性口令种子。 */
    const val TOTP = "totp"

    /** 自定义字段（兜底）。 */
    const val CUSTOM = "custom-fields"

    /** 已知类型集合（供校验与展示）。 */
    val KNOWN: Set<String> = setOf(SSH_KEY, API_KEY, BASIC_AUTH, TOTP, CUSTOM)

    /**
     * 推断类型：只看名称与值的**结构性**特征，不依赖任何外部元数据。
     *
     * 顺序有意为之——先判私钥/公钥结构（最确定的信号），再看名称后缀。
     */
    fun infer(name: String, value: String, publicKey: String = ""): String {
        val n = name.uppercase()
        if (publicKey.isNotBlank() || value.contains("PRIVATE KEY-----")) return SSH_KEY
        if (n.endsWith("_TOTP") || n.endsWith("_OTP") || n.contains("_2FA")) return TOTP
        if (n.endsWith("_PWD") || n.endsWith("_PASS") || n.contains("PASSWORD")) return BASIC_AUTH
        if (n.endsWith("_TOKEN") || n.endsWith("_API_KEY") || n.endsWith("_KEY") ||
            n.contains("APIKEY") || n.contains("SECRET")
        ) {
            return API_KEY
        }
        return CUSTOM
    }

    /** 是否是可以落库的类型值（空串表示未分类，允许）。 */
    fun isValid(type: String): Boolean = type.isEmpty() || type in KNOWN
}
