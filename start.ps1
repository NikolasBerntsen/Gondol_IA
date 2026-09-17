<#
.SYNOPSIS
    GondolIA - atajo para PowerShell: ejecuta start.sh con Git Bash.

.DESCRIPTION
    Acepta los mismos comandos y opciones que start.sh (start, stop [--borrar] [-y], restart,
    logs [servicio], status, reset [-y], db, help). Necesita Git para Windows (incluye Git Bash).

.EXAMPLE
    .\start.ps1
    .\start.ps1 logs backend
    .\start.ps1 stop --borrar -y

.NOTES
    Si PowerShell no permite ejecutar scripts, usá:
    powershell -ExecutionPolicy Bypass -File .\start.ps1
#>

$ErrorActionPreference = 'Stop'

function Find-GitBash {
    $candidates = New-Object System.Collections.Generic.List[string]

    foreach ($key in 'HKLM:\SOFTWARE\GitForWindows', 'HKCU:\SOFTWARE\GitForWindows', 'HKLM:\SOFTWARE\WOW6432Node\GitForWindows') {
        try {
            $installPath = (Get-ItemProperty -Path $key -Name InstallPath -ErrorAction Stop).InstallPath
            if ($installPath) { $candidates.Add((Join-Path $installPath 'bin\bash.exe')) }
        } catch { }
    }

    # git.exe suele estar en ...\Git\cmd, ...\Git\bin o ...\Git\mingw64\bin: se busca bin\bash.exe hacia arriba.
    $git = Get-Command git.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($git) {
        $dir = Split-Path -Parent $git.Source
        for ($i = 0; $i -lt 3 -and $dir; $i++) {
            $candidates.Add((Join-Path $dir 'bin\bash.exe'))
            $dir = Split-Path -Parent $dir
        }
    }

    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:ProgramW6432)) {
        if ($base) { $candidates.Add((Join-Path $base 'Git\bin\bash.exe')) }
    }
    if ($env:LOCALAPPDATA) { $candidates.Add((Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')) }
    if ($env:USERPROFILE) { $candidates.Add((Join-Path $env:USERPROFILE 'scoop\apps\git\current\bin\bash.exe')) }

    # Nunca C:\Windows\System32\bash.exe: ese es el de WSL, no Git Bash.
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

$scriptPath = Join-Path $PSScriptRoot 'start.sh'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    Write-Host "No encontré start.sh en $PSScriptRoot. ¿Clonaste el repositorio completo?" -ForegroundColor Red
    exit 1
}

$bash = Find-GitBash
if (-not $bash) {
    Write-Host 'No encontré Git Bash.' -ForegroundColor Red
    Write-Host '  Instalá Git para Windows (incluye Git Bash): https://git-scm.com/download/win'
    Write-Host '  Después volvé a ejecutar .\start.ps1, o abrí "Git Bash" en esta carpeta y ejecutá ./start.sh'
    exit 1
}

# start.sh escribe en UTF-8 (acentos y símbolos): la consola tiene que interpretarlo igual.
$previousOutputEncoding = $null
try {
    $previousOutputEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
} catch { }
$previousLang = $env:LANG
if (-not $env:LANG) { $env:LANG = 'C.UTF-8' }

$exitCode = 1
try {
    & $bash ($scriptPath -replace '\\', '/') @args
    $exitCode = $LASTEXITCODE
} finally {
    $env:LANG = $previousLang
    if ($previousOutputEncoding) {
        try { [Console]::OutputEncoding = $previousOutputEncoding } catch { }
    }
}
exit $exitCode
