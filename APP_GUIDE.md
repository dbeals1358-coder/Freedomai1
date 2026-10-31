# Unbound AI Studio

Android 10+ app for photo editing and image generation.

## Install
GitHub Actions builds a debug APK after each main-branch push. Open Actions → Build Android APK → latest successful run → UnboundAIStudio-APK. Extract the downloaded artifact and install app-debug.apk on your phone.

## Use
Photo brightness/saturation editing and saving work without a server. For generation and AI editing, enter the HTTPS base URL of your own running AUTOMATIC1111-compatible server with --api enabled. Do not enter a website homepage or the /sdapi/v1 endpoint. The server must be reachable from the phone. This app does not supply a hosted model or free GPU service. Hosting costs and model terms depend on the server you choose. API authentication is not yet supported.

Generate: enter a prompt, tap Generate Image, then save or edit the result. Edit: choose a photo, adjust brightness/saturation, save a copy or describe a change and tap AI Edit Photo. AI editing uses whole-image img2img at strength 0.55; it does not offer masked inpainting. Source photos are preserved.

The server URL is saved on your phone. Prompts/photos are sent to that server when you request generation/editing. No API keys are bundled. No guarantee of unrestricted content or free computation is made.

## Validation
CI compiles the debug APK and runs Android lint. Device testing and a live server integration test are still required. In-progress images are held in memory and may be lost when Android recreates the activity.
