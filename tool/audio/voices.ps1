# Renders raw TTS takes for the Reaction Speed voice pipeline.
#
#   powershell -ExecutionPolicy Bypass -File tool\audio\voices.ps1
#
# Reads voice_lines.json (UTF-8) and writes tool/audio/_raw_tts/<id>_<lang>.wav
# as 22050 Hz / 16-bit / mono PCM. These are RAW takes: voices_post.py turns
# them into the cartoon characters.
#
# Notes:
#  - the JSON is read with -Encoding UTF8 so Cyrillic/umlauts survive
#    (Windows PowerShell 5.1 would otherwise read this file as ANSI);
#  - SSML <prosody> is tried first for a more expressive take, with a plain
#    Speak() fallback for voices that reject it;
#  - the synthesizer output is closed before the file is inspected, otherwise
#    the RIFF header is not finalised.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $here '_raw_tts'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$json = Get-Content -Path (Join-Path $here 'voice_lines.json') -Encoding UTF8 -Raw
$cfg = $json | ConvertFrom-Json

$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    22050,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

function Escape-Xml([string]$s) {
    $s.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
}

$langs = $cfg.languages.PSObject.Properties
$count = 0
foreach ($langProp in $langs) {
    $lang = $langProp.Name
    $li = $langProp.Value

    foreach ($line in $cfg.lines) {
        $text = $line.text.$lang
        if (-not $text) { continue }
        $path = Join-Path $outDir ("{0}_{1}.wav" -f $line.id, $lang)

        $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
        try {
            $synth.SelectVoice($li.voice)
            $synth.Rate = [int]$line.rate
            $synth.Volume = 100
            $synth.SetOutputToWaveFile($path, $fmt)

            $esc = Escape-Xml $text
            $ssml = @"
<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="$($li.culture)">
<prosody pitch="$($line.pitch)" volume="loud"><emphasis level="$($line.emphasis)">$esc</emphasis></prosody>
</speak>
"@
            try {
                $synth.SpeakSsml($ssml)
            } catch {
                Write-Host ("  SSML rejected for {0}/{1}, plain speak" -f $line.id, $lang)
                $synth.Speak($text)
            }
        } finally {
            $synth.SetOutputToNull()
            $synth.Dispose()
        }

        $len = (Get-Item $path).Length
        Write-Host ("{0,-18} {1}  {2,8:N1} KB  `"{3}`"" -f $line.id, $lang, ($len / 1KB), $text)
        if ($len -lt 2000) { Write-Warning ("suspiciously small take: {0}" -f $path) }
        $count++
    }
}
Write-Host ("`n{0} raw takes written to {1}" -f $count, $outDir)
