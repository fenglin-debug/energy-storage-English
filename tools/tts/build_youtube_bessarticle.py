"""Build youtube-bess.bessarticle from 4 YouTube BESS audio files + translated SRT subtitles.

Pipeline:
1. Parse each English SRT file to extract cues (text + timestamps)
2. Apply Chinese translations (embedded in this script)
3. Convert MP3 -> M4A (AAC, mono, 24kHz, 64kbps) using ffmpeg
4. Build manifest.json + checksums.sha256 + audio files -> .bessarticle (zip)
"""

import hashlib
import argparse
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

DEFAULT_OUTPUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/articles/youtube-bess.bessarticle"
DEFAULT_WORK_DIR = Path(__file__).resolve().parents[2] / "build/youtube_bess_work"

# ---- Article metadata ----
ARTICLES = [
    {
        "id": "ART-YT-0001",
        "title": "Battery Energy Storage Systems (BESS)",
        "titleZh": "电池储能系统 (BESS)",
        "topic": "BESS 基础 - YouTube",
        "srt": "Battery Energy Storage Systems (BESS).srt",
        "mp3": "Battery Energy Storage Systems (BESS).mp3",
    },
    {
        "id": "ART-YT-0002",
        "title": "What is Battery Energy Storage Systems (Moxa)",
        "titleZh": "什么是电池储能系统 (Moxa)",
        "topic": "BESS 基础 - YouTube",
        "srt": "Decoding BESS_ What is Battery Energy Storage Systems _ Moxa.srt",
        "mp3": "Decoding BESS_ What is Battery Energy Storage Systems _ Moxa.mp3",
    },
    {
        "id": "ART-YT-0003",
        "title": "How to Optimize Your BESS Container's Reliability and Performance (Moxa)",
        "titleZh": "如何优化 BESS 集装箱的可靠性与性能 (Moxa)",
        "topic": "BESS 优化 - YouTube",
        "srt": "Decoding BESS_ How to Optimize Your BESS Container\u2019s Reliability and Performance _ Moxa.srt",
        "mp3": "Decoding BESS_ How to Optimize Your BESS Container\u2019s Reliability and Performance _ Moxa.mp3",
    },
    {
        "id": "ART-YT-0004",
        "title": "Building Cross-border Success with Communication, Cybersecurity, and Global Support (Moxa)",
        "titleZh": "以通信、网络安全和全球支持构建跨境成功 (Moxa)",
        "topic": "BESS 跨境 - YouTube",
        "srt": "Decoding BESS_ Building Cross-border Success with Communication, Cybersecurity, and Global Support.srt",
        "mp3": "Decoding BESS_ Building Cross-border Success with Communication, Cybersecurity, and Global Support.mp3",
    },
]

# ---- Chinese translations (one string per cue, in SRT order) ----
TRANSLATIONS = {
    "ART-YT-0001": [
        "电池储能系统，简称 BESS。",
        "电池储能是一种能够捕获并",
        "释放储存在电池中的能量的技术。",
        "这可以用于支持电网、提供备用电力，",
        "甚至储存太阳能和风能等可再生能源产生的电力。",
        "电池储能的好处很多，但",
        "最重要的一点是它能够",
        "帮助推动全球能源结构",
        "从化石燃料向更清洁的可再生能源转变。",
        "这不仅减少了我们对污染性化石燃料的依赖，",
        "而且还有助于",
        "通过提供可按需调度的电力来源",
        "来稳定电网。",
        "电池储能是一种将能量存储在",
        "电池中供后续使用的技术。",
        "这可以用来",
        "补充或替代传统的",
        "基于电网的发电和用电方式。",
        "电池储能系统可以小到便携式设备，",
        "大到公用事业级的大型系统。",
        "电池储能的好处包括：第一，",
        "减少对电网的依赖。通过将能量储存在电池中，",
        "你可以减少对电网的依赖。",
        "大型电池储能系统还可以",
        "通过提供备用电力并",
        "平滑需求波动来帮助稳定电网。",
        "这在停电或电网不可用时尤为重要。第二，",
        "提高可再生能源利用率。电池储能可以促进",
        "太阳能和风能等可再生能源的更大规模使用。",
        "第三，电池储能是一项快速发展的技术，",
        "具有许多潜在应用。",
        "它有潜力彻底改变我们",
        "发电和用电的方式。",
        "电池储能是如何工作的？",
        "电池储能背后的技术相对简单。",
        "电池以化学能的形式储存电能，",
        "可以在需要时转换回电能。",
        "当太阳能或风能等可再生能源发电时，",
        "电力往往是间歇性的，可能并不总是匹配需求。",
        "电池储能可以帮助",
        "通过在电力充裕时储存多余的电能，",
        "并在需求高峰时释放来平抑波动。",
        "电池有各种尺寸和化学类型，",
        "各有其优缺点。",
        "某些电池类型更适合",
        "快速释放大量电力，而",
        "其他类型则可以在更长时间内",
        "提供更稳定的电力供应。",
        "电池储能系统的关键组件包括电池本身、",
        "用于管理充放电的控制器，以及",
        "将储存的直流电转换为交流电的逆变器，",
        "供用户使用或并入电网。",
        "电池储能的优缺点。",
        "关于电池储能，需要考虑",
        "其优点和缺点。",
        "优点方面，电池储能可以帮助",
        "平抑电力需求的高峰和低谷。",
        "这可以带来更高效的",
        "电网利用和更低的电力成本。",
        "此外，电池储能可以在停电时",
        "提供备用电力。",
        "缺点方面，电池储能是一项相对较新的技术，",
        "因此成本较高。",
        "此外，电池的寿命有限，",
        "在达到使用期限后必须妥善处理。",
        "你可能会问自己，听起来不错，",
        "但对我有什么好处？",
        "通过将 BESS 与可再生能源（",
        "如风力发电机或光伏系统）结合使用，",
        "你可以通过储存所产出的电能",
        "在其他时间使用而获益。",
        "英国大多数企业每周仅工作五天。",
        "这可能意味着每周",
        "约 29% 的用电量被浪费。",
        "当然，你可以将电力卖回电网，但",
        "既然回购的成本很可能",
        "是售电价格的五到十倍，",
        "你为什么要这样做呢？",
        "每天都有 29% 的浪费。",
        "这些电力你本可以自己使用，",
        "这些钱你本可以用来发展业务。",
        "国家电网正在努力",
        "满足我们不断增长的需求，",
        "尤其是在电动汽车革命之后更加明显。",
        "甚至有人谈论停电和限制用电，",
        "如果你是订单满满的企业，这是令人担忧的。",
        "虽然电池储能不太可能完全解决停电问题，",
        "但它确实能在很大程度上",
        "在限电措施实施时减少你的停工时间。",
        "另外值得注意的是，如果你的土地",
        "（无论是农场还是工业用地）恰好",
        "安装了变电站，那么很有可能",
        "可以在其附近建造一个独立的电池储能系统，",
        "来帮助支持电网。",
        "虽然这个过程相当复杂且费用高昂，但你的土地",
        "有可能帮助为电网提供此类能源支持，",
        "因此你将获得丰厚的回报。",
        "是的，这类电池储能系统费用高昂，但",
        "这不意味着你不能从中受益。",
        "在 PowerHub，我们有",
        "准备投资的投资者。",
        "如果你有土地，其他一切我们都有。",
        "觉得这一切像个雷区？",
        "不必如此。",
        "当你决定与我们合作时，",
        "我们会打理一切。",
        "如何将这一切整合起来正是我们的专长。",
        "我们的 BESS 专家了解市场，",
        "熟悉能源供应商的语言，组织投资，",
        "并了解规划要求。",
        "事实上，我们从头到尾管理整个过程。",
        "我们在这个领域的信誉确保了快速、",
        "响应迅速且顺利的过渡。",
        "如果你正在考虑用电池储能系统来",
        "补充你的可再生能源，或只是想",
        "了解你的土地是否适合建造 BESS，",
        "请点击视频下方的按钮或链接，",
        "PowerHub 的专家",
        "将与您联系，",
        "进行一次非正式的交流。",
    ],
    "ART-YT-0002": [
        "欢迎来到电池储能系统，即 BESS 的世界。",
        "在追求可持续未来的道路上，",
        "BESS 在革命性地改变我们储存和使用能源的方式中发挥着关键作用。",
        "BESS 与风能和太阳能等可再生能源无缝集成，",
        "减少波动性并确保可靠调度。",
        "它储存多余的能量并在短缺时释放。",
        "电网的储能系统",
        "大致可分为两类。",
        "电网连接服务，通常称为表前（FTM），以及",
        "以客户为中心的能源应用，",
        "主要称为表后（BTM）。",
        "在表前领域，涵盖发电、",
        "输电和配电，BESS 发挥着至关重要的作用。",
        "它提供关键服务，包括灵活性、频率响应和",
        "能量时移，确保电网的稳定性和可靠性，",
        "特别是在需求高峰期间。",
        "在住宅、商业和工业领域，BESS 提供备用电力、",
        "自用电管理和分时电价账单管理，优化能源使用并",
        "增强微电网的",
        "稳定性和可靠性。",
        "现在，让我们来探索",
        "构成这项创新技术的核心组件。",
        "首先是电池管理系统，也称为 BMS。",
        "BESS 的核心组件由高能量密度电池组成。",
        "BMS 作为智能守护者，确保最佳性能和安全性。",
        "它精密地监控和",
        "管理各个电芯，",
        "负责均衡、",
        "温度调节和故障检测等任务。",
        "除了高能量密度电池外，还有重要的辅助系统。",
        "在 BESS 内部，复杂的传感器",
        "和监控设备网络时刻",
        "监控电池健康状态、",
        "温度和整体性能。",
        "这种精密的监督确保了",
        "最佳运行和使用寿命。",
        "集成的先进冷却系统",
        "经过精密设计，用于",
        "调节电池温度，",
        "保证安全性和最佳性能。",
        "强大的消防系统",
        "维护安全的环境，",
        "将安全置于一切之上。",
        "接下来是功率转换系统，",
        "也称为 PCS。",
        "PCS 主要负责",
        "促进交直流转换，并",
        "高效管理 BESS 的能量流入和流出。",
        "接下来是能量管理系统，即 EMS。",
        "EMS 确保 BESS 的安全",
        "和高效运行，",
        "使其能够提供",
        "所需的服务。",
        "它优化充放电循环，",
        "利用 BESS、PCS 和 EMS",
        "的数据实现峰值效率。",
        "所有这些元素协同工作，",
        "将 BESS 转变为一种多功能、",
        "可靠且可持续的",
        "储能解决方案。",
        "随着我们迈向更可持续的未来，BESS 将继续",
        "在重塑我们的能源格局和",
        "通过顺畅的数据流引领数字化转型",
        "方面发挥关键作用。",
        "这一承诺确保了为子孙后代",
        "提供更清洁、更可靠和",
        "更有韧性的能源未来。",
    ],
    "ART-YT-0003": [
        "欢迎来到电池储能系统，即 BESS 的世界。",
        "在 BESS 领域，性能和可靠性至关重要，而不仅仅是目标。",
        "以集装箱为焦点，",
        "BESS 供应商面临着优化电池性能的挑战，",
        "预判未来需求，并降低保修成本。",
        "这段旅程充满了障碍，例如需要",
        "通信设备方面的专业指导，以及寻找",
        "可靠且耐用的设备。",
        "这一点尤为重要，因为这些系统通常部署在",
        "偏远或恶劣的环境中。",
        "这正是我们的用武之地。",
        "我们的专业在于掌握 OT 数据，提供经过",
        "严格测试的可靠解决方案，以实现最佳的数据",
        "采集、传输和处理。",
        "让我们来看看它是如何工作的。",
        "在数据采集方面，Moxa 的 NPort、MGate 和",
        "I/O 模块在连接 BMS 或辅助系统中的",
        "串行信号方面发挥着关键作用，确保精确的数据采集和无缝的系统集成。",
        "在数据处理方面，Moxa 的工业计算机是收集和",
        "分析关键数据的基石，如荷电状态 (SoC)、健康状态 (SoH)、",
        "温度、电压和电流等来自电池架和辅助系统的数据。",
        "在数据传输方面，我们的交换机充当骨干网络，",
        "协调流向 EMS 和 PCS 的数据流，",
        "促进顺畅的数据通信并确保强大的网络安全。",
        "此外，我们的服务",
        "超越了产品本身，还融入了可靠性和专业知识。",
        "我们的产品坚固耐用，能够承受最恶劣的条件，",
        "从振动和冲击到极端温度。",
        "它们让您安心，免受意外的",
        "维护成本和停机时间的影响。",
        "在长寿命方面，没有人能与我们匹敌。",
        "我们保证在超过 100 个国家提供持续供应和服务。",
        "在您的 BESS 项目的建设和前 20 年里，",
        "我们始终作为您的合作伙伴，简化流程，",
        "实现顺畅的网络连接，并提供可靠的供应和服务。",
        "Moxa 的通信和网络解决方案赋能每个 BESS 集装箱",
        "通过确保可靠、高效和",
        "可持续的数据管理来实现最佳性能。",
        "Moxa — 为您的储能赋能。",
    ],
    "ART-YT-0004": [
        "欢迎来到电池储能系统，也称为 BESS 的世界。",
        "随着全球能源市场转型加速，",
        "BESS 正迅速成为能源管理的关键组成部分。",
        "全球储能市场正在快速扩张，越来越多",
        "的系统集成商争夺全球合同，",
        "以保持竞争优势。",
        "电力系统集成商旨在高效部署 BESS，",
        "以确保更高的稳定性和可靠性，",
        "同时最大化运营效率。",
        "然而，国际 BESS 项目",
        "通常面临诸多挑战，",
        "包括全球供应链中断和技术支持问题。",
        "克服这些挑战的关键是找到合适的合作伙伴。",
        "Moxa 拥有一支",
        "经验丰富的专业团队，",
        "是一站式通信解决方案提供商，也是",
        "设计高效稳定的 BESS 网络的专家顾问。",
        "BESS 站点需要可靠的工业",
        "通信设备来收集、",
        "传输和处理 OT 数据。",
        "随着电网侧储能设施规模的增大，",
        "其复杂性也随之增加，需要",
        "全面的数字网络",
        "来应对这些挑战。",
        "Moxa 专利的 Turbo Ring 技术",
        "采用高可靠性的冗余设计，",
        "可在 50 毫秒内自动恢复网络连接。",
        "此外，我们高度灵活的",
        "Turbo Chain 技术可以满足",
        "大型系统的连接和应用需求。",
        "在数字环境中，",
        "网络安全至关重要。",
        "BESS 系统容易受到",
        "未受监管的远程访问、",
        "非恶意的内部威胁和过时设备的影响，这些",
        "可能中断网络并",
        "危及电网稳定性。",
        "Moxa 是全球首家",
        "获得 IEC 62443 认证的工业网络",
        "设备供应商，重申我们对",
        "最高安全标准的承诺。",
        "我们提供安全设备的选择、",
        "网络状态识别和保护，",
        "以及全面的网络安全管理解决方案，",
        "确保 BESS 的持续运行",
        "同时将风险降至最低。",
        "全球项目通常涉及",
        "来自多个国家的供应商和合作伙伴，",
        "参与设计和部署，",
        "因此即时全球支持",
        "对项目成功至关重要。",
        "Moxa 在超过 100 个国家的广泛布局，",
        "包括经销商和子公司，",
        "能够灵活调配资源，",
        "使我们能够提供及时的设备和技术支持。",
        "我们与全球众多领先",
        "储能公司的合作帮助我们",
        "获得了丰富的知识和全球合作经验。",
        "选择 Moxa，享受无缝数字化",
        "和部署、全球支持、",
        "稳定运营和",
        "有效的维护，",
        "助力您的跨境 BESS 项目。",
        "Moxa，为您的储能赋能。",
    ],
}


def parse_srt(path: Path) -> list[tuple[int, int, str]]:
    """Parse SRT file -> list of (startMs, endMs, englishText)."""
    text = path.read_text(encoding="utf-8-sig")
    blocks = re.split(r"\n\s*\n", text.strip())
    cues = []
    for block in blocks:
        lines = [l.strip() for l in block.strip().splitlines() if l.strip()]
        timing_idx = next((i for i, l in enumerate(lines) if "-->" in l), None)
        if timing_idx is None:
            continue
        timing = lines[timing_idx].split("-->")
        start = parse_timestamp(timing[0].strip())
        end = parse_timestamp(timing[1].strip().split()[0])
        english = " ".join(lines[timing_idx + 1:])
        cues.append((start, end, english))
    return cues


def parse_timestamp(ts: str) -> int:
    m = re.match(r"(?:(\d+):)?(\d+):(\d{2})[,.](\d{1,3})", ts)
    h = int(m.group(1) or 0)
    mn = int(m.group(2))
    s = int(m.group(3))
    ms_val = m.group(4)
    ms = int(ms_val.ljust(3, "0"))
    return h * 3600000 + mn * 60000 + s * 1000 + ms


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def convert_audio(mp3_path: Path, m4a_path: Path, ffmpeg: str, ffprobe: str) -> int:
    """Convert MP3 -> M4A (AAC, mono, 24kHz, 64kbps). Returns duration_ms."""
    m4a_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = m4a_path.with_suffix(".tmp.m4a")
    proc = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(mp3_path),
         "-ac", "1", "-ar", "24000", "-b:a", "64k",
         "-c:a", "aac", "-f", "ipod", str(tmp)],
        capture_output=True,
    )
    if proc.returncode != 0 or not tmp.is_file():
        raise RuntimeError(f"ffmpeg failed: {proc.stderr.decode(errors='replace')[:300]}")
    tmp.replace(m4a_path)
    # Get duration
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(m4a_path)],
        capture_output=True, text=True,
    )
    return round(float(proc.stdout.strip()) * 1000)


def build_pack(src_dir: Path, out_path: Path, work_dir: Path, ffmpeg: str):
    ffprobe = str(Path(ffmpeg).with_name("ffprobe.exe" if Path(ffmpeg).suffix else "ffprobe"))
    if not Path(ffprobe).is_file():
        discovered = shutil.which("ffprobe")
        if not discovered:
            raise FileNotFoundError("ffprobe was not found next to ffmpeg or on PATH")
        ffprobe = discovered
    work_dir.mkdir(parents=True, exist_ok=True)
    manifest_articles = []
    files = {}

    for art in ARTICLES:
        art_id = art["id"]
        print(f"Processing {art_id}: {art['title']}")

        # Parse SRT
        srt_path = src_dir / art["srt"]
        cues = parse_srt(srt_path)
        print(f"  Parsed {len(cues)} cues from SRT")

        # Get translations
        translations = TRANSLATIONS.get(art_id, [])
        if len(translations) < len(cues):
            print(f"  WARNING: {len(translations)} translations for {len(cues)} cues, padding with empty")
            translations += [""] * (len(cues) - len(translations))

        # Convert audio
        m4a_path = work_dir / f"audio/{art_id}.m4a"
        if not m4a_path.is_file():
            mp3_path = src_dir / art["mp3"]
            duration_ms = convert_audio(mp3_path, m4a_path, ffmpeg, ffprobe)
            print(f"  Converted audio: {duration_ms} ms")
        else:
            # Get duration from existing file
            proc = subprocess.run(
                [ffprobe, "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=noprint_wrappers=1:nokey=1", str(m4a_path)],
                capture_output=True, text=True,
            )
            duration_ms = round(float(proc.stdout.strip()) * 1000)
            print(f"  Reusing existing audio: {duration_ms} ms")

        # Build paragraphs
        paragraphs = []
        for i, (start, end, english) in enumerate(cues):
            paragraphs.append({
                "textEn": english,
                "textZh": translations[i],
                "startMs": start,
                "endMs": end,
            })

        # Build content hash
        article_content = json.dumps(
            {"title": art["title"], "paragraphs": paragraphs},
            ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")

        audio_file = f"audio/{art_id}.m4a"
        manifest_articles.append({
            "id": art_id,
            "title": art["title"],
            "titleZh": art["titleZh"],
            "topic": art["topic"],
            "paragraphs": paragraphs,
            "audioFile": audio_file,
            "durationMs": duration_ms,
            "contentHash": sha256_hex(article_content),
            "contentScope": "BESS",
        })
        files[audio_file] = m4a_path.read_bytes()

    # Build manifest
    import datetime as dt
    manifest = {
        "schemaVersion": 2,
        "packageId": "bess-article",
        "contentVersion": "2026.08.08.youtube-bess-v1",
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "articles": manifest_articles,
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    files["manifest.json"] = manifest_bytes

    # Build checksums
    checksum_lines = []
    for name in sorted(files.keys()):
        checksum_lines.append(f"{sha256_hex(files[name])}  {name}")
    files["checksums.sha256"] = ("\n".join(checksum_lines) + "\n").encode("utf-8")

    # Write .bessarticle
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)

    print(f"\nBuilt: {out_path}")
    print(f"Articles: {len(manifest_articles)}")
    print(f"Size: {out_path.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Directory containing 4 MP3/SRT pairs")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"), help="ffmpeg executable path")
    args = parser.parse_args()
    if not args.ffmpeg:
        parser.error("ffmpeg was not found on PATH; pass --ffmpeg explicitly")
    build_pack(args.source, args.output, args.work_dir, args.ffmpeg)
