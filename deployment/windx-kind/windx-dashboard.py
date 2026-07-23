#!/usr/bin/env python3
"""Wind-X Connector -- Web-Dashboard (Demonstrator 2).

Visualisiert denselben Fluss wie windx-demo.sh (Katalog -> Vertrag -> Tor) als Browser-Seite:
vier Stufen, die live gruen aufleuchten, mit dem 200/401-Tor-Kontrast als Hoehepunkt.

Da der Cluster kein Ingress hat (und der Browser CORS-blockiert), oeffnet dieses Skript selbst
drei kubectl-port-forwards (Consumer-Mgmt 28181, Provider-Mgmt 18181, Provider-Public 19293),
faehrt den Ablauf host-seitig und streamt jeden Schritt per Server-Sent-Events an die Seite.

Nur Python-Standardbibliothek -- kein pip noetig.

Start:
    python deployment/windx-kind/windx-dashboard.py
    # oeffnet http://localhost:8088 im Browser; Strg+C beendet (raeumt die Port-Forwards auf).

Voraussetzung wie bei windx-seed.sh/transfer.sh: Stack deployt, STS-Secrets + EDR-Keys gesetzt
(siehe README). Ohne EDR-Keys laufen Stufe 1-3, Stufe 4 bricht vor dem Datenabruf sauber ab.

UI-Vorschau ohne Cluster: http://localhost:8088/?preview spielt den Ablauf mit Beispieldaten ab.
"""
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CTX = os.environ.get("CTX", "kind-mvd")
NS = os.environ.get("NS", "windx")
os.environ.setdefault("KUBECONFIG", "C:/Users/luf/Desktop/wind-x/kubernetes/.kube")
DASH_PORT = int(os.environ.get("DASH_PORT", "8088"))

# --- Cluster-interne Konstanten (identisch zu windx-demo.sh) -----------------
PROVIDER_DID = "did:web:windx-identityhub:provider"
DSP = "http://windx-provider:18182/protocol/2025-1"      # vom Consumer intern aufgeloest
PROTO = "dataspace-protocol-http:2025-1"
# Host-seitige Basen (ueber die port-forwards):
PROV = "http://localhost:18181/management/v3"
CONS = "http://localhost:28181/management/v3"

_procs = []


# ============================================================================
# HTTP-Helfer
# ============================================================================
def req(method, url, json_body=None, api_key=False, token=None, timeout=30):
    headers = {}
    data = None
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["X-Api-Key"] = "password"
    if token:
        headers["Authorization"] = token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def find(pattern, text):
    m = re.search(pattern, text)
    return m.group(1) if m else None


# ============================================================================
# Der Ablauf -- ruft emit(**event) fuer jedes Ereignis
# ============================================================================
def run_flow(emit):
    # -- Stufe 1: Provider seedet Asset + Policy + ContractDefinition ---------
    emit(kind="stage", id="provide", state="run",
         title="Der Provider stellt Daten bereit", detail="Asset, Policy und Angebot anlegen ...")
    asset = {"@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@id": "windx-asset-1",
             "properties": {"description": "Wind-X DID test asset"},
             "dataAddress": {"@type": "DataAddress", "type": "HttpData",
                             "baseUrl": "http://windx-identityhub:8181/api/check/health"}}
    policy = {"@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@id": "windx-policy-allow",
              "policy": {"@context": "http://www.w3.org/ns/odrl.jsonld", "@type": "Set",
                         "permission": [], "prohibition": [], "obligation": []}}
    cd = {"@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@id": "windx-cd-1",
          "accessPolicyId": "windx-policy-allow", "contractPolicyId": "windx-policy-allow",
          "assetsSelector": []}
    ca, _ = req("POST", PROV + "/assets", asset, api_key=True)
    cp, _ = req("POST", PROV + "/policydefinitions", policy, api_key=True)
    cc, _ = req("POST", PROV + "/contractdefinitions", cd, api_key=True)
    emit(kind="sub", id="provide", text=f"POST /assets -> {ca} · /policydefinitions -> {cp} · /contractdefinitions -> {cc}  (409 = existiert schon, ok)")
    emit(kind="stage", id="provide", state="ok",
         title="Der Provider stellt Daten bereit", detail="'windx-asset-1' ist online, Policy permissiv.")

    # -- Stufe 2: Katalog (Identitaet per DID) -------------------------------
    emit(kind="stage", id="catalog", state="run",
         title="Identität & Katalog", detail="Consumer fragt: Was bietest du an?")
    catreq = {"@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@type": "CatalogRequest",
              "counterPartyAddress": DSP, "counterPartyId": PROVIDER_DID, "protocol": PROTO,
              "querySpec": {"offset": 0, "limit": 10}}
    sc, cat = req("POST", CONS + "/catalog/request", catreq, api_key=True)
    offer = find(r'hasPolicy"\s*:\s*\[\s*\{\s*"@id"\s*:\s*"([^"]+)"', cat)
    if not offer:
        emit(kind="stage", id="catalog", state="fail",
             title="Identität & Katalog", detail=f"Keine Offer-ID (HTTP {sc}) -- Identitätskette prüfen (STS-Secrets?).")
        emit(kind="sub", id="catalog", text=cat[:400])
        raise RuntimeError("no offer")
    types = [t for t in ("Mms-PULL", "MediatorHttpData-PULL", "MmsLight-PULL", "HttpData-PUSH") if t in cat]
    emit(kind="types", list=types)
    emit(kind="sub", id="catalog", text=f"counterPartyId={PROVIDER_DID} · offerId={offer}")
    emit(kind="stage", id="catalog", state="ok",
         title="Identität & Katalog", detail="Kryptografisches Vertrauen per DID steht -- der Katalog kam an.")

    # -- Stufe 3: Vertrag aushandeln -----------------------------------------
    emit(kind="stage", id="contract", state="run",
         title="Der Vertrag wird ausgehandelt", detail="angefragt → vereinbart → besiegelt ...")
    edrreq = {"@context": ["http://www.w3.org/ns/odrl.jsonld", {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}],
              "@type": "ContractRequest", "counterPartyAddress": DSP, "protocol": PROTO,
              "policy": {"@id": offer, "@type": "Offer", "assigner": PROVIDER_DID, "target": "windx-asset-1",
                         "permission": [], "prohibition": [], "obligation": []},
              "callbackAddresses": []}
    _, edr = req("POST", CONS + "/edrs", edrreq, api_key=True)
    nid = find(r'"@id"\s*:\s*"([^"]+)"', edr)
    if not nid:
        emit(kind="stage", id="contract", state="fail", title="Der Vertrag wird ausgehandelt",
             detail="Keine negotiationId erhalten.")
        raise RuntimeError("no negotiationId")

    tpid, last, agid = None, None, None
    for _ in range(25):
        _, s = req("GET", CONS + f"/contractnegotiations/{nid}", api_key=True)
        state = find(r'"state"\s*:\s*"([^"]+)"', s)
        if state and state != last:
            label = {"INITIAL": "initialisiert (INITIAL)",
                     "REQUESTED": "angefragt (REQUESTED)", "OFFERED": "angeboten (OFFERED)",
                     "ACCEPTED": "akzeptiert (ACCEPTED)", "AGREED": "vereinbart (AGREED)",
                     "VERIFIED": "geprüft (VERIFIED)", "FINALIZED": "besiegelt (FINALIZED)"}.get(state, state)
            emit(kind="sub", id="contract", text=f"Vertragsstatus: {label}")
            last = state
        if state in ("TERMINATED", "ERROR"):
            emit(kind="stage", id="contract", state="fail", title="Der Vertrag wird ausgehandelt",
                 detail=f"Verhandlung {state}.")
            raise RuntimeError(state)
        if state == "FINALIZED":
            agid = find(r'"contractAgreementId"\s*:\s*"([^"]+)"', s)
        qspec = {"@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@type": "QuerySpec",
                 "filterExpression": [{"operandLeft": "contractNegotiationId", "operator": "=", "operandRight": nid}]}
        _, rr = req("POST", CONS + "/edrs/request", qspec, api_key=True)
        tpid = find(r'"transferProcessId"\s*:\s*"([^"]+)"', rr)
        if state == "FINALIZED" and tpid:
            break
        time.sleep(3)

    if last != "FINALIZED" or not tpid:
        emit(kind="stage", id="contract", state="fail", title="Der Vertrag wird ausgehandelt",
             detail="Kein FINALIZED im Zeitfenster.")
        raise RuntimeError("not finalized")
    emit(kind="sub", id="contract", text=f"agreementId={agid} · transferProcessId={tpid}")
    emit(kind="stage", id="contract", state="ok", title="Der Vertrag wird ausgehandelt",
         detail="Vertrag besiegelt -- es existiert ein signiertes Agreement.")

    # -- Stufe 4: Das Tor -----------------------------------------------------
    emit(kind="stage", id="gate", state="run", title="Das Tor",
         detail="Dieselbe Adresse -- einmal mit, einmal ohne Vertrag.")
    _, da = req("GET", CONS + f"/edrs/{tpid}/dataaddress?auto_refresh=true", api_key=True)
    endpoint = find(r'"endpoint"\s*:\s*"([^"]+)"', da)
    token = find(r'"authorization"\s*:\s*"([^"]+)"', da)
    if not endpoint:
        emit(kind="stage", id="gate", state="fail", title="Das Tor",
             detail="Kein Endpoint/Token -- Provider hat vermutlich keine echten EDR-Signing-Keys (README Schritt 4-6).")
        raise RuntimeError("no endpoint")
    local_ep = endpoint.replace("windx-provider:19293", "localhost:19293")
    emit(kind="sub", id="gate", text=f"GET /edrs/{tpid}/dataaddress -> endpoint={endpoint}")

    code_with, body_with = req("GET", local_ep, token=token)
    emit(kind="gate", which="with", code=code_with, body=body_with.strip())
    code_without, _ = req("GET", local_ep)
    emit(kind="gate", which="without", code=code_without, body="")

    ok = code_with == 200 and code_without == 401
    emit(kind="stage", id="gate", state="ok" if ok else "fail", title="Das Tor",
         detail=("Der Vertrag IST das Tor: mit Vertrag fließen die Daten, ohne bleibt die Tür zu."
                 if ok else f"Unerwartet: mit={code_with}, ohne={code_without}."))


# ============================================================================
# HTTP-Server (statische Seite + SSE-Endpoint)
# ============================================================================
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/" or path.startswith("/index"):
            body = HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/run":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

            def emit(**payload):
                try:
                    self.wfile.write(f"data: {json.dumps(payload)}\n\n".encode())
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    raise SystemExit
            try:
                run_flow(emit)
            except SystemExit:
                return
            except Exception as e:  # noqa: BLE001 -- Demo: Fehler als Event zeigen
                try:
                    emit(kind="error", message=str(e))
                except SystemExit:
                    return
            try:
                emit(kind="done")
            except SystemExit:
                pass
            if os.environ.get("ONESHOT"):
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        self.send_response(404)
        self.end_headers()


# ============================================================================
# Port-Forwards
# ============================================================================
def port_open(port):
    with socket.socket() as s:
        s.settimeout(0.6)
        try:
            s.connect(("127.0.0.1", port))
            return True
        except OSError:
            return False


def wait_port(port, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        if port_open(port):
            return True
        time.sleep(0.4)
    return False


def spawn_pf(*mappings):
    _procs.append(subprocess.Popen(
        ["kubectl", "--context", CTX, "-n", NS, "port-forward", *mappings],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))


def preflight():
    r = subprocess.run(["kubectl", "--context", CTX, "-n", NS, "get", "deploy",
                        "windx-provider", "windx-consumer", "windx-identityhub"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(f"FEHLER: windx-Stack nicht gefunden (Kontext {CTX}, Namespace {NS}).")
        print("        Erst 'kubectl apply -f windx-stack.yaml' (siehe README).")
        sys.exit(1)
    for sec, hint in (("windx-sts-secrets", "Negotiation scheitert (README Schritt 3)"),
                      ("windx-provider-vault-seed", "Stufe 4 scheitert (README Schritt 4-6)")):
        c = subprocess.run(["kubectl", "--context", CTX, "-n", NS, "get", "secret", sec],
                           capture_output=True, text=True)
        if c.returncode != 0:
            print(f"WARN: Secret '{sec}' fehlt -> {hint}.")


def ensure_forwards():
    if not port_open(28181):
        spawn_pf("svc/windx-consumer", "28181:28181")
    if not (port_open(18181) and port_open(19293)):
        spawn_pf("svc/windx-provider", "18181:18181", "19293:19293")
    for p in (28181, 18181, 19293):
        if not wait_port(p):
            print(f"FEHLER: Port {p} kam nicht hoch. Läuft der Stack? "
                  f"Ggf. manuell: kubectl -n {NS} port-forward svc/... {p}:{p}")
            cleanup()
            sys.exit(1)


def cleanup():
    for p in _procs:
        try:
            p.terminate()
        except Exception:
            pass


def main():
    preflight()
    print("Öffne Port-Forwards (Consumer 28181, Provider 18181+19293) ...")
    ensure_forwards()
    httpd = ThreadingHTTPServer(("127.0.0.1", DASH_PORT), Handler)
    url = f"http://localhost:{DASH_PORT}"
    print(f"Dashboard läuft: {url}   (Strg+C beendet)")
    if not os.environ.get("NO_BROWSER"):
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nBeende ...")
    finally:
        httpd.server_close()
        cleanup()


# ============================================================================
# Eingebettete Dashboard-Seite
# ============================================================================
HTML = r"""<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Wind-X · Souveräner Datenaustausch</title>
<style>
  /* Tokens: validierte Dark-Palette (dataviz reference; Akzent+Status geprüft auf #1a1a19) */
  :root{
    --page:#0d0d0d; --surface:#1a1a19; --raised:#222220; --sunken:#141413;
    --ink:#ffffff; --ink2:#c3c2b7; --muted:#898781; --line:#2c2c2a; --ring:rgba(255,255,255,.10);
    --accent:#3987e5; --good:#0ca30c; --crit:#d03b3b;
    --mono:ui-monospace,Menlo,Consolas,monospace;
  }
  *{box-sizing:border-box}
  html{color-scheme:dark}
  body{margin:0;background:var(--page);color:var(--ink);
    font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;
    -webkit-font-smoothing:antialiased}
  .wrap{max-width:880px;margin:0 auto;padding:30px 22px 70px}

  /* ---------- Header ---------- */
  header{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
  .turbine{flex:none}
  .rotor{transform-origin:32px 26px;animation:spin 9s linear infinite}
  body.running .rotor{animation-duration:1.4s}
  @keyframes spin{to{transform:rotate(360deg)}}
  .brand{font-size:21px;font-weight:700;letter-spacing:.2px}
  .brand small{display:block;font-size:12.5px;font-weight:400;color:var(--muted);letter-spacing:.4px}
  .controls{margin-left:auto;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
  .toggle{display:flex;align-items:center;gap:7px;color:var(--ink2);font-size:13px;cursor:pointer;user-select:none}
  .toggle input{accent-color:var(--accent)}
  .pill{font-size:12px;color:var(--ink2);border:1px solid var(--line);border-radius:20px;
    padding:4px 12px;display:flex;align-items:center;gap:7px;font-variant-numeric:tabular-nums}
  .pill .dot{width:7px;height:7px;border-radius:50%;background:var(--muted)}
  .pill.run .dot{background:var(--accent);animation:blink 1.1s ease-in-out infinite}
  .pill.ok  .dot{background:var(--good)}
  .pill.err .dot{background:var(--crit)}
  @keyframes blink{50%{opacity:.25}}
  button#start{background:var(--accent);color:#fff;border:0;border-radius:9px;
    padding:10px 20px;font-weight:650;font-size:14px;cursor:pointer;
    box-shadow:0 1px 0 rgba(255,255,255,.12) inset, 0 6px 18px rgba(57,135,229,.25);
    transition:transform .12s ease, box-shadow .2s}
  button#start:hover:not(:disabled){transform:translateY(-1px);box-shadow:0 1px 0 rgba(255,255,255,.12) inset, 0 9px 22px rgba(57,135,229,.32)}
  button#start:disabled{opacity:.45;cursor:default;box-shadow:none}
  button#start:focus-visible{outline:2px solid var(--ink);outline-offset:2px}
  .lede{color:var(--ink2);font-size:14.5px;margin:12px 0 24px;max-width:60ch}

  /* ---------- Szene: Consumer — Pipe — Provider ---------- */
  .scene{background:var(--surface);border:1px solid var(--ring);border-radius:14px;
    padding:18px 22px 20px;margin-bottom:28px}
  .ih{display:flex;flex-direction:column;align-items:center;margin-bottom:2px}
  .ih-node{display:flex;align-items:center;gap:8px;font-size:12.5px;color:var(--ink2);
    border:1px solid var(--line);background:var(--raised);border-radius:20px;padding:5px 14px}
  .ih-node svg{flex:none}
  .ih-node small{color:var(--muted)}
  .drop{width:0;height:16px;border-left:1px dashed var(--line)}
  .lane{display:flex;align-items:center;gap:16px}
  .party{display:flex;align-items:center;gap:11px;font-weight:600;font-size:14px}
  .party small{display:block;color:var(--muted);font-weight:400;font-size:12px}
  .avatar{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;flex:none;
    background:var(--raised);border:1px solid var(--line);color:var(--ink2);font-weight:700}
  .pipe{flex:1;height:26px;position:relative}
  .pipe::before{content:"";position:absolute;left:0;right:0;top:50%;height:2px;margin-top:-1px;
    background:var(--line);border-radius:2px}
  .pkt{position:absolute;top:50%;width:8px;height:8px;margin-top:-4px;border-radius:50%;
    background:var(--accent);opacity:0;box-shadow:0 0 10px rgba(57,135,229,.8)}
  body.running .pkt{animation:travel 2.2s linear infinite}
  .pkt.p2{animation-delay:.7s!important}
  .pkt.p3{animation-delay:1.4s!important}
  @keyframes travel{0%{left:0;opacity:0}10%{opacity:1}90%{opacity:1}100%{left:calc(100% - 8px);opacity:0}}
  body.done-ok .pipe::before{background:linear-gradient(90deg,var(--good),var(--accent))}

  /* ---------- Timeline ---------- */
  .timeline{list-style:none;margin:0;padding:0}
  .stage{display:flex;gap:18px;position:relative;padding-bottom:18px}
  .stage:not(:last-child)::before{content:"";position:absolute;left:17px;top:40px;bottom:-2px;
    width:2px;background:var(--line);border-radius:2px}
  .stage.ok:not(:last-child)::before{background:var(--good)}
  .badge{flex:none;width:36px;height:36px;border-radius:50%;display:grid;place-items:center;
    font-weight:700;font-size:14px;background:var(--raised);border:2px solid var(--line);
    color:var(--muted);position:relative;z-index:1;transition:all .25s}
  .stage.run .badge{border-color:var(--accent);color:var(--accent)}
  .stage.run .badge::after{content:"";position:absolute;inset:-2px;border-radius:50%;
    border:2px solid transparent;border-top-color:var(--accent);animation:spin2 .9s linear infinite}
  @keyframes spin2{to{transform:rotate(360deg)}}
  .stage.ok .badge{background:var(--good);border-color:var(--good);color:#fff}
  .stage.fail .badge{background:var(--crit);border-color:var(--crit);color:#fff}
  .card{flex:1;min-width:0;background:var(--surface);border:1px solid var(--ring);border-radius:13px;
    padding:15px 18px;transition:border-color .3s,opacity .3s;opacity:.55}
  .stage.run .card{opacity:1;border-color:rgba(57,135,229,.55)}
  .stage.ok .card,.stage.fail .card{opacity:1}
  .stage.fail .card{border-color:rgba(208,59,59,.6)}
  .head{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap}
  .title{font-weight:650;font-size:15px}
  .state-chip{font-size:11.5px;color:var(--muted);letter-spacing:.3px;text-transform:uppercase}
  .stage.run  .state-chip{color:var(--accent)}
  .stage.ok   .state-chip{color:var(--good)}
  .stage.fail .state-chip{color:var(--crit)}
  .detail{color:var(--muted);font-size:13.5px;margin:3px 0 0}
  .stage.ok .detail{color:var(--ink2)}
  .chips{margin-top:10px;display:flex;flex-wrap:wrap;gap:6px}
  .chip{font-size:12px;background:var(--raised);border:1px solid var(--line);border-radius:6px;
    padding:3px 10px;color:var(--ink2);font-family:var(--mono)}
  .chip::before{content:"";display:inline-block;width:6px;height:6px;border-radius:50%;
    background:var(--accent);margin-right:7px;vertical-align:1px}
  .techlog{margin:10px 0 0;padding:0;list-style:none;font-family:var(--mono);
    font-size:12.5px;color:var(--muted)}
  .techlog li{padding:2.5px 0 2.5px 12px;border-left:2px solid var(--line);margin-left:2px;
    word-break:break-all}
  .techlog .t{color:var(--ink2);font-variant-numeric:tabular-nums;margin-right:8px}

  /* ---------- Gate-Kacheln (Stat-Tiles) ---------- */
  .gate-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px;margin-top:14px}
  .tile{border-radius:12px;padding:16px 18px;background:var(--raised);border:1px solid var(--line);
    animation:rise .4s ease}
  @keyframes rise{from{opacity:0;transform:translateY(8px)}}
  .tile.good{border-color:var(--good);box-shadow:0 0 26px rgba(12,163,12,.12)}
  .tile.crit{border-color:var(--crit)}
  .tile-top{display:flex;align-items:center;gap:9px;font-size:12.5px;font-weight:650;
    letter-spacing:.6px;text-transform:uppercase;color:var(--ink2)}
  .tile-top svg{flex:none}
  .hero{font-size:42px;font-weight:650;line-height:1.15;margin:6px 0 0;font-variant-numeric:tabular-nums}
  .hero small{font-size:14px;font-weight:500;color:var(--muted);margin-left:7px}
  .tile.good .hero{color:var(--good)}
  .tile.crit .hero{color:var(--crit)}
  .tile-sub{font-size:13.5px;color:var(--ink2);margin-top:3px}
  .payload{margin:12px 0 0;font-family:var(--mono);font-size:12px;color:var(--ink2);
    background:var(--sunken);border:1px solid var(--line);border-radius:8px;padding:10px 12px;
    white-space:pre-wrap;word-break:break-all;max-height:180px;overflow:auto}

  /* ---------- Fazit ---------- */
  .fazit{margin-top:26px;padding:18px 20px;border-radius:13px;border:1px solid var(--accent);
    background:linear-gradient(180deg,rgba(57,135,229,.09),transparent);font-size:15px;color:var(--ink2);
    animation:rise .5s ease}
  .fazit b{color:var(--ink)}

  body:not(.tech) .tech{display:none}
  @media (prefers-reduced-motion: reduce){
    *,*::before,*::after{animation:none!important;transition:none!important}
  }
</style>
</head>
<body class="tech">
<div class="wrap">
  <header>
    <svg class="turbine" viewBox="0 0 64 64" width="46" height="46" aria-hidden="true">
      <line x1="32" y1="27" x2="32" y2="58" stroke="#898781" stroke-width="2.5" stroke-linecap="round"/>
      <g class="rotor" fill="#3987e5">
        <path d="M32 26 C 30.4 17, 30.4 10, 32 3.5 C 33.6 10, 33.6 17, 32 26 Z"/>
        <path d="M32 26 C 30.4 17, 30.4 10, 32 3.5 C 33.6 10, 33.6 17, 32 26 Z" transform="rotate(120 32 26)"/>
        <path d="M32 26 C 30.4 17, 30.4 10, 32 3.5 C 33.6 10, 33.6 17, 32 26 Z" transform="rotate(240 32 26)"/>
      </g>
      <circle cx="32" cy="26" r="3" fill="#c3c2b7"/>
    </svg>
    <div class="brand">WIND-X Connector
      <small>Souveräner Datenaustausch · DID-Identität · DSP 2025</small>
    </div>
    <div class="controls">
      <label class="toggle"><input type="checkbox" id="techToggle" checked> Technik-Details</label>
      <span class="pill" id="status"><span class="dot"></span><span id="statusText">bereit</span></span>
      <button id="start">Demo starten</button>
    </div>
  </header>
  <p class="lede">Zwei Organisationen ohne Vorbeziehung tauschen Daten aus — aber nur mit
    ausgehandeltem Vertrag. Der Connector setzt das durch.</p>

  <div class="scene" aria-label="Datenraum-Topologie">
    <div class="ih">
      <div class="ih-node">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#3987e5" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 3l7 3v5c0 4.6-3 8-7 10-4-2-7-5.4-7-10V6z"/>
        </svg>
        IdentityHub&nbsp;<small>· DID-Vertrauensanker</small>
      </div>
      <div class="drop"></div>
    </div>
    <div class="lane">
      <div class="party"><div class="avatar">C</div><div>Consumer<small>Datennutzer</small></div></div>
      <div class="pipe"><i class="pkt p1"></i><i class="pkt p2"></i><i class="pkt p3"></i></div>
      <div class="party"><div>Provider<small>Datenanbieter</small></div><div class="avatar">P</div></div>
    </div>
  </div>

  <ol class="timeline" id="stages"></ol>

  <div class="fazit" id="fazit" style="display:none">
    <b>Der Vertrag IST das Tor.</b> Ohne Agreement kein Token, ohne Token keine Daten —
    selbst für einen authentifizierten Partner. Das ist Datensouveränität, durchgesetzt vom Connector.
  </div>
</div>

<script>
const STAGES = [
  {id:"provide",  n:1, title:"Der Provider stellt Daten bereit", detail:"Asset, Nutzungsregeln und Angebot."},
  {id:"catalog",  n:2, title:"Identität & Katalog",             detail:"Kryptografischer Ausweis per DID, dann: Was bietest du an?"},
  {id:"contract", n:3, title:"Der Vertrag wird ausgehandelt",   detail:"angefragt → vereinbart → besiegelt."},
  {id:"gate",     n:4, title:"Das Tor",                          detail:"Dieselbe Adresse — einmal mit, einmal ohne Vertrag."},
];
const STATE_LABEL = {idle:"wartet", run:"läuft …", ok:"fertig", fail:"fehler"};
const LOCK_OPEN = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#0ca30c" stroke-width="2.2" stroke-linecap="round"><rect x="4" y="11" width="14" height="9" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.5-2"/></svg>';
const LOCK_SHUT = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#d03b3b" stroke-width="2.2" stroke-linecap="round"><rect x="4" y="11" width="14" height="9" rx="2"/><path d="M7 11V8a4 4 0 0 1 8 0v3"/></svg>';
const host = document.getElementById("stages");
const els = {};
let t0 = 0;

function build(){
  host.innerHTML = "";
  for(const s of STAGES){
    const li = document.createElement("li");
    li.className = "stage";
    li.innerHTML =
      '<div class="badge">'+s.n+'</div>'+
      '<div class="card">'+
        '<div class="head"><span class="title">'+s.title+'</span>'+
        '<span class="state-chip">'+STATE_LABEL.idle+'</span></div>'+
        '<p class="detail">'+s.detail+'</p>'+
        '<div class="chips" data-role="chips"></div>'+
        '<ul class="techlog tech" data-role="log"></ul>'+
        '<div class="gate-grid" data-role="gate"></div>'+
      '</div>';
    host.appendChild(li);
    els[s.id] = li;
  }
  document.getElementById("fazit").style.display = "none";
  document.body.classList.remove("done-ok");
}
build();

function setStage(id, state, title, detail){
  const el = els[id]; if(!el) return;
  el.classList.remove("run","ok","fail"); el.classList.add(state);
  const b = el.querySelector(".badge");
  b.textContent = state==="ok" ? "✓" : state==="fail" ? "✕" : STAGES.find(s=>s.id===id).n;
  el.querySelector(".state-chip").textContent = STATE_LABEL[state] || state;
  if(title)  el.querySelector(".title").textContent = title;
  if(detail) el.querySelector(".detail").textContent = detail;
}
function addLog(id, text){
  const ul = els[id]?.querySelector('[data-role=log]'); if(!ul) return;
  const li = document.createElement("li");
  const dt = ((performance.now()-t0)/1000).toFixed(1);
  li.innerHTML = '<span class="t">+'+dt+'s</span>';
  li.appendChild(document.createTextNode(text));
  ul.appendChild(li);
}
function setChips(id, list){
  const c = els[id]?.querySelector('[data-role=chips]'); if(!c) return;
  c.innerHTML = list.map(t=>'<span class="chip">'+t+'</span>').join("");
}
function pretty(body){
  try{ return JSON.stringify(JSON.parse(body), null, 2); }catch(e){ return body; }
}
function addGate(which, code, body){
  const g = els["gate"].querySelector('[data-role=gate]');
  const on = which==="with";
  const tile = document.createElement("div");
  tile.className = "tile " + (on ? "good" : "crit");
  tile.innerHTML =
    '<div class="tile-top">'+(on?LOCK_OPEN:LOCK_SHUT)+(on?"Mit Vertrag":"Ohne Vertrag")+'</div>'+
    '<div class="hero">'+code+'<small>HTTP</small></div>'+
    '<div class="tile-sub">'+(on?"Die Daten fließen.":"Dieselbe Adresse — die Tür bleibt zu.")+'</div>'+
    (on && body ? '<pre class="payload tech">'+pretty(body).replace(/</g,"&lt;")+'</pre>' : '');
  g.appendChild(tile);
}

const pillEl = document.getElementById("status");
const statusText = document.getElementById("statusText");
const btn = document.getElementById("start");
function setStatus(mode, text){ pillEl.className = "pill "+mode; statusText.textContent = text; }
document.getElementById("techToggle").addEventListener("change", e=>{
  document.body.classList.toggle("tech", e.target.checked);
});

btn.addEventListener("click", ()=>{
  build();
  t0 = performance.now();
  btn.disabled = true; setStatus("run","läuft …");
  document.body.classList.add("running");
  let lastRun = "gate";
  const es = new EventSource("/api/run");
  es.onmessage = ev=>{
    const m = JSON.parse(ev.data);
    if(m.kind==="stage"){ setStage(m.id, m.state, m.title, m.detail); if(m.state==="run") lastRun = m.id; }
    else if(m.kind==="sub")   addLog(m.id, m.text);
    else if(m.kind==="types") setChips("catalog", m.list);
    else if(m.kind==="gate")  addGate(m.which, m.code, m.body);
    else if(m.kind==="error"){ setStatus("err","Fehler"); addLog(lastRun, "Fehler: "+m.message); }
    else if(m.kind==="done"){
      es.close(); btn.disabled = false;
      document.body.classList.remove("running");
      const okAll = els["gate"].classList.contains("ok");
      setStatus(okAll ? "ok" : "err", okAll ? "fertig ✓" : "beendet");
      if(okAll){
        document.getElementById("fazit").style.display = "block";
        document.body.classList.add("done-ok");
      }
    }
  };
  es.onerror = ()=>{ es.close(); btn.disabled = false;
    document.body.classList.remove("running"); setStatus("err","Verbindung getrennt"); };
});

/* ?preview — spielt den Ablauf mit Beispieldaten ab (UI-Vorschau ohne Cluster) */
if(new URLSearchParams(location.search).has("preview")){
  setTimeout(()=>{
    t0 = performance.now();
    document.body.classList.add("running"); setStatus("run","läuft …"); btn.disabled = true;
    const seq = [
      [200,  ()=>setStage("provide","run",null,"Asset, Policy und Angebot anlegen …")],
      [700,  ()=>addLog("provide","POST /assets -> 200 · /policydefinitions -> 200 · /contractdefinitions -> 200")],
      [900,  ()=>setStage("provide","ok",null,"'windx-asset-1' ist online, Policy permissiv.")],
      [1100, ()=>setStage("catalog","run")],
      [1600, ()=>setChips("catalog",["Mms-PULL","MediatorHttpData-PULL","MmsLight-PULL","HttpData-PUSH"])],
      [1700, ()=>addLog("catalog","counterPartyId=did:web:windx-identityhub:provider · offerId=d2luZHgtY2QtMQ==:…")],
      [1900, ()=>setStage("catalog","ok",null,"Kryptografisches Vertrauen per DID steht — der Katalog kam an.")],
      [2100, ()=>setStage("contract","run")],
      [2400, ()=>addLog("contract","Vertragsstatus: angefragt (REQUESTED)")],
      [2900, ()=>addLog("contract","Vertragsstatus: besiegelt (FINALIZED)")],
      [3100, ()=>addLog("contract","agreementId=4c40156f-88ef-4f49-9fb3-6ceccac9e67b · transferProcessId=7018295d-…")],
      [3300, ()=>setStage("contract","ok",null,"Vertrag besiegelt — es existiert ein signiertes Agreement.")],
      [3500, ()=>setStage("gate","run")],
      [3800, ()=>addLog("gate","GET /edrs/…/dataaddress -> endpoint=http://windx-provider:19293/api/public")],
      [4100, ()=>addGate("with",200,'{"componentResults":[{"failure":null,"component":"BaseRuntime","isHealthy":true}],"isSystemHealthy":true}')],
      [4500, ()=>addGate("without",401,"")],
      [4700, ()=>{ setStage("gate","ok",null,"Der Vertrag IST das Tor: mit Vertrag fließen die Daten, ohne bleibt die Tür zu.");
                   document.body.classList.remove("running"); setStatus("ok","fertig ✓"); btn.disabled = false;
                   document.getElementById("fazit").style.display = "block";
                   document.body.classList.add("done-ok"); }],
    ];
    for(const [d,f] of seq) setTimeout(f,d);
  }, 400);
}
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
