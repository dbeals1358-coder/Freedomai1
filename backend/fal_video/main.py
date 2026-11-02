"""Development-only fal.ai image-to-video backend for FreedomAI.
Deploy behind HTTPS with real user authentication, quotas and rate limiting before public use.
Secrets belong on the SERVER, never in the Android APK.
"""
import os
import tempfile
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Form, Header, HTTPException
import fal_client

app = FastAPI(title="FreedomAI video service")
MODEL = "fal-ai/wan/v2.2-5b/image-to-video"
MAX_IMAGE_BYTES = 8 * 1024 * 1024

def authorize(authorization: str | None):
    expected = os.environ.get("FREEDOMAI_BACKEND_TOKEN")
    if not expected or not authorization or authorization != f"Bearer {expected}":
        raise HTTPException(401, "Unauthorized")
    if not os.environ.get("FAL_KEY"):
        raise HTTPException(503, "Server FAL_KEY not configured")

@app.post("/v1/video/jobs")
async def create_video(
    image: UploadFile = File(...),
    prompt: str = Form(...),
    authorization: str | None = Header(default=None),
):
    authorize(authorization)
    if image.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(415, "Use a JPEG, PNG or WebP image")
    if not 1 <= len(prompt.strip()) <= 2000:
        raise HTTPException(400, "Prompt must be 1-2000 characters")
    data = await image.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(413, "Image exceeds 8 MB")
    suffix = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[image.content_type]
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(data)
        path = tmp.name
    try:
        image_url = fal_client.upload_file(path)
        handle = fal_client.submit(MODEL, arguments={"image_url": image_url, "prompt": prompt.strip()})
        return {"id": handle.request_id, "status": "queued"}
    finally:
        Path(path).unlink(missing_ok=True)

@app.get("/v1/video/jobs/{job_id}")
def get_video(job_id: str, authorization: str | None = Header(default=None)):
    authorize(authorization)
    if not job_id or len(job_id) > 128 or not all(c.isalnum() or c in "-_" for c in job_id):
        raise HTTPException(400, "Invalid job ID")
    handle = fal_client.AsyncRequestHandle if False else None  # no persistent in-memory job handles
    status = fal_client.status(MODEL, job_id, with_logs=False)
    state = type(status).__name__
    if state == "Completed":
        result = fal_client.result(MODEL, job_id)
        video = result.get("video", {})
        return {"id": job_id, "status": "completed", "video_url": video.get("url")}
    if state in {"InProgress", "InQueue"}:
        return {"id": job_id, "status": "running" if state == "InProgress" else "queued"}
    return {"id": job_id, "status": "failed", "error": state}
