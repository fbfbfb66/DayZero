<#
.SYNOPSIS
Installs the Debug APK without wiping user data.

.DESCRIPTION
This script safely builds and installs the DayZero debug APK using gradle.
It does not use `adb uninstall` or `pm clear`. It uses the standard
`:app:installDebug` task which preserves SharedPreferences and Room database.
#>

Write-Host "Building and installing DayZero Debug..." -ForegroundColor Cyan
Write-Host "This is a safe install that preserves local data (Room, SharedPreferences)."

./gradlew :app:installDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "Install successful. Launching MainActivity..." -ForegroundColor Green
    adb shell am start -n com.aistudio.dayzero.djwqop/com.example.MainActivity
} else {
    Write-Host "Install failed." -ForegroundColor Red
    exit 1
}