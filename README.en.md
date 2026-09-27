# BoldTextFix

[简体中文](README.md) | **English**

BoldTextFix is a Minecraft client-side Fabric mod that improves the appearance of bold text, with adjustable bold rendering methods and support for local bold fonts.

[Modrinth](https://modrinth.com/mod/boldtextfix) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/boldtextfix)

Current version: **1.3.1+mc26.3**.

## Features

- **Mask Dilation**: expands the glyph outlines from font resource packs on demand to reduce ghosting caused by repeated offset draws. Generated results are reused through memory and disk caches.
- **Vanilla Offset**: adjusts the second offset draw for resource-pack glyphs. Minecraft's own glyphs and vanilla fallback glyphs keep native bold rendering, independent of the mod's strength setting.
- **Custom Bold Font**: renders bold text with a local TTF/OTF font, with adjustable size and clarity, while leaving regular text unchanged.
- Compare bold and regular text in real time in the settings screen, drag in font files, and view generation progress. New glyph generation uses a fixed **0.05-second** cooldown after each completed glyph. Reading cached masks does not incur this cooldown.
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

This directory includes the complete Gradle Wrapper and builds independently. Mod Menu 21.0.0-beta.1 is resolved from its official Maven repository for optional compile-time integration; it is not bundled in the mod. No machine-specific paths or manually supplied JARs are required.

## Development

| Path | Contents |
| --- | --- |
| `src/main/java/` | Configuration, font rendering, caching, UI, and Mixins |
| `src/main/resources/` | Mod metadata, translations, and icons |
| `tests/` | Font, configuration, UI, and cross-process cache regression checks |
| `gradle/` | Gradle Wrapper and validation tasks |
| `docs/` | Source structure and verification procedures |
| `licenses/` | Copies of third-party licenses |

See the [development guide (Chinese)](docs/development.md) for entry points, threading rules, and test commands. `build` compiles every shipped probe, checks packaged entry points and Mixins, and runs configuration migration, glyph policy, cooldown, translation formatting and cross-process cache checks. Full font/UI probes require external fixtures and run separately; windowless probes do not establish in-game visual correctness.

When reporting an issue, include the mod version, Minecraft and loader versions, relevant mods and resource packs, steps to reproduce the problem, and relevant logs. Before sharing a font or resource pack, check that its license permits redistribution.

## License

This project is licensed under the [MIT License](LICENSE). Third-party components retain their own licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md).
