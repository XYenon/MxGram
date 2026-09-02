# AGENTS.md

This file applies to the entire repository.

## Project Goal

- This repository contains an LSPosed modern API 102 module for Telegram.
- The shipped behavior is intentionally limited to the interaction, message, sticker, protected-media, and profile-ID features documented in `README.md`.
- Prefer preserving that narrow scope unless the user explicitly asks for new behavior.

## Build And Verify

- Prefer using the Nix environment for all verification.
- First choice: `nix develop -c ./gradlew assembleDebug`
- If working interactively in the repo, `direnv allow` is expected to load the same environment from `.envrc`.
- Treat a successful debug build as the default verification step for code changes in this repository.
- Code formatting / linting is managed via `treefmt-nix`.
  - Format: `nix fmt`
  - CI-friendly checks: `nix flake check`

## Source Of Truth

- Main hook implementation: `app/src/main/kotlin/dev/xyenon/mxgram/TelegramHooksModule.kt`
- Xposed metadata: `app/src/main/resources/META-INF/xposed/`
- Android module config: `app/build.gradle.kts`
- Development environment: `flake.nix` and `.envrc`
- Formatting configuration: `treefmt.nix`

## Editing Guidance

- Do not add legacy Xposed APIs unless the user explicitly requests them.
- Keep the module compatible with LSPosed modern API 102.
- Prefer the smallest stable hook point in Telegram over broad or fragile hooks.
- When changing hook behavior, keep Telegram package scoping explicit.
- Do not check in generated files from `.direnv/`, `.gradle/`, `build/`, `app/build/`, or `result`.
- Do not rely on writing into the Android SDK path at build time; the Nix SDK is read-only by design.

## External Reference

- Telegram source for local reverse engineering is available at `../TelegramAndroid`.
- Use that tree to confirm class names, method names, and UI behavior before changing hooks.

## Documentation

- Keep `README.md` in sync when changing build steps, scope, hook behavior, or target package names.
- If build requirements change, update both `README.md` and this `AGENTS.md` in the same change.
