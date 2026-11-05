# Native Qwen Image 2.1 Turbo integration (in progress)

Goal: edit images inside FreedomAI without launching or depending on Nightmare.

Upstream Android library: https://github.com/scsonic/libQwenImage21 (Apache-2.0 code).
Model weights: https://huggingface.co/evankuo/Qwen-Image-2.1-MNN (check Qwen Research License before distribution).

## Required implementation
1. Vendor or pin the upstream `qwenimage21` Android library and required MNN/OpenCL native runtime; configure arm64-v8a build and reproducible dependency resolution.
2. Add a model manager with resumable downloads, checksums, free-space checks, license notice, and persistent storage under the app-specific external files directory. Never package multi-GB weights inside APK or silently download.
3. Create a native editor adapter running on a background thread. Use `QwenImage21.Options.turbo=true`, `edit(prompt, inputImage, Size.Tier.FAST, 6, seed, output, progress)`, and safe exception handling.
4. Connect existing Edit Image / AI Edit flow to native editor, retain original image, show actual stage/progress, allow cancellation, and report out-of-memory failures clearly.
5. Keep existing RealVis generation and Nightmare fallback available until native editor is tested.
6. Test model availability, permission/storage failures, process restart, portrait/landscape state retention, 384x384 Fast and 512x512 Standard, identity/background consistency, and device thermal/memory behavior.

## Important constraints
- Upstream recommends >=12 GB RAM and ~11 GB storage; Turbo requires additional model files.
- Native Android compatibility and actual on-device performance are **not yet verified in FreedomAI**.
- Fast resolution is recommended initially because upstream reports better identity preservation than Standard for edits.
- Do not claim a functional APK until a successful Android build and device editing test exist.
