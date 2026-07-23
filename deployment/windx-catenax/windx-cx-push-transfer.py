#!/usr/bin/env python3
"""Mms-PUSH -- kontinuierlicher Provider-Push der Turbinen-Telemetrie in den Forwarding-Service.

Fluss: Katalog (bewirbt Mms-PUSH?) -> Negotiation (Membership-Policy) -> Transfer 'Mms-PUSH'
mit dataDestination {type: Mms, baseUrl: http://windx-forwarding:4000/log?signal=...} -> STARTED
bleibt STARTED (non-finite) -> Forwarding-Queue liefert fortlaufend Aggregationen (~1 alle 10 s:
AGGREGATION_SIZE=10 x updateInterval 1000 ms) -> Terminate stoppt den Strom (totalReceived stabil).

Voraussetzungen: windx-cx-seed.sh (Abschnitt 3d: Asset windx-mms-live-1 mit edc:isNonFinite=true)
und windx-forwarding-deploy.sh (Push-Ziel). Anders als beim PULL gibt es KEINE EDR und keinen
Public-API-Abruf -- der Provider POSTet selbst; die 401-Gegenprobe entfaellt deshalb.

Laeuft IM Cluster (python-Image). Nur Standardbibliothek.
"""
import json
import time
import urllib.error
import urllib.request

CONS = "http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP = "http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID = "did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
PROV_BPN = "BPNL00000003AVTH"
ASSET = "windx-mms-live-1"
SIGNAL = "windx-verify"
FWD = "http://windx-forwarding:4000"
KEY = "password"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}


def call(method, url, body=None, raw=False, timeout=30, api_key=True):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if api_key:
        req.add_header("X-Api-Key", KEY)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read().decode()
            return r.status, (payload if raw else (json.loads(payload) if payload else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


def total_received():
    """totalReceived unseres Signals aus /log/queues (0, wenn Queue noch nie beschrieben)."""
    st, queues = call("GET", f"{FWD}/log/queues", api_key=False)
    if st != 200 or not isinstance(queues, list):
        return None
    return next((q.get("totalReceived", 0) for q in queues if q.get("signal") == SIGNAL), 0)


print("==== 0) Forwarding-Service erreichbar? ====")
st, _ = call("GET", f"{FWD}/health", api_key=False, timeout=5)
print(f"  GET {FWD}/health -> HTTP {st}")
if st != 200:
    raise SystemExit("  FEHLER: Forwarding-Service fehlt -- erst windx-forwarding-deploy.sh ausfuehren")

print("==== A) Katalog: bietet der Provider Mms-PUSH an? ====")
st, cat = call("POST", f"{CONS}/v3/catalog/request", {
    "@context": VOCAB, "counterPartyAddress": PROV_DSP,
    "counterPartyId": PROV_DID, "protocol": "dataspace-protocol-http"})
print(f"  POST /v3/catalog/request -> HTTP {st}")
if st != 200:
    print("  FEHLER:", cat)
    raise SystemExit(1)

datasets = cat.get("dcat:dataset") or cat.get("dataset") or []
if isinstance(datasets, dict):
    datasets = [datasets]
ds = next((d for d in datasets if d.get("@id") == ASSET), None)
if not ds:
    print(f"  FEHLER: {ASSET} nicht im Katalog (windx-cx-seed.sh 3d gelaufen?). Vorhanden:",
          [d.get("@id") for d in datasets])
    raise SystemExit(1)
ds_str = json.dumps(ds)
print(f"  dataset:  {ASSET}")
print(f"  Mms-PUSH beworben: {'Mms-PUSH' in ds_str}")
if "Mms-PUSH" not in ds_str:
    raise SystemExit("  FEHLER: Mms-PUSH fehlt -- Data-Plane-Image mit MmsPushSinkFactory ausgerollt?")

offer = ds.get("odrl:hasPolicy") or ds.get("hasPolicy")
if isinstance(offer, list):
    offer = offer[0]
print(f"  offerId:  {offer.get('@id')}")

# Offer 1:1 uebernehmen; Fallen aus Phase 4: leere prohibition/obligation raus, odrl:Offer,
# Target auf Policy-Ebene, assigner = BPN (nicht DID!).
policy = dict(offer)
for _k in ("odrl:prohibition", "odrl:obligation", "prohibition", "obligation"):
    if _k in policy and not policy[_k]:
        del policy[_k]
policy["@type"] = "odrl:Offer"
policy["odrl:target"] = {"@id": ASSET}
policy["odrl:assigner"] = {"@id": PROV_BPN}

ctx = cat.get("@context", {})
if isinstance(ctx, dict):
    req_ctx = {**ctx, **VOCAB}
elif isinstance(ctx, list):
    req_ctx = ctx + [VOCAB]
else:
    req_ctx = [ctx, VOCAB]

print("==== B) Contract-Negotiation ====")
st, res = call("POST", f"{CONS}/v3/contractnegotiations", {
    "@context": req_ctx, "@type": "ContractRequest",
    "counterPartyAddress": PROV_DSP, "counterPartyId": PROV_DID,
    "protocol": "dataspace-protocol-http", "policy": policy})
print(f"  POST /v3/contractnegotiations -> HTTP {st}")
if st not in (200, 201):
    print("  FEHLER:", res)
    raise SystemExit(1)
nid = res.get("@id")

agreement = None
for i in range(30):
    st, n = call("GET", f"{CONS}/v3/contractnegotiations/{nid}")
    state = n.get("state") if isinstance(n, dict) else None
    agreement = n.get("contractAgreementId") if isinstance(n, dict) else None
    print(f"  [{i}] state={state} agreement={agreement or ''}")
    if state == "FINALIZED":
        break
    if state == "TERMINATED":
        print("  TERMINATED:", json.dumps(n)[:400])
        raise SystemExit(1)
    time.sleep(3)
if not agreement:
    raise SystemExit("  FEHLER: kein Agreement")

print("==== C) Transfer 'Mms-PUSH' starten (Zwei-Schritt-Weg) ====")
base_total = total_received() or 0
st, tp = call("POST", f"{CONS}/v3/transferprocesses", {
    "@context": VOCAB, "@type": "TransferRequest",
    "counterPartyAddress": PROV_DSP, "protocol": "dataspace-protocol-http",
    "contractId": agreement, "assetId": ASSET,
    "transferType": "Mms-PUSH",
    "dataDestination": {"type": "Mms", "baseUrl": f"{FWD}/log?signal={SIGNAL}"}})
print(f"  POST /v3/transferprocesses -> HTTP {st}")
if st not in (200, 201):
    print("  FEHLER:", tp)
    raise SystemExit(1)
tpid = tp.get("@id")
print(f"  transferProcessId: {tpid}")

state = None
for i in range(30):
    st, t = call("GET", f"{CONS}/v3/transferprocesses/{tpid}")
    state = t.get("state") if isinstance(t, dict) else None
    print(f"  [{i}] state={state}")
    if state == "STARTED":
        break
    if state == "TERMINATED":
        print("  TERMINATED:", json.dumps(t)[:500])
        raise SystemExit(1)
    time.sleep(3)
if state != "STARTED":
    raise SystemExit(f"  FEHLER: Transfer haengt in {state}")

print("==== D) Kontinuierlicher Strom: >=2 Aggregationen aus der Forwarding-Queue ====")
print("  (je Aggregation ~10 s: 10 Polls a 1000 ms; Timeout 120 s)")
aggregations = []
deadline = time.time() + 120
while len(aggregations) < 2 and time.time() < deadline:
    st, body = call("GET", f"{FWD}/log?signal={SIGNAL}", raw=True, api_key=False)
    if st == 200 and body:
        aggregations.append(body)
        try:
            agg = json.loads(body)
            print(f"  Aggregation {len(aggregations)}: messageCount={agg.get('messageCount')}"
                  f" timestamp={agg.get('aggregationTimestamp')} ({len(body)} Bytes)")
        except Exception:                                     # noqa: BLE001
            print(f"  Aggregation {len(aggregations)}: {body[:120]}")
    else:
        time.sleep(3)
if len(aggregations) < 2:
    raise SystemExit("  FEHLER: keine 2 Aggregationen innerhalb 120 s")

st, t = call("GET", f"{CONS}/v3/transferprocesses/{tpid}")
state = t.get("state") if isinstance(t, dict) else None
print(f"  Transfer nach 2 Aggregationen weiterhin: {state} (erwartet STARTED, non-finite)")
still_started = state == "STARTED"

print("==== E) Terminate stoppt den Strom ====")
st, _ = call("POST", f"{CONS}/v3/transferprocesses/{tpid}/terminate", {
    "@context": VOCAB, "@type": "TerminateTransfer", "reason": "windx-cx-push-transfer.py: Verifikation beendet"})
print(f"  POST /v3/transferprocesses/{tpid}/terminate -> HTTP {st}")
time.sleep(15)  # DSP-Signal + letzte in-flight-Aggregation ablaufen lassen
count_after_grace = total_received()
time.sleep(15)
count_later = total_received()
print(f"  totalReceived: vor Transfer={base_total}, nach Grace={count_after_grace}, +15s={count_later}")
stopped = count_after_grace is not None and count_after_grace == count_later

ok = still_started and stopped and len(aggregations) >= 2
print("\n==== ERGEBNIS:", "GRUEN" if ok else "ROT", "====")
print(f"  >=2 Aggregationen empfangen: {len(aggregations) >= 2}")
print(f"  STARTED blieb STARTED:       {still_started}")
print(f"  Terminate stoppt den Strom:  {stopped}")
raise SystemExit(0 if ok else 1)
