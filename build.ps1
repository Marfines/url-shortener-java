param(
    [ValidateSet('verify', 'test', 'package', 'format')]
    [string]$Task = 'verify'
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $goal = if ($Task -eq 'format') { 'spotless:apply' } else { $Task }
    $mavenArgs = @('--batch-mode', '--no-transfer-progress', '-Dmaven.repo.local=.cache/m2', $goal)
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mavenCommand) {
        & $mavenCommand.Source @mavenArgs
    } else {
        $ideaRoot = Join-Path $env:ProgramFiles 'JetBrains'
        $bundledMaven = Get-ChildItem -LiteralPath $ideaRoot -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3' } |
            Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\m2.conf') } |
            Select-Object -First 1
        if (-not $bundledMaven) {
            throw 'Install Maven 3.9+ and JDK 17+, or use IntelliJ IDEA bundled Maven.'
        }
        & java "-Dmaven.home=$bundledMaven" "-Dclassworlds.conf=$bundledMaven\bin\m2.conf" `
            "-Dmaven.multiModuleProjectDirectory=$PSScriptRoot" -cp "$bundledMaven\boot\*" `
            org.codehaus.plexus.classworlds.launcher.Launcher @mavenArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
