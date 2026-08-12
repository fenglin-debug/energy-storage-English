"""Batch-generate real m4a audio for the BESS corpus via edge-tts + ffmpeg.

Build-time only (TDD: no runtime TTS in the app). Reads the text files
emitted by the packager (--emit-audio-texts), synthesizes mp3 with
edge-tts, transcodes to M4A / AAC-LC / mono / 24 kHz / 64 kbps with
ffmpeg, and writes the directory layout expected by `--audio-dir`:

    real_audio/turns/aud_TURN-0001.m4a
    real_audio/words/aud_word_WIND-0001.m4a
    real_audio/examples/aud_example_EX-0001.m4a
    real_audio/phrases/aud_phrase_PHR-0001.m4a

Idempotent: existing non-empty outputs are skipped, so re-running
resumes after network failures.
"""

import argparse
import asyncio
import shutil
import subprocess
import sys
from pathlib import Path

import edge_tts

VOICES = {
    # turns are split by speaker inside main(); directory voice here is the default.
    "turns": "en-US-AndrewNeural",
    "words": "en-US-AndrewNeural",
    "examples": "en-US-AndrewNeural",
    "phrases": "en-US-AndrewNeural",
}

FFMPEG_CANDIDATES = [
    r"C:\Users\fengl\AppData\Local\Microsoft\WinGet\Packages"
    r"\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
    r"\ffmpeg-8.1.1-full_build\bin\ffmpeg.exe",
]


def find_ffmpeg() -> str:
    for path in FFMPEG_CANDIDATES:
        if Path(path).is_file():
            return path
    found = shutil.which("ffmpeg")
    if found:
        return found
    sys.exit("ffmpeg not found; install it or extend FFMPEG_CANDIDATES")


def transcode_to_m4a(ffmpeg: str, mp3: bytes, out_path: Path) -> None:
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


async def synthesize_one(
    sem: asyncio.Semaphore,
    ffmpeg: str,
    voice: str,
    rate: str,
    text_file: Path,
    out_path: Path,
    retries: int,
    overwrite: bool,
) -> tuple[Path, str | None]:
    if not overwrite and out_path.is_file() and out_path.stat().st_size > 0:
        return out_path, None
    text = text_file.read_text(encoding="utf-8").strip()
    if not text:
        return out_path, f"empty text: {text_file}"
    async with sem:
        for attempt in range(1, retries + 1):
            try:
                communicate = edge_tts.Communicate(text, voice, rate=rate)
                mp3 = bytearray()
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        mp3.extend(chunk["data"])
                if not mp3:
                    raise RuntimeError("edge-tts returned no audio")
                await asyncio.to_thread(transcode_to_m4a, ffmpeg, bytes(mp3), out_path)
                return out_path, None
            except Exception as exc:  # noqa: BLE001 - retry any transient failure
                if attempt == retries:
                    return out_path, f"{text_file.name}: {exc}"
                await asyncio.sleep(1.5 * attempt)
    return out_path, "unreachable"


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--texts-dir", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--rate", default="-5%")
    parser.add_argument("--voice", default="en-US-AndrewNeural")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace all selected outputs so the voice is consistent",
    )
    args = parser.parse_args()

    ffmpeg = find_ffmpeg()
    sem = asyncio.Semaphore(args.concurrency)

    jobs = []
    for subdir in VOICES:
        src_dir = args.texts_dir / subdir
        if not src_dir.is_dir():
            print(f"[skip] missing dir: {src_dir}", file=sys.stderr)
            continue
        for text_file in sorted(src_dir.glob("*.m4a")):
            out_path = args.out_dir / subdir / text_file.name
            file_voice = args.voice
            jobs.append(
                synthesize_one(
                    sem, ffmpeg, file_voice, args.rate,
                    text_file, out_path, args.retries, args.overwrite,
                )
            )

    total = len(jobs)
    print(
        f"[plan] {total} audio files, voice={args.voice}, "
        f"concurrency={args.concurrency}, overwrite={args.overwrite}"
    )

    done = 0
    failures: list[str] = []
    for result in asyncio.as_completed(jobs):
        out_path, error = await result
        done += 1
        if error:
            failures.append(error)
        if done % 50 == 0 or done == total:
            print(f"[progress] {done}/{total} (failures={len(failures)})")

    if failures:
        print(f"\n[FAILED] {len(failures)} files:", file=sys.stderr)
        for f in failures[:20]:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print(f"[ok] all {total} files generated under {args.out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
