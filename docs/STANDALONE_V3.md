# Boob's V-3.0 — Built-in AI on Android

This is an in-place upgrade of the FreedomAI project; it retains the same Android application ID,
app storage, existing model files, photos, videos, and UI features.

## Default engines
- Image generation: built-in Qwen-Image-2.1 Turbo Android MNN/OpenCL; real text-to-image via `QwenImage21.generate`.
- Image editing: built-in Qwen-Image-2.1 Turbo via the already-integrated `NativeQwenEditor.edit`.
- Video: existing MobileI2V ONNX path by default; **experimental**, not verified to create good AI motion on a physical phone.
- Assistant: existing offline Gemma .task inference path by default; an offline model is needed.
- Nightmare, Local Dream, and online servers are optional alternatives, never prerequisites for installed built-in models.

## Model requirements
Built-in Qwen requires a one-time download of converted models into FreedomAI's own app storage
(approximately 11 GB), and a device with sufficient available memory. Installed Nightmare
models cannot be directly accessed across Android application sandboxes. Once the models are
installed, Qwen generation and editing run without paid service or internet access.

A home-screen `Install AI Image Model (one time)` button handles download/resume; no
desktop or separate application is needed. The MNN/OpenCL runtime is pinned to
scsonic/libQwenImage21 v0.4.0. Review Qwen model license terms before commercial use.

Default BALANCED output is roughly 384px, FAST 320px, and MAX QUALITY 512px,
with square/portrait/landscape choices. Native 4K output is not supported by this model;
the separate upscale feature remains available.

## Validation status
GitHub Actions runs Android compilation, lint, and JVM unit tests for engine selection
and image profile routing. This does **not** validate model files, memory pressure, image
quality, or video output on the user's physical Samsung phone. A separate on-device
generation test is still needed before calling the inference pipeline fully verified.

Version name 3.0, Android versionCode 42, user-visible name Boob's V-3.0.
