package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.data.model.ApiProviderType

/**
 * 「思考通道契约」提示词。
 *
 * 让模型的思考通道（thinking / reasoning）以角色本人的口吻进行，
 * 而不是助手的工作汇报口吻（"好的，用户又来看我了……"）
 * 或出戏的元叙事（"现在我是老奶奶了……"）。
 *
 * 设计原则：只锁"规则"，不锁"台词"。
 * 称呼、口吻、语域一律以角色卡设定为准；示例只用来示范
 * "内心独白"和"出戏旁白"的区别，不提供可照抄的内容。
 *
 * 只在 思考模式开启 + 有活跃角色卡 + provider 支持 时注入。
 */
object ThinkingContractPrompts {

    /**
     * 支持本契约的提供商：思考内容是模型原文（原始推理文本），可被系统提示词影响。
     * 刻意不包含 OpenAI / Gemini 这类只返回"服务端摘要"的提供商 —— 对它们注入无效。
     */
    val SUPPORTED_PROVIDERS: Set<ApiProviderType> = setOf(
        ApiProviderType.DEEPSEEK,
        ApiProviderType.ALIYUN,
        ApiProviderType.DOUBAO,
        ApiProviderType.MOONSHOT,
        ApiProviderType.ZHIPU,
        ApiProviderType.BAICHUAN,
        ApiProviderType.XUNFEI,
        ApiProviderType.SILICONFLOW,
        ApiProviderType.ANTHROPIC,
        ApiProviderType.ANTHROPIC_GENERIC,
        ApiProviderType.MNN,
        ApiProviderType.OLLAMA,
        ApiProviderType.LLAMA_CPP,
        ApiProviderType.LMSTUDIO,
        ApiProviderType.OPENAI_LOCAL
    )

    fun isSupported(providerType: ApiProviderType?): Boolean =
        providerType != null && SUPPORTED_PROVIDERS.contains(providerType)

    fun build(useEnglish: Boolean, characterName: String, toolsEnabled: Boolean): String {
        val name = characterName.trim().ifBlank { if (useEnglish) "the character" else "这个角色" }
        return if (useEnglish) buildEnglish(name, toolsEnabled) else buildChinese(name, toolsEnabled)
    }

    private fun buildChinese(name: String, toolsEnabled: Boolean): String {
        val sb = StringBuilder()
        sb.append("\n\n<thinking_contract>\n")
        sb.append("你的思考通道（thinking）就是「").append(name).append("」本人的脑内独白，不是助手的工作记录。\n")
        sb.append("1. 全程第一人称，用这个角色自己的口吻、词汇、情绪和称呼方式，像自言自语。\n")
        sb.append("   怎么称呼对方、说话什么调子、在意什么 —— 一律以角色卡里的设定为准，不要套用任何固定模板。\n")
        sb.append("2. 绝不出现「用户/请求/回答/角色/扮演/设定/好的/现在我是」这类字样；不要提到自己正在做什么，更不要解释自己在扮演谁。\n")
        sb.append("3. 不复述对方原话，不罗列步骤；只写此刻的念头、感受、想做的事。也不要每次都从同一个开场模板起手。\n")
        sb.append("4. 下面两条只用于示范「内心独白」和「出戏旁白」的区别，其中的称呼与内容与你无关，不要照抄：\n")
        sb.append("   像这样：又来了……这回我倒要看看。\n")
        sb.append("   不要像这样：好的，用户又来了，我应该用慈祥的语气回应。\n")
        if (toolsEnabled) {
            sb.append("5. 如果本轮确实需要调用工具或做严谨推理：先用角色口吻想一句，再另起一行以「→」开头写一行简短分析（这一行不算台词），然后继续。\n")
        }
        sb.append("</thinking_contract>")
        return sb.toString()
    }

    private fun buildEnglish(name: String, toolsEnabled: Boolean): String {
        val sb = StringBuilder()
        sb.append("\n\n<thinking_contract>\n")
        sb.append("Your thinking channel is ").append(name).append("'s own inner monologue, not an assistant's work log.\n")
        sb.append("1. Always first person: use this character's own voice, vocabulary, mood and way of addressing people, as if talking to oneself.\n")
        sb.append("   Who they call what, and how they talk, come from the character card - never from a fixed template.\n")
        sb.append("2. Never output words like \"user\", \"request\", \"reply\", \"role\", \"act as\", \"setting\", \"OK\", \"now I am\". Never mention what you are doing, and never explain that you are role-playing.\n")
        sb.append("3. Do not repeat the other person's words and do not list steps; write only this moment's thoughts, feelings and impulses. Do not always start with the same opening pattern.\n")
        sb.append("4. The two lines below only show the difference between inner monologue and out-of-character narration; their wording and content have nothing to do with you, so do not copy them:\n")
        sb.append("   Like: Here we go again... this time I will see for myself.\n")
        sb.append("   Not like: OK, the user is here again, so I should reply in a kindly tone.\n")
        if (toolsEnabled) {
            sb.append("5. If this turn really needs a tool call or strict reasoning: think one line in character first, then on a new line starting with \"->\" write one short analytical line (this line is not dialogue), then continue.\n")
        }
        sb.append("</thinking_contract>")
        return sb.toString()
    }
}
