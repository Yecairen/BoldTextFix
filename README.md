# BoldTextFix

**简体中文** | [English](README.en.md)

BoldTextFix 是一个 Minecraft 客户端 Fabric 模组，用于改善粗体文本的显示效果，提供可调的加粗方案和本地粗字体支持。

[Modrinth](https://modrinth.com/mod/boldtextfix) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/boldtextfix)

## 功能

- **蒙版膨胀**：按需扩张字体资源包的字形轮廓，减少重复偏移绘制造成的重影；生成结果复用内存和磁盘缓存。
- **原版偏移**：保留原版的二次偏移绘制，并允许调整偏移强度。
- **自选粗字体**：使用本地 TTF/OTF 渲染粗体，调整大小和清晰度，普通文本保持原样。
- 在设置界面实时对比粗体和普通文本，支持拖入字体、生成进度提示及生成速度限制。
- 包含 Iris 光影下的文字兼容处理。

## 使用

将对应游戏版本的模组 JAR 放入客户端的 `mods/` 目录。服务器无需安装。

可通过 Mod Menu 或在游戏按键设置中自行绑定的快捷键打开配置界面。Mod Menu 为可选集成；成品内嵌所需的 Fabric API 子模块，无需额外安装完整 Fabric API。

配置界面提供模组总开关，并包含七种语言。

本开发线面向 **Minecraft 26.3 / Fabric**，需要 Java 25 和 Fabric Loader 0.19.5 或更新版本。其他游戏版本需要对应的移植版本。构建版本与依赖版本见 [gradle.properties](gradle.properties) 和 [build.gradle](build.gradle)。

自选字体放入游戏实例的 `config/boldtextfix/boldfonts/`，也可以直接拖入模组设置界面。支持 TTF/OTF，单文件最大 128 MiB。模组不随附或下载字体。

配置保存在 `config/boldtextfix.json`。蒙版缓存位于 `config/boldtextfix/cache/v2/`，正文与预览共用；首次出现的新字形仍需生成。字体回退顺序及缓存约定见 [开发说明](docs/development.md)。

## 从源码构建

安装 JDK 25，并设置 `JAVA_HOME`，或让 Gradle 能发现该工具链。

Windows：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```sh
sh ./gradlew build
```

首次构建会下载 Gradle、Minecraft 和构建依赖。输出位于 `build/libs/`，主文件名为 `boldtextfix-<版本>.jar`；带 `-sources` 后缀的 JAR 用于阅读源码，不是玩家安装文件。

本仓库可独立构建。Gradle Wrapper 和 `libs/modmenu/` 中的固定编译依赖需要一并提交；Mod Menu JAR 仅用于编译可选集成，不会打包进成品。

## 开发

| 路径 | 内容 |
| --- | --- |
| `src/main/java/` | 配置、字体渲染、缓存、界面及 Mixin |
| `src/main/resources/` | 模组元数据、语言、图标 |
| `tests/` | 字体、配置、界面及跨进程缓存回归 |
| `gradle/` | Gradle Wrapper 与验证任务 |
| `docs/` | 源码结构和验证方法 |
| `libs/`、`licenses/` | 固定编译依赖与相应许可证 |

源码入口、线程约定和测试命令见 [开发说明](docs/development.md)。构建会检查入口类与 Mixin 是否完整打包；字体、界面和缓存回归需按文档另行运行。

反馈问题时，请提供模组版本、Minecraft 与加载器版本、相关模组及资源包、复现步骤和必要的日志。分享字体或资源包前，请确认其许可允许分发。

## 许可证

项目采用 [MIT 许可证](LICENSE)。第三方组件保留各自许可证，见 [第三方组件说明](THIRD_PARTY_NOTICES.md)。
