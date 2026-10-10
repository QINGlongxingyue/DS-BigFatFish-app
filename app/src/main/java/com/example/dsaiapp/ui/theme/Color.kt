package com.example.dsaiapp.ui.theme

import androidx.compose.ui.graphics.Color

// 大肥鱼配色：深海蓝底 + 亮蓝主体，跟模型本身的蓝发 / 蓝尾呼应
val DeepSea = Color(0xFF0B1220)
val DeepSeaSoft = Color(0xFF121C30)
val DeepSeaCard = Color(0xFF18243C)

val WhaleBlue = Color(0xFF5AA9FF)
val WhaleBlueDark = Color(0xFF2F6FD0)
val WhaleCyan = Color(0xFF7BE0E8)

val TextPrimary = Color(0xFFE8EEF9)
val TextSecondary = Color(0xFF93A4C0)

val BubbleUser = Color(0xFF2F6FD0)
val BubbleAi = Color(0xFF1B2740)

// Live2D 舞台的底色：必须和 Live2DDelegate 里的 STAGE_CLEAR_* 一致
// （乘算混合的部件需要浅色目标，清黑会变黑色剪影）
val StageLight = Color(0xFFECF5FC)

// 保留默认模板里的名字，避免别处引用报错
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
