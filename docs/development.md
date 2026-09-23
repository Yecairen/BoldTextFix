# 开发与维护

当前目标为 Fabric / Minecraft 26.3，运行时 Java 25，项目 Java 字节码目标为 21。源码使用游戏的正式名称。不要根据旧版本教程猜测 Mixin 目标；升级游戏时应核对目标版本字节码。

## 从哪里开始读

| 想修改的内容 | 入口 |
| --- | --- |
| 默认值、范围、保存格式和旧配置迁移 | `BoldTextFixConfig` |
| 界面布局、滚动、预览、字体导入操作 | `client/BoldTextFixConfigScreen` |
| 字体导入成功提示、文件名颜色与渐隐 | `client/FontImportNotice` |
| 按钮、滑条、字体列表项和说明边框的画法与输入处理 | `client/ConfigWidgets` |
| 字体文件检查、导入与字符/字形统计 | `BoldFontFiles`、`LocalBoldFont` |
| 自选字体加载、缓存、释放 | `CustomBoldFonts` |
| 自选字体及 Emoji 的选择顺序、字宽匹配 | `mixin/FontMixin` |
| 独立读取 Minecraft 本体字体 | `VanillaBoldFallback` |
| 蒙版膨胀的像素算法 | `DilationMask` |
| 从游戏字形获取像素、缓存键、纹理上传 | `DilationBoldBaker` |
| 后台队列、冷却、统计、8 秒提示续接 | `DilationRenderQueue` |
| 本地磁盘缓存读写与淘汰 | `GlyphDiskCache` |
| 选择是否修复与控制原版重复绘制 | `FontFixPolicy`、`mixin/GlyphInstanceMixin`、`mixin/BakedSheetGlyphMixin` |
| 渲染提示的布局与文字 | `client/DilationProgressOverlay`、`assets/boldtextfix/lang/` |

配置界面负责“放在哪里、操作什么设置”，控件负责“如何绘制、如何响应输入”。没有必要为每个按钮再创建接口或服务层。

`FontImportNotice` 仅负责绘制，不注册输入控件。成功导入后，从首个显示帧开始持续 8 秒并逐渐透明，文件名显示为金黄色。自选粗字体方案使用简短提示，另两种方案附带切换说明；显示期间切换方案不会改写已有提示。再次成功导入会更新提示并重新计时，失败的导入不会触发成功提示。

## 字形经过哪些步骤

蒙版膨胀：

1. `FontProviderOrigins` 和来源标记 Mixin 识别字形是否来自 Minecraft 本体资源。
2. `GlyphStitcherMixin` 记住普通字形的来源；此时不生成粗体副本。
3. 实际请求粗体时，`BakedSheetGlyphMixin` 根据 `FontFixPolicy` 决定是否调用 `DilationBoldBaker.ensureVariant`。
4. Baker 尝试已有纹理副本、内存/磁盘蒙版缓存。未命中时只复制当前字形的像素，交给队列。
5. `DilationMask` 在后台计算像素；生成结果交给磁盘缓存，无论正文或预览都使用同一路径。
6. 渲染线程将就绪结果上传到字体纹理；上传失败不撤销已完成像素的缓存写入。
7. `GlyphInstanceMixin` 与 `FontFixPolicy` 避免对已加粗字形再进行原版第二次偏移绘制。

自选粗字体：

- 普通字符：自选 TTF/OTF → 独立的原版字体。
- Emoji/符号：当前字体链中的第三方字形 → 自选 TTF/OTF → 独立的原版字体。
- `FontMixin` 的绘制和测量共用同一解析方法，避免字宽与实际显示不一致。
- `CustomBoldGlyph` 只包装已选出的第三方/自选字形以禁用重复加粗；不改变正常阴影、颜色和斜体。
- 数字走普通字符规则；Unicode 虽允许数字参与键帽 Emoji，但不能因此让普通数字优先使用第三方字体。

## 必须保留的约定

- UI 步长与配置存储精度不同。每像素 400 个整数刻度是为保留旧版 `0.0125 px` 数值；不要在读取、打开界面或保存其他选项时按新步长舍入。
- `configVersion` 和旧 `dilationRadius` 的读取仍用于迁移玩家配置，不是废弃代码。
- 磁盘格式为 v2，缓存算法标记为 v6；两者版本含义不同。
- 缓存键由版本、像素格式、字形尺寸、边距、膨胀半径和源像素组成，不含方向字段，也不查找旧方向格式的缓存。字形按需重新生成后，继续使用内存和本地磁盘缓存。
- 改变像素算法或缓存键结构时，应更新缓存算法标记并验证缓存不会混用。只改文件名、代码组织、注释不应使缓存失效。
- 字体对象、NativeImage 和 GPU 上传留在渲染线程。后台膨胀只接收复制的像素和不可变尺寸；磁盘线程负责写入和淘汰。
- 正常退出先关闭并等待字形队列，再等待磁盘写入，最后释放自选与回退字体。资源重载取消旧纹理任务，但保留可复用的蒙版缓存。
- Mixin 不会被普通 Java 调用引用；它们通过 `boldtextfix.mixins.json` 注册，不能因为“查不到调用”就删除。Iris 反射桥也不能按静态引用缺失误删。
- 三类字体提供器的内部类名、字段和上传方法是版本敏感部分。它们的反射读取集中在 Baker，升级 Minecraft 时优先核对这些位置。

## 无窗口验证

先构建当前源码，保证探针读取新生成的 JAR。构建的 `check` 阶段执行 `verifyBoldTextFixJar`，核对元数据声明的入口类与 Mixin；`writeVerificationClasspath` 将本机解析后的依赖路径写到 `build/verification/classpath.txt`，此文件无需提交。

PowerShell 5.1 或 PowerShell 7 下，使用 Java 25 执行：

```powershell
./tests/settings/run-probe.ps1 -JavaPath 'java' -TrueTypeFont '<真实 TrueType 字体的路径>' -AssetsDirectory '<Minecraft 26.3 的 assets 目录>'
./tests/cache/run-probe.ps1 -JavaPath 'java'
./tests/resources/run-probe.ps1 -JavaPath 'java' -MinecraftJar '<Minecraft 26.3 原版客户端 JAR>'
./tests/loader/run-probe.ps1 -JavaPath 'java'
```

- TrueType 测试字体需要有拉丁字母、空格和至少 100 个字符/字形。Windows 可使用本机 Arial Bold，其他系统可使用合适的开源 TTF；不随源码分发系统字体。
- 资源目录需要包含 26.3 的资源索引与对象，默认索引 ID 为 `34`，可通过 `-AssetIndex` 指定。脚本不下载游戏资源。
- 配置、滑条、多语言布局、TTF/CFF OTF、字符/字形统计、缺字回退、Emoji、冷却和提示续接由设置探针覆盖。
- `FontImportNoticeProbe` 随设置探针执行，检查三个方案的导入提示、文件名着色、8 秒渐隐，以及提示显示期间的点击、输入、滚动和方案切换；覆盖七种语言及六种界面尺寸。
- 缓存探针分两个 JVM 验证落盘、重启读取、坏缓存替换及关闭/重载后的保留。
- 可通过 `-ClasspathFile` 指定构建输出的依赖清单，通过 `-ModJar` 指定待测 JAR。默认验证当前工程的构建产物。
- `DilationCompatibilityProbe.java` 可在改动前后分别运行，对比 `PIXEL_DIGEST` 和 `CACHE_KEY_DIGEST`，分别检查像素结果与缓存键；主动更新缓存键时应确认像素结果仍一致。
- 输出只写入 `build/verification/` 的独立目录；不会操作玩家实例、配置或字体原文件。
- 字体与界面探针使用真实字体提供器和受控的纹理/界面替身，不启动游戏窗口。加载探针使用 Fabric Knot 应用全部 Mixin，并排除外置 Fabric API 与其他模组；它只验证类加载，不执行游戏主循环或 GPU 画面。

资源探针只向隔离目录放入成品 JAR，排除 Mod Menu、Placeholder API 和外置 Fabric API；通过游戏的资源仓库发现资源，检查七种语言全部条目及组件翻译，并重复资源重载。它不直接注入翻译表，也不启动窗口。`fabric-resource-loader-v1` 必须内嵌，负责将模组语言与纹理加入资源仓库。

## 文件与许可证

`src/main` 保存运行源码与资源；`tests/settings/`、`tests/cache/` 和 `tests/loader/` 保存无窗口回归。构建输出、日志、玩家字体、资源包与本机依赖清单均不属于源码。

工程代码使用根目录 `LICENSE` 的 MIT 许可证。第三方 JAR 保留自己的版权及许可，不能将它们的许可一并替换为项目许可。
