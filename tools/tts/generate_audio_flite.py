"""Generate missing BESS corpus audio locally with FFmpeg's Flite engine.

The packager emits UTF-8 text files with an ``.m4a`` suffix. This tool mirrors
that directory tree into real AAC/M4A files without sending corpus text to any
network service. Existing non-empty outputs are kept, so interrupted runs are
safe to resume.
"""

import argparse
import concurrent.futures
import shutil
import subprocess
import sys
from pathlib import Path


def find_ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if found:
        return found
    sys.exit("ffmpeg not found on PATH")


def synthesize(
    ffmpeg: str,
    text_file: Path,
    out_file: Path,
    voice: str,
) -> tuple[Path, str | None]:
    if out_file.is_file() and out_file.stat().st_size > 0:
        return out_file, None

    text = text_file.read_text(encoding="utf-8").strip()
    if not text:
        return out_file, f"empty text: {text_file}"

    out_file.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = out_file.with_suffix(".tmp.m4a")
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"flite=textfile={text_file.name}:voice={voice}",
        "-ac",
        "1",
        "-ar",
        "24000",
        "-b:a",
        "64k",
        "-c:a",
        "aac",
        "-f",
        "ipod",
        str(tmp_file.resolve()),
    ]
    result = subprocess.run(
        command,
        cwd=text_file.parent,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0 or not tmp_file.is_file() or tmp_file.stat().st_size == 0:
        tmp_file.unlink(missing_ok=True)
        message = result.stderr.strip().replace("\n", " ")[:300]
        return out_file, f"{text_file.name}: {message}"
    tmp_file.replace(out_file)
    return out_file, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--texts-dir", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--voice", choices=("awb", "kal", "kal16", "rms", "slt"), default="slt")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument(
        "--reference-dir",
        type=Path,
        help="only synthesize source files that are absent from this directory tree",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace selected outputs instead of keeping existing non-empty files",
    )
    args = parser.parse_args()

    ffmpeg = find_ffmpeg()
    text_files = sorted(args.texts_dir.rglob("*.m4a"))
    selected = [
        source
        for source in text_files
        if args.reference_dir is None
        or not (args.reference_dir / source.relative_to(args.texts_dir)).is_file()
    ]
    pending = [
        source
        for source in selected
        if args.overwrite
        or not (args.out_dir / source.relative_to(args.texts_dir)).is_file()
    ]
    print(
        f"[plan] {len(pending)} selected of {len(text_files)} files; "
        f"voice={args.voice}; overwrite={args.overwrite}"
    )

    if args.overwrite:
        for source in pending:
            (args.out_dir / source.relative_to(args.texts_dir)).unlink(missing_ok=True)

    failures: list[str] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        jobs = [
            executor.submit(
                synthesize,
                ffmpeg,
                source,
                args.out_dir / source.relative_to(args.texts_dir),
                args.voice,
            )
            for source in pending
        ]
        for index, future in enumerate(concurrent.futures.as_completed(jobs), start=1):
            _, error = future.result()
            if error:
                failures.append(error)
            if index % 50 == 0 or index == len(jobs):
                print(f"[progress] {index}/{len(jobs)} failures={len(failures)}")

    if failures:
        print(f"[FAILED] {len(failures)} files", file=sys.stderr)
        for failure in failures[:20]:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"[ok] generated {len(pending)} files under {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
