#!/usr/bin/env python3
"""Phase 5: MMS-Use-Case -- Turbinen-Telemetrie (IEC 61850) aus dem iecsim ueber den EDC ziehen.

Fluss: Katalog -> Negotiation (echte Membership-Policy) -> Transfer 'Mms-PULL' -> EDR ->
GET auf die Public-API. Beim Pull oeffnet die Provider-Data-Plane die Mms-Source, pollt den
iecsim (AGGREGATION_SIZE=10 Nachrichten, updateInterval 1000 ms -> ~10 s) und liefert EINE
JSON-Aggregation. Gegenprobe ohne Token -> 401.

WICHTIG -- zwei Wege, die hier NICHT gehen:
  * POST /v3/edrs (der Phase-4-Weg) verdrahtet den Transfer fest auf 'HttpData-PULL' +
    Destination 'HttpProxy' (edr-callback/ContractNegotiationCallback.java) -> fuer 'Mms-PULL'
    zwingend der Zwei-Schritt-Weg: /v3/contractnegotiations + /v3/transferprocesses.
  * Der assigner im Offer muss die BPN sein, nicht die DID (PolicyEquality, siehe
    windx-cx-transfer.py und docs/windx/demonstrator-catenax-plan.md, Phase-4-Fallen).

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
ASSET = "windx-mms-asset-1"
KEY = "password"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}


def call(method, url, body=None, token=None, raw=False, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    else:
        req.add_header("X-Api-Key", KEY)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read().decode()
            return r.status, (payload if raw else (json.loads(payload) if payload else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


print("==== A) Katalog: bietet der Provider Mms-PULL an? ====")
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
    print(f"  FEHLER: {ASSET} nicht im Katalog. Vorhanden:",
          [d.get("@id") for d in datasets])
    raise SystemExit(1)
ds_str = json.dumps(ds)
print(f"  dataset:  {ASSET}")
print(f"  Mms-PULL beworben:      {'Mms-PULL' in ds_str}")
print(f"  HttpData-PUSH beworben: {'HttpData-PUSH' in ds_str}")

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

print("==== C) Transfer 'Mms-PULL' starten (Zwei-Schritt-Weg, NICHT /v3/edrs) ====")
st, tp = call("POST", f"{CONS}/v3/transferprocesses", {
    "@context": VOCAB, "@type": "TransferRequest",
    "counterPartyAddress": PROV_DSP, "protocol": "dataspace-protocol-http",
    "contractId": agreement, "assetId": ASSET,
    "transferType": "Mms-PULL",
    "dataDestination": {"type": "Mms"}})
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

print("==== D) EDR abholen ====")
da = None
for _ in range(10):
    st, da = call("GET", f"{CONS}/v3/edrs/{tpid}/dataaddress")
    if st == 200 and isinstance(da, dict):
        break
    time.sleep(3)
endpoint = da.get("endpoint") if isinstance(da, dict) else None
token = da.get("authorization") if isinstance(da, dict) else None
print(f"  endpoint: {endpoint}")
print(f"  token:    {str(token)[:28]}...")
if not endpoint:
    raise SystemExit("  FEHLER: keine EDR / kein Endpoint")

print("==== E) DATENABRUF (Aggregation braucht ~10 s: 10 Polls a 1000 ms) ====")
t0 = time.time()
st, body = call("GET", endpoint, token=token, raw=True, timeout=150)
dt = time.time() - t0
print(f"--- MIT Token  -> [HTTP {st}] nach {dt:.1f}s")
try:
    agg = json.loads(body)
    print(f"    messageCount: {agg.get('messageCount')}")
    print(f"    dataArrays:   {list(agg.get('dataArrays', {}).keys()) if isinstance(agg.get('dataArrays'), dict) else agg.get('dataArrays')}")
    print(f"    (gesamt {len(body)} Bytes JSON)")
    print("    " + body[:400])
except Exception:                                             # noqa: BLE001
    print("    " + str(body)[:400])

st2, _ = call("GET", endpoint, raw=True)
print(f"--- OHNE Token -> [HTTP {st2}]  (erwartet 401/403)")

ok = st == 200 and st2 in (401, 403)
print("\n==== ERGEBNIS:", "GRUEN" if ok else "ROT", "====")
