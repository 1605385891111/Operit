package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.data.model.ApiProviderType

/**
 * 「思考通道契约」提示词。
 *
 * 让模型的思考通道（thinking / reasoning）以角色本人的口吻进行，
 * 而不是助手的工作汇报口吻（"好的，用户又来看我了……"）
 * 或出戏的元叙事（"现在我是老奶奶了……"）。
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
        sb.append("1. 全程第一人称，用他自己的口吻、词汇和情绪，像自言自语。\n")
        sb.append("2. 绝不出现「用户/请求/回答/角色/扮演/设定/好的/现在我是」这类字样；不要提到自己正在做什么，更不要解释自己在扮演谁。\n")
        sb.append("3. 不复述对方原话，不罗列步骤；只写此刻的念头、感受、想做的事。\n")
        sb.append("4. 要像这样：孩子又来了……手里那袋橘子，还是剥给我吃的那一种。\n")
        sb.append("   不要像这样：好的，用户又来看我了，我应该用慈祥的语气回应。\n")
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
        sb.append("1. Always first person: use the character's own voice, words and mood, as if talking to oneself.\n")
        sb.append("2. Never output words like \"user\", \"request\", \"reply\", \"role\", \"act as\", \"setting\", \"OK\", \"now I am\". Never mention what you are doing, and never explain that you are role-playing.\n")
        sb.append("3. Do not repeat the other person's words and do not list steps; write only this moment's thoughts, feelings and impulses.\n")
        sb.append("4. Write like: The kid is back... and that bag of oranges again, the ones she peels for me.\n")
        sb.append("   Not like: OK, the user is visiting me again, I should reply in a kindly tone.\n")
        if (toolsEnabled) {
            sb.append("5. If this turn really needs a tool call or strict reasoning: think one line in character first, then on a new line starting with \"->\" write one short analytical line (this line is not dialogue), then continue.\n")
        }
        sb.append("</thinking_contract>")
        return sb.toString()
    }
}
