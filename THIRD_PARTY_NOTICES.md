# 第三方组件

工程代码适用根目录的 MIT 许可证。以下组件适用自己的版权和许可；保留随其分发的许可文件。

| 组件 | 版本/用途 | 许可证与来源 |
| --- | --- | --- |
| Fabric API Base | 2.0.6+fcdff87f5d，内嵌模块 | Apache-2.0；https://github.com/FabricMC/fabric |
| Fabric Key Mapping API | 2.0.8+3434d6d95d，内嵌模块 | Apache-2.0；https://github.com/FabricMC/fabric |
| Fabric Lifecycle Events | 4.1.9+ffef5f675d，内嵌模块 | Apache-2.0；https://github.com/FabricMC/fabric |
| Fabric Resource Loader | 3.0.4+fcdff87f5d，内嵌模块 | Apache-2.0；https://github.com/FabricMC/fabric |
| Mod Menu | 21.0.0-beta.1，仅编译可选集成 | MIT；https://github.com/TerraformersMC/ModMenu |
| Gradle Wrapper | 9.5.1，构建启动器 | Apache-2.0；https://gradle.org/ |

四个 Fabric API 模块由构建从 Fabric Maven 获取，以原始 JAR 形式内嵌，保留其内部许可证。完整 Fabric API 不作为外置运行前置。

Mod Menu 通过 `com.terraformersmc:modmenu:21.0.0-beta.1` 从官方 Maven 仓库解析，只用于编译可选集成，不进入 BoldTextFix 成品。主许可副本见 `licenses/ModMenu-MIT.txt`。

Gradle Wrapper JAR 内含 `META-INF/LICENSE`。Fabric Loader、Minecraft 及其他构建依赖由 Gradle 解析，不在此源码目录分发。

字体、资源包和 Minecraft 资源不是本项目源码的一部分。验证时从开发者指定的本机文件读取，不随源码打包。
