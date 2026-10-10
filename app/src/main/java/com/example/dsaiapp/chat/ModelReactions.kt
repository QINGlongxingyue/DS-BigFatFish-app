package com.example.dsaiapp.chat

import com.example.dsaiapp.ui.live2d.Live2DController

/**
 * 根据模型回复里的关键字，让 Live2D 模型做表情 / 动作。
 *
 * <p>两只皮的表情名不同，按当前模型映射；没有对应表情的那只就跳过。
 * 每次最多触发一个动作，避免表情打架。
 */
object ModelReactions {

    fun react(reply: String, modelName: String) {
        val isDafeiyu = modelName == "大肥鱼"

        // 1) 「思考」→ DeepSeek 专属的 thinking 动作（Anima 组第 2 个）
        if (listOf("思考", "想想", "让我想", "考虑一下", "想一想").any(reply::contains)) {
            if (!isDafeiyu) Live2DController.playMotion("Anima", 2)
            return
        }

        // 2) 关键字 → 表情：Triple(关键字们, DeepSeek 表情名, 大肥鱼表情名)
        val table = listOf(
            Triple(listOf("开心", "高兴", "哈哈", "嘻嘻", "喜欢", "太好了"), "happy2", "grin"),
            Triple(listOf("难过", "伤心", "呜呜", "可怜"), "unhappy", "worried_mouth"),
            Triple(listOf("生气", "讨厌", "哼", "烦人"), "unhappy", "dark_face"),
            Triple(listOf("疑惑", "不懂", "什么意思", "为什么"), "puzzled", "squint"),
            Triple(listOf("害羞", "脸红", "不好意思"), "happy2", "blush"),
            Triple(listOf("惊讶", "震惊", "天哪", "不会吧"), "puzzled", "oo"),
            Triple(listOf("好吃", "馋", "流口水"), "happy", "drool"),
            Triple(listOf("放松", "休息", "摸鱼", "晚安"), "relax", "o_mouth"),
            Triple(listOf("嘿嘿", "坏笑", "龇牙"), "happy", "x_eyes"),
        )
        for ((keywords, deepseekExp, dafeiyuExp) in table) {
            if (keywords.any(reply::contains)) {
                Live2DController.setExpression(if (isDafeiyu) dafeiyuExp else deepseekExp)
                return
            }
        }
    }
}
