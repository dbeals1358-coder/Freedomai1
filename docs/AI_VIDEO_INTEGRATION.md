# Realistic image-to-video integration plan

The shipped offline mode creates camera pan/zoom MP4s, **not AI-generated subject motion**. Wan 2.2 Turbo in Nightmare produced corrupted video on the test device, so do not present it as a working local engine.

## Architecture
- Keep offline pan/zoom as the reliable free fallback.
- Add a separate **AI Video (Cloud)** action backed by a developer-operated HTTPS service.
- Android uploads an image and prompt only after the user explicitly starts an online job.
- The service keeps its video-provider API key in server-side secrets. **Never bundle API keys in an APK.**
- Backend submits to a licensed image-to-video provider, polls asynchronously, and returns a downloadable MP4 or a job failure.
- App displays status, handles cancellation/timeouts, downloads the video and saves it to Movies/FreedomAI.

## Proposed API (not implemented)
POST /v1/video/jobs (multipart: image, prompt, duration_seconds) -> 202 {id, status}
GET /v1/video/jobs/{id} -> {status: queued|running|completed|failed, progress?, video_url?, error?}
DELETE /v1/video/jobs/{id} -> cancellation request

Server requirements: authentication, per-user quotas, consent checks, file size limits, encrypted transport, URL validation, output retention/deletion policy, cost caps, rate limiting and no public video URLs containing secrets.

## Local AI research
A true on-device diffusion video model must be benchmarked on the target Android phone for compatible weights/runtime, RAM, NPU/GPU support, temperature, latency, quality and licensing. No compatible working local AI video model has yet been validated. Avoid promising unlimited realistic generation.

## Release gate
Do not label Cloud AI as working until an actual service is connected and tested end-to-end. Build success alone does not prove generated video quality.
