# Host-Launcher (PowerShell) fuer den Wind-X Demonstrator.
#
# Startet windx-demo.sh in einem curl-Pod im Namespace 'windx'. Der Ablauf MUSS cluster-intern
# laufen (der Stack hat kein Ingress). PowerShell-Aequivalent zu run-demo.sh.
#
# Aufruf (aus dem Repo-Root oder deployment/windx-kind/):
#   ./deployment/windx-kind/run-demo.ps1                 # volle Demo (Story + Technik)
#   ./deployment/windx-kind/run-demo.ps1 -StoryOnly      # nur die Story-Ebene (Nicht-Techniker)
#   ./deployment/windx-kind/run-demo.ps1 -Pause 4        # laengere Pausen zum Mitmoderieren
#   ./deployment/windx-kind/run-demo.ps1 -NoColor        # ohne ANSI-Farben
#
# Falls die Ausfuehrung geblockt wird ("... cannot be loaded because running scripts is disabled"):
#   powershell -ExecutionPolicy Bypass -File deployment/windx-kind/run-demo.ps1

[CmdletBinding()]
param(
    [int]$Pause,
    [switch]$StoryOnly,
    [switch]$NoColor
)

$ctx = if ($env:CTX) { $env:CTX } else { 'kind-mvd' }
$ns  = if ($env:NS)  { $env:NS }  else { 'windx' }
if (-not $env:KUBECONFIG) { $env:KUBECONFIG = 'C:/Users/luf/Desktop/wind-x/kubernetes/.kube' }
$dir = $PSScriptRoot

# --- Preflight ---------------------------------------------------------------
& kubectl --context $ctx -n $ns get deploy windx-provider windx-consumer windx-identityhub 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "FEHLER: windx-Stack nicht gefunden (Kontext $ctx, Namespace $ns)."
    Write-Host "        Erst 'kubectl apply -f $dir\windx-stack.yaml' (siehe README)."
    exit 1
}
& kubectl --context $ctx -n $ns get secret windx-sts-secrets 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "WARN: Secret 'windx-sts-secrets' fehlt -> Negotiation scheitert (README Schritt 3)." }
& kubectl --context $ctx -n $ns get secret windx-provider-vault-seed 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "WARN: Secret 'windx-provider-vault-seed' fehlt -> Akt 4 (Datentransfer) scheitert (README Schritt 4-6)." }

# --- Demo-Skript nach base64 (LF-normalisiert, eine Zeile ohne Umbrueche) -----
$scriptPath = Join-Path $dir 'windx-demo.sh'
$text  = [IO.File]::ReadAllText($scriptPath) -replace "`r`n", "`n" -replace "`r", "`n"
$b64   = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))

# --- Start -------------------------------------------------------------------
& kubectl --context $ctx -n $ns delete pod windx-demo --ignore-not-found 2>&1 | Out-Null

$envFlags = @()
if ($StoryOnly)                            { $envFlags += '--env=STORY_ONLY=1' }
if ($PSBoundParameters.ContainsKey('Pause')) { $envFlags += "--env=PAUSE=$Pause" }
if ($NoColor)                              { $envFlags += '--env=NO_COLOR=1' }

$kargs = @('--context', $ctx, '-n', $ns, 'run', 'windx-demo', '--rm', '-i', '--restart=Never',
           '--image=curlimages/curl:8.10.1')
$kargs += $envFlags
$kargs += @('--command', '--', 'sh', '-c', "echo $b64 | base64 -d | sh")

& kubectl @kargs
