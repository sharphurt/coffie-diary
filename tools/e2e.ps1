param(
  [string]$PgBin = "$PSScriptRoot/../.tools/pg/node_modules/@embedded-postgres/windows-x64/native/bin",
  [string]$JavaHome = "$PSScriptRoot/../.tools/msjdk/jdk-21.0.9+10",
  [string]$Maven = "$PSScriptRoot/../.tools/apache-maven-3.9.11/bin/mvn.cmd"
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = Join-Path $root ('.tools/e2e/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run -Force | Out-Null
$env:JAVA_HOME = (Resolve-Path $JavaHome).Path
$env:OWNER_PASSWORD = [guid]::NewGuid().ToString()
$env:OWNER_LOGIN = 'owner'
$env:POSTGRES_USER = 'postgres'
$env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString()
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55439/postgres'
$env:SERVER_PORT = '18089'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:COOKIE_SECURE = 'false'
$env:E2E_BASE_URL = 'http://127.0.0.1:18089'
$env:E2E_FRESH_DATABASE = 'true'
$env:SPRING_WEB_RESOURCES_STATIC_LOCATIONS = 'file:' + ($root.Replace('\','/') + '/frontend/dist/')
$javaProcess = $null
$pgStarted = $false
Push-Location $root
try {
  foreach ($port in @(55439, 18089)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Test port $port is already in use" }
  }
  & npm.cmd --prefix frontend run build
  if ($LASTEXITCODE) { throw 'Frontend build failed' }
  & $Maven -q -f backend/pom.xml package
  if ($LASTEXITCODE) { throw 'Backend build failed' }
  $passwordFile = Join-Path $run 'pg-password'
  [IO.File]::WriteAllText($passwordFile, $env:POSTGRES_PASSWORD)
  & "$PgBin/initdb.exe" -D "$run/db" -U postgres -A scram-sha-256 "--pwfile=$passwordFile" --encoding=UTF8 --locale=C | Out-Null
  if ($LASTEXITCODE) { throw 'initdb failed' }
  Remove-Item -LiteralPath $passwordFile
  $pgControl = Start-Process -FilePath "$PgBin/pg_ctl.exe" -ArgumentList "-D `"$run/db`" -l `"$run/postgres.log`" -o `"-p 55439 -h 127.0.0.1`" -w start" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/pg-start.log" -RedirectStandardError "$run/pg-start-error.log"
  $pgControl.WaitForExit()
  if ($pgControl.ExitCode) { throw 'PostgreSQL start failed' }
  $pgStarted = $true
  $javaProcess = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList '-jar', 'backend/target/coffee-diary-0.1.0.jar' -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/app.log" -RedirectStandardError "$run/app-error.log"
  $ready = $false
  for ($i = 0; $i -lt 90; $i++) {
    if ($javaProcess.HasExited) { throw "Application exited; see $run/app.log" }
    try {
      $response = Invoke-WebRequest "$env:E2E_BASE_URL/api/v1/auth/csrf" -TimeoutSec 2
      if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
  }
  if (!$ready) { throw "Application did not start; see $run/app.log" }
  & npm.cmd --prefix frontend run test:e2e
  if ($LASTEXITCODE) { throw "E2E tests failed; logs: $run" }
} finally {
  if ($javaProcess -and !$javaProcess.HasExited) { Stop-Process -Id $javaProcess.Id; $javaProcess.WaitForExit() }
  if ($pgStarted) { & "$PgBin/pg_ctl.exe" -D "$run/db" -m fast -w stop | Out-Null }
  Pop-Location
  Write-Host "Isolated database and logs retained at $run"
}


