# LumiOne — Font Download Script (Google Fonts API)
# Run from D:\ANTI\LumiOne in PowerShell
# Usage: .\setup_fonts.ps1

$ErrorActionPreference = "Continue"
$fontDir = "app\src\main\res\font"

Write-Host "=== LumiOne Font Downloader ===" -ForegroundColor Cyan

# Google Fonts CSS2 API URLs
$googleFontsBase = "https://fonts.gstatic.com/s"

$fonts = @(
    [PSCustomObject]@{ name="plus_jakarta_sans_regular.ttf";  url="$googleFontsBase/plusjakartasans/v8/LDIbaomQNQcsA88c7O9yZ4KMCoOg4Ko70yyygA.ttf" },
    [PSCustomObject]@{ name="plus_jakarta_sans_semibold.ttf"; url="$googleFontsBase/plusjakartasans/v8/LDIbaomQNQcsA88c7O9yZ4KMCoOg4Ko70yyy5A.ttf" },
    [PSCustomObject]@{ name="plus_jakarta_sans_bold.ttf";     url="$googleFontsBase/plusjakartasans/v8/LDIbaomQNQcsA88c7O9yZ4KMCoOg4Ko70yyy8A.ttf" },
    [PSCustomObject]@{ name="manrope_regular.ttf";            url="$googleFontsBase/manrope/v15/xn7gYHE41ni1AdIRggexSg.ttf" },
    [PSCustomObject]@{ name="manrope_medium.ttf";             url="$googleFontsBase/manrope/v15/xn7gYHE41ni1AdIRggqxSg.ttf" },
    [PSCustomObject]@{ name="manrope_bold.ttf";               url="$googleFontsBase/manrope/v15/xn7gYHE41ni1AdIRggSySg.ttf" }
)

$headers = @{ "User-Agent" = "Mozilla/5.0" }

foreach ($f in $fonts) {
    $dest = Join-Path $fontDir $f.name
    if (Test-Path $dest) {
        Write-Host "  [SKIP] $($f.name)" -ForegroundColor DarkGray
        continue
    }
    try {
        Write-Host "  [DL]   $($f.name)" -ForegroundColor Yellow
        Invoke-WebRequest -Uri $f.url -OutFile $dest -Headers $headers -UseBasicParsing -TimeoutSec 30
        $size = (Get-Item $dest).Length
        if ($size -lt 1000) {
            Remove-Item $dest -Force
            throw "File too small - likely an error page"
        }
        Write-Host "  [OK]   $($f.name) ($size bytes)" -ForegroundColor Green
    } catch {
        Write-Host "  [FAIL] $($f.name): $_" -ForegroundColor Red
        Write-Host "         Download manually: https://fonts.google.com" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "=== Font setup complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "NEXT STEPS:" -ForegroundColor White
Write-Host "  1. Open D:\ANTI\LumiOne in Android Studio (File -> Open)" -ForegroundColor Gray
Write-Host "  2. Wait for Gradle sync (bottom status bar)" -ForegroundColor Gray
Write-Host "  3. Connect Android device (API 26+) with USB Debugging ON" -ForegroundColor Gray
Write-Host "  4. Click Run or: .\gradlew installDebug" -ForegroundColor Gray
Write-Host ""
Write-Host "  Stitch UI Design (3 screens):" -ForegroundColor Cyan
Write-Host "  https://stitch.withgoogle.com/project/12646646274753514615" -ForegroundColor Cyan
