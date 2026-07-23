# Demo des MMS-Use-Case (Phase 5) -- PowerShell-Variante von windx-cx-demo.sh.
# Turbinen-Telemetrie aus dem iecsim, gezogen ueber den Tractus-X-EDC:
# Katalog -> Vertrag (Membership) -> Mms-PULL -> Live-JSON, 401-Gegenprobe.
#
#   .\windx-cx-demo.ps1          Vorfuehrung mit Pausen (Enter)
#   .\windx-cx-demo.ps1 -Auto    ohne Pausen (Selbsttest)
#
# Die API-Aufrufe laufen in einem Demo-Pod im Cluster (windx-cx-demo.py), weil die
# Management-APIs nur clusterintern erreichbar sind. Fachliche Details: siehe .sh/.py.
param([switch]$Auto)

$KCTX = if ($env:KCTX) { $env:KCTX } else { 'try1' }
$NS = if ($env:NS) { $env:NS } else { 'windx' }
$POD = 'windx-demo'
if (-not $env:KUBECONFIG -and (Test-Path 'C:\Users\luf\Desktop\wind-x\kubernetes\.kube')) {
    $env:KUBECONFIG = 'C:\Users\luf\Desktop\wind-x\kubernetes\.kube'
}

function Akt($n, $t) { Write-Host ""; Write-Host "==== AKT ${n}: $t ====" -ForegroundColor Cyan }
function Erz($s) { Write-Host $s -ForegroundColor DarkGray }
function Warte { if (-not $Auto) { Read-Host "`n[Enter fuer den naechsten Schritt]" | Out-Null } }
function Run($stufe) {
    kubectl --context $KCTX -n $NS exec $POD -- python3 /tmp/demo.py $stufe
    if ($LASTEXITCODE -ne 0) { Write-Host "Abbruch in Stufe '$stufe'" -ForegroundColor Red; exit 1 }
}

# ---------------------------------------------------------------- Vorbereitung
Write-Host "Wind-X Demonstrator -- IEC-61850-Telemetrie ueber den Tractus-X-EDC" -ForegroundColor Cyan
Erz "Cluster: $KCTX / Namespace: $NS"

kubectl --context $KCTX -n $NS get pod $POD *> $null
if ($LASTEXITCODE -ne 0) {
    Erz "Starte Demo-Pod ..."
    kubectl --context $KCTX -n $NS run $POD --image=python:3.12-alpine --restart=Never --command -- sleep 14400 | Out-Null
}
kubectl --context $KCTX -n $NS wait --for=condition=Ready "pod/$POD" --timeout=180s | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "Demo-Pod nicht bereit" -ForegroundColor Red; exit 1 }
# kubectl cp: RELATIVER Quellpfad, denn den Doppelpunkt in "C:\..." missversteht kubectl als
# Pod-Trenner ("one of src or dest must be a local file specification").
Push-Location $PSScriptRoot
kubectl --context $KCTX -n $NS cp "windx-cx-demo.py" "${POD}:/tmp/demo.py" | Out-Null
$cpOk = ($LASTEXITCODE -eq 0)
Pop-Location
if (-not $cpOk) { Write-Host "kubectl cp fehlgeschlagen -- Demo-Stufen wuerden mit altem Stand laufen" -ForegroundColor Red; exit 1 }

# iecsimweb lokal verfuegbar machen (Akt 1) -- nicht fatal, wenn Port 8080 schon belegt
$pf = $null
$belegt = $false
try {
    $tc = New-Object Net.Sockets.TcpClient
    $tc.Connect('127.0.0.1', 8080); $belegt = $true; $tc.Close()
} catch {}
if (-not $belegt) {
    $pf = Start-Process kubectl -ArgumentList "--context", $KCTX, "-n", "aas", "port-forward", "svc/iecsimweb", "8080:80" -WindowStyle Hidden -PassThru
}

try {
    # ---------------------------------------------------------------- Akt 1
    Akt 1 "DIE ANLAGE"
    Erz "Der iecsim simuliert eine Windenergieanlage nach IEC 61850: MMS-Protokoll auf TCP 102,"
    Erz "kein HTTP, keine partnerbezogene Autorisierung -- so sieht Anlagen-IT wirklich aus."
    Write-Host "  Live-Ansicht im Browser oeffnen:  " -NoNewline
    Write-Host "http://localhost:8080" -ForegroundColor Green
    Erz "  (Windgeschwindigkeit, Leistung, Status -- bitte offen lassen fuer Akt 4)"
    Warte

    # ---------------------------------------------------------------- Akt 2
    Akt 2 "DAS ANGEBOT"
    Erz "Der Consumer fragt den Katalog des Providers ab. Schon dieser Aufruf laeuft ueber den"
    Erz "vollen DCP-Handschlag: DID-Aufloesung, Credential-Praesentation, Token vom Wallet."
    Run katalog
    Erz "-> Die Anlage erscheint als Vertragsangebot mit Policy -- nicht als offener Port."
    Warte

    # ---------------------------------------------------------------- Akt 3
    Akt 3 "DER VERTRAG"
    Erz "Der Consumer verhandelt das Angebot. Der Provider prueft dabei das MembershipCredential"
    Erz "aus dem Wallet des Consumers -- das ist die 'echte Authentifizierung' des Auftrags."
    Run vertrag
    Erz "-> FINALIZED: rechtsfoermige Grundlage fuer den Datenzugriff."
    Warte

    # ---------------------------------------------------------------- Akt 4
    Akt 4 "DIE DATEN"
    Erz "Transfer 'Mms-PULL': die Provider-Data-Plane verbindet sich zum iecsim (Port 102),"
    Erz "pollt die Sensoren und uebersetzt IEC 61850 -> JSON. Der Consumer sieht nur HTTP."
    Run daten
    Write-Host ""
    Erz "Beweis im iecsim-Log (jeder MMS-Read wird mit Client-IP quittiert):"
    $zeile = kubectl --context $KCTX -n $NS get pod -o wide |
        Where-Object { $_ -match 'provider-tractusx-connector-dataplane' } | Select-Object -First 1
    $dpip = if ($zeile) { ($zeile -split '\s+')[5] } else { '?' }
    # gezielt nach der Data-Plane-IP filtern -- die iecsimweb-UI pollt permanent und wuerde das Log fluten
    kubectl --context $KCTX -n aas logs deploy/iecsim --since=2m |
        Where-Object { $_ -match [regex]::Escape("client=${dpip}:") } |
        Select-Object -Last 3 | ForEach-Object { "    $_" }
    Write-Host "  Data-Plane-Pod-IP: " -NoNewline
    Write-Host $dpip -ForegroundColor Green -NoNewline
    Write-Host " -- die Reads kommen vom EDC, nicht von der Web-UI."
    Erz "-> Werte mit der Browser-Ansicht (Akt 1) vergleichen: dieselbe Anlage, dieselben Zahlen."
    Warte

    # ---------------------------------------------------------------- Akt 5
    Akt 5 "DAS SCHLOSS"
    Erz "Gegenprobe: derselbe Endpunkt ohne Token. Und danach ein zweiter Abruf mit Token --"
    Erz "der neue Werte liefert, denn das ist eine lebende Anlage, kein aufgezeichnetes Demo."
    Run schloss

    # ---------------------------------------------------------------- Fazit
    Write-Host ""
    Write-Host "==== DEMO ABGESCHLOSSEN ====" -ForegroundColor Green
    Erz "Gezeigt: Sichtbarkeit nur im Dataspace-Katalog | Zugriff nur mit Membership-Credential |"
    Erz "Protokolluebersetzung IEC 61850 -> JSON | Token-Schutz (401) | Live-Daten."
    Erz "Aufraeumen (optional): kubectl --context $KCTX -n $NS delete pod $POD"
} finally {
    if ($pf -and -not $pf.HasExited) { Stop-Process -Id $pf.Id -Force -ErrorAction SilentlyContinue }
}
