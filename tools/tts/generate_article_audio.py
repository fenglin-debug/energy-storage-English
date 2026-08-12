"""Build the bundled .bessarticle pack from outputs/articles_src/*.json.

Pipeline: read article sources -> edge-tts synthesize each article's full
English text -> capture TTS word boundaries -> ffmpeg transcode to
M4A/AAC-LC/mono/24kHz/64kbps -> measure real duration with ffprobe -> emit:

    article_pack/manifest.json     (schemaVersion=2, packageId=bess-article)
    article_pack/checksums.sha256
    article_pack/audio/<articleId>.m4a

Idempotent: existing non-empty audio files are skipped and reused.
"""

import argparse
import asyncio
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

VOICE = "en-US-AndrewNeural"
RATE = "-5%"

FFMPEG_CANDIDATES = [
    r"C:\Users\fengl\AppData\Local\Microsoft\WinGet\Packages"
    r"\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
    r"\ffmpeg-8.1.1-full_build\bin\ffmpeg.exe",
]


def find_tool(name: str) -> str:
    for path in FFMPEG_CANDIDATES:
        p = Path(path.replace("ffmpeg.exe", f"{name}.exe"))
        if p.is_file():
            return str(p)
    found = shutil.which(name)
    if found:
        return found
    sys.exit(f"{name} not found; install ffmpeg or extend FFMPEG_CANDIDATES")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def transcode(ffmpeg: str, mp3: bytes, out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(".tmp.m4a")
    proc = subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "mp3", "-i", "pipe:0",
            "-ac", "1", "-ar", "24000", "-b:a", "64k",
            "-c:a", "aac", "-f", "ipod", str(tmp),
        ],
        input=mp3,
        capture_output=True,
    )
    if proc.returncode != 0 or not tmp.is_file() or tmp.stat().st_size == 0:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"ffmpeg failed: {proc.stderr.decode(errors='replace')[:200]}")
    tmp.replace(out_path)


def probe_duration_ms(ffprobe: str, path: Path) -> int:
    proc = subprocess.run(
        [
            ffprobe, "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        capture_output=True,
        text=True,
    )
    return int(float(proc.stdout.strip()) * 1000)


async def synth_article(
    sem,
    ffmpeg,
    article: dict,
    audio_path: Path,
    voice: str,
    overwrite: bool,
    retries=3,
):
    if not overwrite and audio_path.is_file() and audio_path.stat().st_size > 0:
        return article["id"], None
    import edge_tts

    text = "\n\n".join(p["textEn"] for p in article["paragraphs"])
    async with sem:
        for attempt in range(1, retries + 1):
            try:
                communicate = edge_tts.Communicate(text, voice, rate=RATE)
                mp3 = bytearray()
                boundaries = []
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        mp3.extend(chunk["data"])
                    elif chunk["type"] == "WordBoundary":
                        boundaries.append({
                            "offset": chunk["offset"] // 10_000,
                            "duration": chunk["duration"] // 10_000,
                            "text": chunk["text"],
                        })
                if not mp3:
                    raise RuntimeError("edge-tts returned no audio")
                await asyncio.to_thread(transcode, ffmpeg, bytes(mp3), audio_path)
                article["_wordBoundaries"] = boundaries
                return article["id"], None
            except Exception as exc:  # noqa: BLE001
                if attempt == retries:
                    return article["id"], str(exc)
                await asyncio.sleep(1.5 * attempt)
    return article["id"], "unreachable"


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src-dir", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--voice", default=VOICE)
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace every bundled article audio file with the selected voice",
    )
    args = parser.parse_args()

    ffmpeg = find_tool("ffmpeg")
    ffprobe = find_tool("ffprobe")

    articles = []
    for f in sorted(args.src_dir.glob("ART-*.json")):
        a = json.loads(f.read_text(encoding="utf-8"))
        if a.get("topic", "").startswith("风电"):
            continue
        normalized = []
        for paragraph in a["paragraphs"]:
            english = [
                value.strip()
                for value in re.split(r"(?<=[.!?])\s+", paragraph["textEn"])
                if value.strip()
            ]
            chinese = [
                value.strip()
                for value in re.split(r"(?<=[。！？])", paragraph["textZh"])
                if value.strip()
            ]
            if english and len(english) == len(chinese):
                normalized.extend(
                    {"textEn": en, "textZh": zh}
                    for en, zh in zip(english, chinese, strict=True)
                )
            else:
                normalized.append(paragraph)
        a["paragraphs"] = normalized
        articles.append(a)
    if not articles:
        sys.exit(f"no ART-*.json under {args.src_dir}")
    print(
        f"[plan] {len(articles)} articles, voice={args.voice}, "
        f"overwrite={args.overwrite}"
    )

    sem = asyncio.Semaphore(args.concurrency)
    jobs = [
        synth_article(
            sem,
            ffmpeg,
            a,
            args.out_dir / "audio" / f"{a['id']}.m4a",
            args.voice,
            args.overwrite,
        )
        for a in articles
    ]
    failures = []
    for result in asyncio.as_completed(jobs):
        article_id, error = await result
        if error:
            failures.append(f"{article_id}: {error}")
        print(f"[progress] {article_id} {'FAILED' if error else 'ok'}")
    if failures:
        print("[FAILED]:\n" + "\n".join(failures), file=sys.stderr)
        return 1

    # manifest with real durations
    entries = []
    for a in articles:
        audio_rel = f"audio/{a['id']}.m4a"
        audio_path = args.out_dir / audio_rel
        duration_ms = probe_duration_ms(ffprobe, audio_path)
        content = json.dumps(
            {"title": a["title"], "paragraphs": a["paragraphs"]},
            ensure_ascii=False, sort_keys=True,
        )
        boundaries = a.get("_wordBoundaries", [])
        cursor = 0
        timed_paragraphs = []
        total_words = sum(len(re.findall(r"\b[\w'-]+\b", p["textEn"])) for p in a["paragraphs"])
        for index, paragraph in enumerate(a["paragraphs"]):
            word_count = len(re.findall(r"\b[\w'-]+\b", paragraph["textEn"]))
            if boundaries and cursor < len(boundaries):
                start_ms = boundaries[cursor]["offset"]
                last_index = min(cursor + max(word_count, 1) - 1, len(boundaries) - 1)
                last = boundaries[last_index]
                end_ms = last["offset"] + last["duration"]
            else:
                start_ms = int(duration_ms * cursor / max(total_words, 1))
                end_ms = int(duration_ms * (cursor + word_count) / max(total_words, 1))
            if index == len(a["paragraphs"]) - 1:
                end_ms = duration_ms
            timed_paragraphs.append({
                **paragraph,
                "startMs": max(0, start_ms),
                "endMs": min(duration_ms, max(start_ms + 1, end_ms)),
            })
            cursor += word_count
        entries.append({
            "id": a["id"],
            "title": a["title"],
            "titleZh": a.get("titleZh", ""),
            "topic": a["topic"],
            "paragraphs": timed_paragraphs,
            "audioFile": audio_rel,
            "durationMs": duration_ms,
            "contentHash": sha256_bytes(content.encode("utf-8")),
            "contentScope": "BESS",
        })

    manifest = {
        "schemaVersion": 2,
        "packageId": "bess-article",
        "contentVersion": "2026.07.29.bess-v2",
        "articles": entries,
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=1).encode("utf-8")
    (args.out_dir / "manifest.json").write_bytes(manifest_bytes)

    # checksums over manifest + audio
    lines = []
    files = [("manifest.json", manifest_bytes)]
    for a in articles:
        rel = f"audio/{a['id']}.m4a"
        files.append((rel, (args.out_dir / rel).read_bytes()))
    for rel, data in sorted(files):
        lines.append(f"{sha256_bytes(data)}  {rel}")
    (args.out_dir / "checksums.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    total_min = sum(e["durationMs"] for e in entries) / 60000
    print(f"[ok] {len(entries)} articles packed to {args.out_dir} "
          f"(total audio {total_min:.1f} min)")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
