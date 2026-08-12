"""Package a bilingual timed LRC and its M4A as an importable .bessarticle."""

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path


TIMED_LINE = re.compile(
    r"^\[(?P<minutes>\d+):(?P<seconds>\d+(?:\.\d+)?)\]"
    r"(?:\[(?P<section>[^\]]+)\])?\s*"
    r"(?P<english>.*?)\s*｜\s*(?P<chinese>.+?)\s*$"
)
TITLE_LINE = re.compile(r"^\[ti:(?P<title>.*)]$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def audio_duration_ms(audio: Path) -> int:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        raise SystemExit("ffprobe not found on PATH")
    result = subprocess.run(
        [
            ffprobe,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return round(float(result.stdout.strip()) * 1000)


def parse_lrc(path: Path, duration_ms: int) -> tuple[str, list[dict]]:
    title = path.stem
    rows: list[tuple[int, str, str]] = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        title_match = TITLE_LINE.match(line.strip())
        if title_match:
            title = title_match.group("title").strip() or title
            continue
        match = TIMED_LINE.match(line.strip())
        if not match:
            continue
        start_ms = round(
            (
                int(match.group("minutes")) * 60
                + float(match.group("seconds"))
            )
            * 1000
        )
        english = match.group("english").strip()
        chinese = match.group("chinese").strip()
        if not english or not chinese:
            raise SystemExit(f"blank bilingual line at {start_ms} ms")
        rows.append((start_ms, english, chinese))

    if not rows:
        raise SystemExit("no bilingual timed lines found")
    if rows != sorted(rows, key=lambda row: row[0]):
        raise SystemExit("LRC timestamps are not ascending")

    paragraphs = []
    for index, (start_ms, english, chinese) in enumerate(rows):
        end_ms = rows[index + 1][0] if index + 1 < len(rows) else duration_ms
        if end_ms <= start_ms or end_ms > duration_ms:
            raise SystemExit(f"invalid cue {start_ms}..{end_ms}")
        paragraphs.append(
            {
                "textEn": english,
                "textZh": chinese,
                "startMs": start_ms,
                "endMs": end_ms,
            }
        )
    return title, paragraphs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lrc", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--article-id", default="ART-WONTAI-COMPANY-PROFILE")
    parser.add_argument("--title-en", default="Wontai Group Company Profile")
    parser.add_argument("--topic", default="Wontai Group and Vanadium Flow Battery Energy Storage")
    parser.add_argument("--content-version", default="2026.07.29.company-profile-andrew-v1")
    args = parser.parse_args()

    if not args.lrc.is_file() or not args.audio.is_file():
        raise SystemExit("LRC or audio file is missing")

    duration_ms = audio_duration_ms(args.audio)
    title_zh, paragraphs = parse_lrc(args.lrc, duration_ms)
    audio_file = f"audio/{args.article_id}.m4a"
    article_content = json.dumps(
        {"title": args.title_en, "paragraphs": paragraphs},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    manifest = {
        "schemaVersion": 2,
        "packageId": "bess-article",
        "contentVersion": args.content_version,
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "articles": [
            {
                "id": args.article_id,
                "title": args.title_en,
                "titleZh": title_zh,
                "topic": args.topic,
                "paragraphs": paragraphs,
                "audioFile": audio_file,
                "durationMs": duration_ms,
                "contentHash": sha256(article_content),
                "contentScope": "BESS",
            }
        ],
    }
    manifest_bytes = json.dumps(
        manifest,
        ensure_ascii=False,
        indent=2,
    ).encode("utf-8")
    audio_bytes = args.audio.read_bytes()
    files = {
        "manifest.json": manifest_bytes,
        audio_file: audio_bytes,
    }
    checksum_bytes = (
        "\n".join(f"{sha256(data)}  {name}" for name, data in sorted(files.items()))
        + "\n"
    ).encode("utf-8")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr("checksums.sha256", checksum_bytes)

    print(
        f"[ok] {args.out} article={args.article_id} "
        f"paragraphs={len(paragraphs)} durationMs={duration_ms}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
