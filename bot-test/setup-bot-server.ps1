<#
Creates the isolated, localhost-only Paper server used by the bot test, next to the repository
(..\..\syncequip-bot-server). Offline mode is on in this server ON PURPOSE and it only listens on
127.0.0.1. Never copy these settings to a server other people can reach.

  powershell -File setup-bot-server.ps1 -AcceptEula
#>
param(
    [string]$Dir = (Join-Path $PSScriptRoot '..\..\syncequip-bot-server'),
    [switch]$AcceptEula
)
$ErrorActionPreference = 'Stop'
$jarName = 'paper-26.3-231.jar'
$jarUrl = 'https://fill-data.papermc.io/v1/objects/a836a016be7ac52352eb3a1efb6857bb42ee73a6c78d46e292f7e87742edb0e6/paper-26.3-231.jar'
$jarSha256 = 'a836a016be7ac52352eb3a1efb6857bb42ee73a6c78d46e292f7e87742edb0e6'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$plugin = Join-Path $repo 'build\libs\SyncEquip-1.0.0.jar'

if (-not (Test-Path $plugin)) { throw "Build the plugin first (gradlew build). Missing $plugin" }
if (-not $AcceptEula) { throw 'Pass -AcceptEula to confirm you accept the Minecraft EULA (https://aka.ms/MinecraftEULA).' }

New-Item -ItemType Directory -Force "$Dir\plugins\SyncEquip", "$Dir\world\datapacks" | Out-Null
$Dir = Resolve-Path $Dir

$jar = Join-Path $Dir $jarName
if (-not (Test-Path $jar)) {
    Write-Host "Downloading $jarName from papermc.io"
    Invoke-WebRequest $jarUrl -OutFile $jar
}
if ((Get-FileHash $jar -Algorithm SHA256).Hash.ToLower() -ne $jarSha256) { Remove-Item $jar; throw 'Paper jar checksum mismatch' }

Copy-Item $plugin "$Dir\plugins\" -Force
Copy-Item (Join-Path $repo 'testing\datapack\syncequip_test') "$Dir\world\datapacks\" -Recurse -Force
'eula=true' | Set-Content "$Dir\eula.txt"
"settings:`n  connection-throttle: -1" | Set-Content "$Dir\bukkit.yml"
"advancements:`n  disable-saving: true`n  disabled:`n  - '*'" | Set-Content "$Dirspigot.yml"
"debug:`n  log-sequence-ticks: true" | Set-Content "$Dir\plugins\SyncEquip\config.yml"

@'
# ISOLATED BOT TEST SERVER. Offline mode, localhost only. Never expose this publicly.
server-ip=127.0.0.1
server-port=25599
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
motd=SyncEquip bot test (localhost only)
max-players=12
gamemode=survival
force-gamemode=true
difficulty=easy
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
pvp=false
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-command-block=false
enable-rcon=false
enable-query=false
pause-when-empty-seconds=-1
'@ | Set-Content "$Dir\server.properties"

Write-Host "Bot server ready in $Dir"
