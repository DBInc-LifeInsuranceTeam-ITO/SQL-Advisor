FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-kor \
        libglib2.0-0 \
        libgl1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY worker_app ./worker_app

ENV PYTHONUNBUFFERED=1
ENV REDIS_URL=redis://redis:6379/0
ENV API_BASE_URL=http://api:8080
ENV AWR_STORAGE_ROOT=/app/data/awr
ENV OCR_LANG=eng+kor
ENV OCR_MAX_PAGES=20

EXPOSE 8081

CMD ["sh", "-c", "uvicorn worker_app.main:app --host 0.0.0.0 --port 8081 & rq worker --url \"$REDIS_URL\" awr"]
