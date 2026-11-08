package com.hvh.client

/** Описание модуля. Имена и ключи настроек должны совпадать с native/modules в C++. */
data class ModuleDef(
    val id: String,
    val title: String,
    val description: String,
    val settings: List<Setting> = emptyList(),
)

data class Setting(val key: String, val title: String, val default: Float, val min: Float, val max: Float)

object Modules {
    val all = listOf(
        ModuleDef("killaura", "KillAura", "Авто-атака ближайших игроков",
            listOf(Setting("range", "Дальность", 4f, 2f, 7f), Setting("cps", "CPS", 12f, 1f, 20f))),
        ModuleDef("reach", "Reach", "Увеличенная дистанция удара",
            listOf(Setting("distance", "Дистанция", 4f, 3f, 7f))),
        ModuleDef("velocity", "Velocity", "Уменьшение отдачи",
            listOf(Setting("horizontal", "Горизонталь %", 100f, 0f, 100f), Setting("vertical", "Вертикаль %", 100f, 0f, 100f))),
        ModuleDef("criticals", "Criticals", "Критические удары"),
        ModuleDef("sprint", "AutoSprint", "Постоянный спринт"),
        ModuleDef("autototem", "AutoTotem", "Тотем в левую руку при низком HP",
            listOf(Setting("health", "HP порог", 6f, 1f, 19f))),
    )
}
