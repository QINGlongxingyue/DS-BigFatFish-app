# DSAI —— 大肥鱼 × DeepSeek Live2D 聊天 App

一个 Android 端的 Live2D AI 聊天应用：桌面上养一只 Live2D 角色陪你聊天，接入 DeepSeek 官方 API，模型会根据回复内容做表情和动作。

内置两只模型，右上角按钮一键切换：

| 模型 | 说明 |
| --- | --- |
| 大肥鱼（默认） | DeepSeek 吉祥物风格的蓝鲸女孩，VTube Studio 导出的皮，15 个表情 |
| DeepSeek | DeepSeek 官方鲸鱼模型，自带 Idle / 说话 / 思考动作组和 5 个表情 |

## 功能

- **Live2D 渲染**：Cubism SDK for Java 5-r.5（框架源码内嵌，无需 NDK）
- **对话**：DeepSeek 官方 API（POST `api.deepseek.com/chat/completions`，模型 `deepseek-flash`），带最近 12 条上下文
- **API Key 管理**：左上角设置按钮填 Key，只存本机 SharedPreferences，不上传
- **关键字驱动表情/动作**：模型回复里带关键词时自动触发，两套表情各自映射：
  - 「开心 / 哈哈」→ 笑；「生气」→ 脸黑；「害羞」→ 脸红
  - 「思考 / 想想」→ DeepSeek 播 thinking 动作
  - 「好吃 / 馋」→ 流口水……完整映射见 `app/src/main/java/com/example/dsaiapp/chat/ModelReactions.kt`
- **待机动画**：眨眼 / 呼吸 / 视线跟随手指（手写参数驱动，不依赖动作文件）
- **水印隐藏**：大肥鱼的水印参数每帧压制
- 深海主题 UI：Compose + Material3，气泡聊天、正在输入指示

## 构建

### Android Studio（推荐）

直接 Open 本目录，等 Gradle Sync 完，Run 即可。

### 命令行

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # 需要 JDK 17+，用 AS 自带 JBR 最稳
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

踩过的坑：

- 不要用系统 PATH 里的 JDK 8 编译，AGP 会直接失败
- 工程刻意不用 `jvmToolchain()`——它会去 github.com 下载 JDK，网络不通时构建必挂；改用 `compileOptions` 加 `jvmTarget` 对齐字节码，不联网
- 最低 API 24，target 37；原生库只有 arm64-v8a / x86 / x86_64（Core AAR 自带）

## 使用

1. 装上 App，等模型加载（首帧 3 到 5 秒）
2. 点左上角设置按钮，填入在 platform.deepseek.com 申请的 API Key
3. 底部输入框开聊；没填 Key 会提示
4. 右上角胶囊按钮切换 大肥鱼 / DeepSeek

## 目录结构

```text
app/src/main/java/com/example/dsaiapp/
├── MainActivity.kt          # 全部 UI（Compose）：顶栏 / 舞台 / 聊天 / 设置对话框
├── chat/
│   ├── DeepSeekClient.kt    # DeepSeek API 最小客户端（HttpURLConnection + org.json，零依赖）
│   └── ModelReactions.kt    # 回复关键字 → 表情/动作 映射表
├── live2d/
│   ├── Live2DDelegate.java  # 全局单例：GL 生命周期 / 投影矩阵 / 模型注册表与切换
│   ├── Live2DModel.java     # 通用模型：加载 / 手写待机动画 / 表情 / 动作 / 水印压制
│   ├── Live2DGLView.java    # GLSurfaceView + 触摸视线
│   ├── Live2DTextureManager.java  # 纹理上传（含 glGenerateMipmap，缺它模型全黑！）
│   └── CubismPal.java       # 平台胶水：assets 读文件 / 日志
└── ui/                      # Compose 包装 + 主题配色
app/src/main/java/com/live2d/…      # Cubism SDK for Java 5-r.5 框架源码
app/src/main/assets/live2d/         # 两只模型的 model3.json / moc3 / 贴图 / 表情 / 动作
app/libs/Live2DCubismCore.aar       # Cubism Core（含各 ABI 的 so）
THIRD-PARTY-LICENSES.md             # 两个模型的署名与开源协议（必读）
```

## 排查备忘

| 症状 | 原因 |
| --- | --- |
| 模型整体黑色剪影 | 纹理没生成 mipmap，而框架绘制时用 mipmap 过滤采样，不完整纹理采出纯黑。上传后必须调 glGenerateMipmap |
| 模型消失 / 只剩背景 | 渲染器创建前没设置离屏目标尺寸；或 GL 上下文重建后没有重建渲染器和纹理绑定 |
| 模拟器里 DNS/网络报错 | 校园网等环境下模拟器 NAT 容易失效，真机加正常网络即可，跟 API Key 无关 |
| gradle 锁文件拒绝访问 | 命令行构建要写用户目录下的 .gradle，沙箱或权限需放行该目录 |

## 资源与致谢

两个 Live2D 模型均为**开源模型，版权归原作者所有，本仓库按作者要求保留署名**。完整署名与协议全文见仓库根目录的 **THIRD-PARTY-LICENSES.md**：

- **模型1「大肥鱼」**：作者 **B站 @Mao绒绒家园**
- **模型2「DeepSeek」**：来源 github.com/lrplrplrp/dsh-live2d，协议 Apache License 2.0
- **Cubism SDK for Java 5-r.5**：© Live2D Inc.，Live2D Open Software License；公开分发前请自行确认 Live2D 的 SDK 发行许可（个人/小规模通常免费，商用前问一次 Live2D 官方）
- API 服务：DeepSeek Open Platform（platform.deepseek.com）

## TODO

- [ ] 口型同步（TTS 音量包络驱动嘴型，接口已留好 Live2DController.setMouthOpen）
- [ ] 流式输出（SSE）
- [ ] 对话历史持久化
- [ ] 表情随 API 返回的结构化情绪标签联动（现在是文本关键字匹配）
