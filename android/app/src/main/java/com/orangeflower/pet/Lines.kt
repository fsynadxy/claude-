package com.orangeflower.pet

import java.util.Calendar
import kotlin.random.Random

/** What she says. Short lines: the bubble fits about two lines of eleven characters. */
object Lines {

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..9 -> "早安。……还有点困。"
        in 10..11 -> "上午好，今天也一起努力吧。"
        in 12..13 -> "午安，记得吃饭哦。"
        in 14..17 -> "下午好，要来杯茶吗？"
        in 18..22 -> "晚上好，今天辛苦了。"
        else -> "这么晚了……还不睡吗？"
    }

    val poke = listOf("嗯？", "干什么呀。", "……在看书呢。", "别戳啦。", "有事吗？")
    val angry = listOf("再戳就生气了！", "哼。", "书可是很硬的哦。")
    val sad = listOf("……欺负人。", "呜……")
    val pat = listOf("……嗯。", "再一下下也可以。", "头发要乱了啦。")
    val shy = listOf("够、够了吧……", "好啦好啦……")
    val happy = listOf("嘿嘿。", "今天心情不错。", "找到一本好书了。")
    val drag = listOf("放我下来……", "要去哪里？", "有点高……")
    val dizzy = listOf("世界在转……", "头好晕……")
    val wake = listOf("唔……再睡五分钟。", "我没睡着哦。", "……刚才在想事情。")
    val chatter = listOf(
        "今天也要好好喝水哦。",
        "这本书还没看完。",
        "要休息一下眼睛吗？",
        "我在这里陪你。",
        "外面的天气怎么样？",
        "累了就伸个懒腰吧。",
        "……嗯，这一页很好看。",
    )
    val chatOpen = listOf("嗯，想聊什么？", "我在听。", "说吧。")
    val rest = listOf("坐太久啦，起来走走吧。", "眼睛也要休息一下哦。", "该喝口水了。")

    fun pick(list: List<String>): String = list[Random.nextInt(list.size)]
}
