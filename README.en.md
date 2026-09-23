# BoldTextFix

[简体中文](README.md) | **English**

BoldTextFix is a Minecraft client-side Fabric mod that improves the appearance of bold text, with adjustable bold rendering methods and support for local bold fonts.

[Modrinth](https://modrinth.com/mod/boldtextfix) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/boldtextfix)

## Features

- **Mask Dilation**: expands the glyph outlines from font resource packs on demand to reduce ghosting caused by repeated offset draws. Generated results are reused through memory and disk caches.
- **Vanilla Offset**: preserves Minecraft's second offset draw and lets you adjust its strength.
- **Custom Bold Font**: renders bold text with a local TTF/OTF font, with adjustable size and clarity, while leaving regular text unchanged.
- Compare bold and regular text in real time in the settings screen, drag in font files, view generation progress, and limit glyph generation speed.
- Includes text compatibility handling for Iris shaders.

## Usage

Place the mod JAR for your Minecraft version in the client's `mods/` directory. The server does not need the mod.

Open the settings screen through Mod Menu or a keybind you assign in Minecraft's controls settings. Mod Menu is optional. The mod bundles the Fabric API modules it needs, so you do not need to install the full Fabric API separately.

The settings screen includes a master toggle and supports seven languages.

This development line targets **Minecraft 26.3 / Fabric** and requires Java 25 and Fabric Loader 0.19.5 or newer. Other Minecraft versions require a corresponding port. See [gradle.properties](gradle.properties) and [build.gradle](build.gradle) for the mod and dependency versions.

Place custom fonts in `config/boldtextfix/boldfonts/` within your game instance, or drag them directly into the mod's settings screen. TTF/OTF files are supported, with a maximum size of 128 MiB per file. The mod does not include or download fonts.

Settings are saved in `config/boldtextfix.json`. The mask cache is stored in `config/boldtextfix/cache/v2/` and shared by regular use and previews; new glyphs still need to be generated when first encountered. See the [development guide (Chinese)](docs/development.md) for font fallback order and cache behavior.

## Building from source

Install JDK 25 and set `JAVA_HOME`, or make sure Gradle can discover that toolchain.

Windows:

```powershell
.\gradlew.bat build
```

Linux / macOS:

```sh
sh ./gradlew build
```

The first build downloads Gradle, Minecraft, and the build dependencies. Output is written to `build/libs/`. The main file is named `boldtextfix-<version>.jar`; the JAR with the `-sources` suffix is for reading the source code and is not the file players should install.

This repository can be built independently. Include the Gradle Wrapper and the pinned compile-time dependency in `libs/modmenu/` when committing the source. The Mod Menu JAR is used only to compile the optional integration and is not bundled in the finished mod.

## Development

| Path | Contents |
| --- | --- |
| `src/main/java/` | Configuration, font rendering, caching, UI, and Mixins |
| `src/main/resources/` | Mod metadata, translations, and icons |
| `tests/` | Font, configuration, UI, and cross-process cache regression checks |
| `gradle/` | Gradle Wrapper and validation tasks |
| `docs/` | Source structure and verification procedures |
| `libs/`, `licenses/` | Pinned compile-time dependencies and their licenses |

See the [development guide (Chinese)](docs/development.md) for source entry points, threading rules, and test commands. The build checks that entry point classes and Mixins are packaged correctly. Font, UI, and cache regression checks must be run separately as described in the guide.

When reporting an issue, include the mod version, Minecraft and loader versions, relevant mods and resource packs, steps to reproduce the problem, and relevant logs. Before sharing a font or resource pack, check that its license permits redistribution.

## License

This project is licensed under the [MIT License](LICENSE). Third-party components retain their own licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md).
