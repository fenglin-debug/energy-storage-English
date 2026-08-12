param(
    [Parameter(Mandatory = $true)]
    [string]$TextsDir,
    [Parameter(Mandatory = $true)]
    [string]$OutDir
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech

$ffmpeg = Get-Command ffmpeg -ErrorAction Stop
$textsRoot = (Resolve-Path -LiteralPath $TextsDir).Path
$outputRoot = (Resolve-Path -LiteralPath $OutDir).Path
$files = Get-ChildItem -LiteralPath $textsRoot -Recurse -File -Filter "*.m4a"
$created = 0
$skipped = 0

foreach ($file in $files) {
    $relative = $file.FullName.Substring($textsRoot.Length).TrimStart('\', '/')
    $target = Join-Path $outputRoot $relative
    if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).Length -gt 0) {
        $skipped++
        continue
    }

    $targetDirectory = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
    $wave = [System.IO.Path]::ChangeExtension($target, ".tmp.wav")
    $text = (Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "Empty speech text: $($file.FullName)"
    }

    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice("Microsoft Zira Desktop")
        $synth.Rate = -1
        $synth.SetOutputToWaveFile($wave)
        $synth.Speak($text)
    }
    finally {
        $synth.Dispose()
    }

    & $ffmpeg.Source -hide_banner -loglevel error -y -i $wave `
        -ac 1 -ar 24000 -b:a 64k -c:a aac -f ipod $target
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $target)) {
        throw "ffmpeg failed for $relative"
    }
    Remove-Item -LiteralPath $wave
    $created++
    if ($created % 50 -eq 0) {
        Write-Host "[progress] created=$created skipped=$skipped"
    }
}

Write-Host "[ok] created=$created skipped=$skipped"
