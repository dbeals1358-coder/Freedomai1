# FreedomAI fal.ai backend (prototype)

This is a **development prototype**, not a deployed or production-ready service. It calls fal.ai's Wan 2.2 5B image-to-video endpoint; this is different from the local Nightmare installation.

## Setup
Create a fal.ai account and API key. Configure environment variables on your backend host:
- `FAL_KEY`: secret fal.ai API key.
- `FREEDOMAI_BACKEND_TOKEN`: temporary development bearer token (replace with per-user authentication for production).

Install dependencies with `pip install -r requirements.txt`; run `uvicorn main:app --host 127.0.0.1 --port 8000`.

POST `/v1/video/jobs` with multipart `image` and `prompt`, plus Authorization: Bearer <token>. Poll GET `/v1/video/jobs/{id}` with the same authorization.

**Not yet connected to Android.** Before deployment add HTTPS, authentication, rate limiting, billing caps, job ownership checks, safe file handling, retention rules, and error handling. Do not commit tokens. Cloud generation incurs charges.
