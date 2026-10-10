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
  - 表情**随机 4~7 秒后自动淡出**回默认状态（Cubism 的表达式不会自己消失，这里用一个空白表情
    触发框架自带的淡出：旧表情权重 1 → 0 平滑过渡。时长见 `Live2DModel` 的 `EXPRESSION_MIN_MS/MAX_MS`）
- **本地离线语音输入**：左下角电话图标，按下开始录音、**停 2 秒自动断句**出结果（电话式），
  跑的是**阿里达摩院 FunASR 的 Paraformer 中文模型**，全在本机推理，不联网、不花 API 的钱、录音不出手机
- **语音朗读回复**：接 **豆包（火山引擎）双向流式 TTS**，AI 回复自动念出来；
  播放时用音量驱动 Live2D 口型，模型会跟着「说话」
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

# 给手机装的话建议打 release（R8 压缩 + 只带 64 位原生库，约 148MB，debug 包会到 195MB）
.\gradlew.bat :app:assembleRelease
# release 已配置成用 debug 签名，所以能直接安装
```

踩过的坑：

- 不要用系统 PATH 里的 JDK 8 编译，AGP 会直接失败
- 工程刻意不用 `jvmToolchain()`——它会去 github.com 下载 JDK，网络不通时构建必挂；改用 `compileOptions` 加 `jvmTarget` 对齐字节码，不联网
- 最低 API 24，target 37；原生库只带 arm64-v8a / x86_64（真机 + 64 位模拟器够了，砍掉 32 位 x86 省约 36MB）
- **别引 `material-icons-extended`**：那套库 5000+ 图标 × 5 风格，不开 R8 的 debug 包会因此多出约 79MB 的 dex。
  本项目只用核心图标集（Settings / Phone / Send），切换图标是本地矢量 `res/drawable/ic_swap_horiz.xml`
- 开 R8 后必须保留 JNI 相关的类，见 `app/src/main/keepRules/rules.keep`（原因写在文件注释里，是真实踩出来的）

## 使用

1. 装上 App，等模型加载（首帧 3 到 5 秒）
2. 点左上角设置按钮：
   - **DeepSeek 对话**：填 API Key（在 platform.deepseek.com 申请）
   - **豆包语音朗读**：填 App ID / Access Token / 音色 ID（在火山引擎控制台开通「语音合成大模型」后获取），
     打开「自动朗读回复」，可以点「试听一句」当场验证
3. 底部输入框开聊；没填 Key 会提示
4. 右上角胶囊按钮切换 大肥鱼 / DeepSeek
5. 想说话就点左下角**电话图标**：变红 = 正在录音，再点一下 = 停止并识别，文字会填进输入框

## 语音朗读（豆包 TTS）

- **接口**：火山引擎豆包**双向流式 TTS**（WebSocket），官方文档
  [bidirectional-streaming-text-to-speech-websocket](https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-text-to-speech-websocket?lang=zh)
- **端点**：`wss://openspeech.bytedance.com/api/v3/tts/bidirection`
- **鉴权**：`Authorization: Bearer;<Access Token>`（注意是**分号**）+ `X-Api-App-Key`（App ID）
  + `X-Api-Access-Key` + `X-Api-Resource-Id` + `X-Api-Connect-Id`
- **协议**：二进制帧 + 事件机。帧头 4 字节（`0x11` 版本 / `0x14` 客户端 / `0x94` 服务端响应 / `0xB4` 服务端音频，
  JSON 不压缩），后面跟 event（i32 大端）、可选的 session_id、payload。
  流程：`StartConnection(1)` → `ConnectionStarted(50)` → `StartSession(100)` → `SessionStarted(150)`
  → `TaskRequest(200)`（带文本）→ 一串 `350/352/351`（音频在 `0xB4` 帧里）→ `FinishSession(102)` → `SessionFinished(152)` → `FinishConnection(2)`
- **音频**：请求 PCM 24kHz 单声道，收到音频块立刻丢给 `AudioTrack` 边收边放（不用等整段合成完）
- **口型同步**：播放线程顺便算每块 PCM 的 RMS，喂给 `Live2DController.setMouthOpen()`，模型嘴型跟着声音动
- **打断（barge-in）**：发新消息或重新朗读时立刻关连接、清播放队列
- **参数**：请求里的 `speaker` 就是音色 ID（如 `zh_female_cancan_mars_bigtts`）；
  `X-Api-Resource-Id` 默认 `volc.service_type.10029`（语音合成大模型），换成 2.0 模型时改成 `seed-tts-2.0`
- **服务端报错**会以 `0x94` 帧带回 `code` + `message`，App 直接把它显示成一条聊天气泡，方便对着改配置

## 语音识别（本地离线 ASR）

- **模型**：阿里达摩院 FunASR 的 **Paraformer-zh-small**（2024-03-09 版），经 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 导出成 onnx
- **运行时**：sherpa-onnx 官方 Android AAR（`app/libs/sherpa-onnx-1.13.8.aar`，含各 ABI 的 so + Kotlin API）
- **流程**：`AudioRecord` 录 16kHz 单声道 → 按下停止时把整段音频喂给 Paraformer 解码 → 文字追加到输入框
- **实测速度**（模拟器 CPU）：模型加载约 1.5~2.6 秒；3 秒音频解码 66ms、33 秒音频 750ms
- **模型参数**写在 `app/src/main/java/com/example/dsaiapp/asr/AsrEngine.kt` 顶部（`MODEL` / `TOKENS` 常量），
  换模型只要替换 `app/src/main/assets/asr/` 下的文件
- 录音上限 60 秒（超过自动停止并识别），识别为空会提示重说，不会崩

### 模型与运行时的版本要对上（重要）

sherpa-onnx **初始化失败时会直接 `exit(-1)`**（表现为进程静默消失、无 Java 异常，
`dumpsys activity exit-info` 里是 `reason=1 (EXIT_SELF) status=255`）。
最常见的原因就是**模型太旧、元数据对不上**：例如 `streaming-zipformer-multi-zh-hans-int8-2023-12-13`
配 1.13.8 运行时会在读 encoder metadata 时报 `'attention_dims' does not exist in the metadata` 然后退出。
换用与运行时同代的模型（如这里的 Paraformer-2024）即可。

## 目录结构

```text
app/src/main/java/com/example/dsaiapp/
├── MainActivity.kt          # 全部 UI（Compose）：顶栏 / 舞台 / 聊天 / 设置对话框 / 输入栏（含语音按钮）
├── asr/
│   └── AsrEngine.kt         # 本地离线 ASR：录音 + Paraformer 解码（sherpa-onnx）
├── tts/
│   └── TtsEngine.kt         # 豆包双向流式 TTS：WS 帧编解码 + AudioTrack 边收边放 + 口型音量
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
app/src/main/assets/live2d/         # 两只 Live2D 模型的 model3.json / moc3 / 贴图 / 表情 / 动作
app/src/main/assets/asr/            # Paraformer 语音模型（model.int8.onnx 约 78MB + tokens.txt）
app/libs/Live2DCubismCore.aar       # Cubism Core（含各 ABI 的 so）
app/libs/sherpa-onnx-1.13.8.aar     # ASR 运行时（含各 ABI 的 so + Kotlin API）
app/src/main/keepRules/rules.keep   # R8 必须保留的 JNI 类（Live2D Core / sherpa-onnx）
LICENSE             # 模型与第三方组件的署名、开源协议（必读）
```

> 仓库里最大的两个文件是 ASR 模型（78MB）和 sherpa-onnx AAR（48MB）——它们让仓库能直接构建出可用 App，
> 但也让 git 仓库变重（约 136MB）。不想要大文件的话，把这两项从 git 里去掉，改成首次运行时从
> [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载也可以。

## 排查备忘

| 症状 | 原因 |
| --- | --- |
| 模型整体黑色剪影 | 纹理没生成 mipmap，而框架绘制时用 mipmap 过滤采样，不完整纹理采出纯黑。上传后必须调 glGenerateMipmap |
| 模型消失 / 只剩背景 | 渲染器创建前没设置离屏目标尺寸；或 GL 上下文重建后没有重建渲染器和纹理绑定 |
| 开 R8 后一启动就闪退 | Live2D Core 的 `JNI_OnLoad` 按类名 `FindClass` 找类，被 R8 改名就 `ClassNotFoundException` 直接 abort。见 `keepRules/rules.keep` |
| 语音按钮一按进程就没了 | sherpa-onnx 模型初始化失败会 `exit(-1)`（`exit-info` 里 `reason=1 (EXIT_SELF) status=255`），通常是模型与运行时版本不匹配 |
| 语音识别总是"没听清" | 模拟器的虚拟麦克风基本收不到人声，真机测试才准；也确认已授予录音权限 |
| 朗读不出声 / 报「语音服务报错」 | 多半是凭证或资源 ID 不对：App ID、Access Token、音色 ID 三者要配套，资源 ID 默认 `volc.service_type.10029`。报错原文会显示在聊天气泡里 |
| 朗读时提示连接失败 | TTS 是云端接口，模拟器/校园网连不上外网时会失败（离线 ASR 不受影响，它纯本地） |
| 模拟器里 DNS/网络报错 | 校园网等环境下模拟器 NAT 容易失效，真机加正常网络即可，跟 API Key 无关 |
| gradle 锁文件拒绝访问 | 命令行构建要写用户目录下的 .gradle，沙箱或权限需放行该目录 |

## 资源与致谢

两个 Live2D 模型均为**开源模型，版权归原作者所有，本仓库按作者要求保留署名**。完整署名与协议全文见仓库根目录的 **LICENSE**：

- **模型1「大肥鱼」**：作者 **B站 @Mao绒绒家园**
- **模型2「DeepSeek」**：来源 github.com/lrplrplrp/dsh-live2d，协议 Apache License 2.0
- **Cubism SDK for Java 5-r.5**：© Live2D Inc.，Live2D Open Software License；公开分发前请自行确认 Live2D 的 SDK 发行许可（个人/小规模通常免费，商用前问一次 Live2D 官方）
- **语音识别模型**：阿里达摩院 **FunASR / Paraformer**（经 sherpa-onnx 导出为 onnx），协议以 FunASR 与模型仓库说明为准
- **sherpa-onnx**：k2-fsa 开源项目（Apache License 2.0），提供本地推理运行时
- **OkHttp**：Square 开源项目（Apache License 2.0），TTS 的 WebSocket 客户端
- **语音合成服务**：[火山引擎豆包语音](https://www.volcengine.com/product/voice-tech)（TTS 为云端接口，按量计费，需自行开通）
- API 服务：DeepSeek Open Platform（platform.deepseek.com）

## TODO

- [ ] 语音输入加 VAD 自动断句（现在要手动按第二下停止）
- [ ] 流式输出（SSE）+ 边生成边朗读
- [ ] 对话历史持久化
- [ ] 表情随 API 返回的结构化情绪标签联动（现在是文本关键字匹配）
