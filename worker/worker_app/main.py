import os

from fastapi import FastAPI
from pydantic import BaseModel
from redis import Redis
from rq import Queue

from worker_app.tasks import process_report


class ExtractJobRequest(BaseModel):
    report_id: int
    filename: str
    raw_file_path: str


app = FastAPI(title="SQLAdvisor Worker")
redis = Redis.from_url(os.getenv("REDIS_URL", "redis://redis:6379/0"))
queue = Queue("awr", connection=redis)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/jobs/extract")
def enqueue_extract_job(request: ExtractJobRequest):
    job = queue.enqueue(
        process_report,
        request.model_dump(),
        job_timeout="30m",
        result_ttl=3600,
        failure_ttl=86400,
    )
    return {"job_id": job.id, "status": "QUEUED"}
