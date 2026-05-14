import os
import re
from pathlib import Path
from typing import Iterable

import fitz
import pdfplumber
import pytesseract
import requests
from bs4 import BeautifulSoup
from PIL import Image


API_BASE_URL = os.getenv("API_BASE_URL", "http://api:8080").rstrip("/")
AWR_STORAGE_ROOT = Path(os.getenv("AWR_STORAGE_ROOT", "/app/data/awr"))
OCR_LANG = os.getenv("OCR_LANG", "eng+kor")
OCR_MAX_PAGES = int(os.getenv("OCR_MAX_PAGES", "20"))
MIN_TEXT_CHARS = int(os.getenv("MIN_TEXT_CHARS", "300"))


def process_report(job: dict) -> dict:
    report_id = int(job["report_id"])
    filename = job["filename"]
    raw_file_path = Path(job["raw_file_path"])
    warnings: list[str] = []

    try:
        update_status(report_id, "EXTRACTING", ["worker extraction started"])
        text, extraction_warnings = extract_text(filename, raw_file_path)
        warnings.extend(extraction_warnings)

        text_dir = AWR_STORAGE_ROOT / "text"
        text_dir.mkdir(parents=True, exist_ok=True)
        text_path = text_dir / f"{report_id}-{safe_filename(filename)}.txt"
        text_path.write_text(text, encoding="utf-8")

        response = requests.post(
            f"{API_BASE_URL}/api/internal/worker/reports/{report_id}/extraction",
            json={"textPath": str(text_path), "warnings": warnings},
            timeout=120,
        )
        response.raise_for_status()
        return {"report_id": report_id, "text_path": str(text_path), "warnings": warnings}
    except Exception as exc:
        update_status(report_id, "FAILED", warnings + [f"worker failed: {exc}"])
        raise


def extract_text(filename: str, raw_file_path: Path) -> tuple[str, list[str]]:
    extension = raw_file_path.suffix.lower().lstrip(".") or filename.rsplit(".", 1)[-1].lower()
    if extension in {"html", "htm"}:
        return extract_html(raw_file_path), ["HTML extraction completed by worker"]
    if extension in {"txt", "text", "log"}:
        return decode_bytes(raw_file_path.read_bytes()), ["TXT extraction completed by worker"]
    if extension == "pdf":
        return extract_pdf(raw_file_path)
    return decode_bytes(raw_file_path.read_bytes()), ["Generic text extraction completed by worker"]


def extract_html(raw_file_path: Path) -> str:
    soup = BeautifulSoup(decode_bytes(raw_file_path.read_bytes()), "html.parser")
    lines: list[str] = []
    for element in soup.select("h1,h2,h3,h4,b,font"):
        append_line(lines, element.get_text(" ", strip=True))
    for row in soup.select("tr"):
        append_line(lines, row.get_text(" ", strip=True))
    body_text = soup.get_text("\n", strip=True)
    if len(body_text) > sum(len(line) for line in lines):
        append_line(lines, body_text)
    return "\n".join(deduplicate(lines))


def extract_pdf(raw_file_path: Path) -> tuple[str, list[str]]:
    warnings: list[str] = []
    pymupdf_text = extract_pdf_with_pymupdf(raw_file_path)
    if len(pymupdf_text.strip()) >= MIN_TEXT_CHARS:
        warnings.append("PDF text extraction completed by PyMuPDF")
        return pymupdf_text, warnings

    warnings.append("PyMuPDF text was insufficient; pdfplumber fallback used")
    pdfplumber_text = extract_pdf_with_pdfplumber(raw_file_path)
    if len(pdfplumber_text.strip()) >= MIN_TEXT_CHARS:
        return pdfplumber_text, warnings + ["PDF text extraction completed by pdfplumber"]

    warnings.append("pdfplumber text was insufficient; Tesseract OCR fallback used")
    ocr_text = extract_pdf_with_tesseract(raw_file_path)
    return ocr_text, warnings + [f"Tesseract OCR completed, pages_scanned<= {OCR_MAX_PAGES}"]


def extract_pdf_with_pymupdf(raw_file_path: Path) -> str:
    lines: list[str] = []
    with fitz.open(raw_file_path) as document:
        for page in document:
            append_line(lines, page.get_text("text"))
    return "\n".join(lines)


def extract_pdf_with_pdfplumber(raw_file_path: Path) -> str:
    lines: list[str] = []
    with pdfplumber.open(raw_file_path) as document:
        for page in document.pages:
            append_line(lines, page.extract_text() or "")
            for table in page.extract_tables() or []:
                for row in table:
                    append_line(lines, " ".join(cell or "" for cell in row))
    return "\n".join(lines)


def extract_pdf_with_tesseract(raw_file_path: Path) -> str:
    lines: list[str] = []
    with fitz.open(raw_file_path) as document:
        for page_index, page in enumerate(document):
            if page_index >= OCR_MAX_PAGES:
                break
            pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
            image = Image.frombytes("RGB", [pixmap.width, pixmap.height], pixmap.samples)
            append_line(lines, pytesseract.image_to_string(image, lang=OCR_LANG))
    return "\n".join(lines)


def update_status(report_id: int, status: str, warnings: list[str]) -> None:
    try:
        response = requests.post(
            f"{API_BASE_URL}/api/internal/worker/reports/{report_id}/status",
            json={"status": status, "warnings": warnings},
            timeout=15,
        )
        response.raise_for_status()
    except Exception:
        pass


def decode_bytes(content: bytes) -> str:
    for encoding in ("utf-8", "cp949", "euc-kr", "latin-1"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("utf-8", errors="replace")


def append_line(lines: list[str], value: str) -> None:
    cleaned = clean(value)
    if cleaned:
        lines.append(cleaned)


def deduplicate(lines: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for line in lines:
        if line not in seen:
            seen.add(line)
            result.append(line)
    return result


def clean(value: str) -> str:
    return re.sub(r"[ \t]+", " ", value or "").strip()


def safe_filename(filename: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9가-힣._-]", "_", filename)
    return sanitized or "awr-report.txt"
