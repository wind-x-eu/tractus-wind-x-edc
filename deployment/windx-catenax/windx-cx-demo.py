#!/usr/bin/env python3
"""Demo-Stufen fuer den MMS-Use-Case (Phase 5) -- wird von windx-cx-demo.sh Akt fuer Akt aufgerufen.

Jede Stufe ist ein eigener Aufruf (kubectl exec); der Zwischenzustand (Offer, Agreement, EDR)
liegt in /tmp/demo-state.json im Pod. Fachlicher Fluss und alle Fallen wie in
windx-cx-mms-transfer.py (BPN-Assigner, Zwei-Schritt-Weg statt /v3/edrs, ~10 s pro Aggregation).

Stufen: katalog | vertrag | daten | schloss
"""
import json
import sys
import time
import urllib.error
import urllib.request

CONS = "http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP = "http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID = "did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
PROV_BPN = "BPNL00000003AVTH"
ASSET = "windx-mms-asset-1"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}
STATE = "/tmp/demo-state.json"


def call(method, url, body=None, token=None, raw=False, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    else:
        req.add_header("X-Api-Key", "password")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read().decode()
            return r.status, (payload if raw else (json.loads(payload) if payload else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


def load():
    try:
        with open(STATE) as f:
            return json.load(f)
    except Exception:                                         # noqa: BLE001
        return {}


def save(st):
    with open(STATE, "w") as f:
        json.dump(st, f)


def fail(msg):
    print(f"  FEHLER: {msg}")
    sys.exit(1)


def akt_katalog():
    st, cat = call("POST", f"{CONS}/v3/catalog/request", {
        "@context": VOCAB, "counterPartyAddress": PROV_DSP,
        "counterPartyId": PROV_DID, "protocol": "dataspace-protocol-http"})
    if st != 200:
        fail(f"Katalog HTTP {st}: {cat}")
    datasets = cat.get("dcat:dataset") or cat.get("dataset") or []
    if isinstance(datasets, dict):
        datasets = [datasets]
    ds = next((d for d in datasets if d.get("@id") == ASSET), None)
    if not ds:
        fail(f"{ASSET} nicht im Katalog -- windx-cx-seed.sh gelaufen?")
    offer = ds.get("odrl:hasPolicy") or ds.get("hasPolicy")
    if isinstance(offer, list):
        offer = offer[0]

    name = ds.get("name") or (ds.get("properties") or {}).get("name") or ""
    dist = json.dumps(ds.get("dcat:distribution") or ds.get("distribution") or "")
    print(f"  Asset:         {ASSET}")
    print(f"  Name:          {name}")
    print(f"  Transferarten: Mms-PULL={'Mms-PULL' in dist}  HttpData-PUSH={'HttpData-PUSH' in dist}")

    # Membership-Constraint aus dem Offer herausziehen und lesbar zeigen
    try:
        perm = offer.get("odrl:permission")
        if isinstance(perm, list):
            perm = perm[0]
        con = perm.get("odrl:constraint")
        cand = con.get("odrl:and", con)
        if isinstance(cand, list):
            cand = cand[0]
        left = cand.get("odrl:leftOperand", {})
        op = cand.get("odrl:operator", {})
        print("  Policy:        "
              f"{left.get('@id', left)} "
              f"{str(op.get('@id', op)).replace('odrl:', '')} "
              f"'{cand.get('odrl:rightOperand')}'")
    except Exception:                                         # noqa: BLE001
        print("  Policy:        (Constraint nicht extrahierbar -- Roh-Offer im State)")

    ctx = cat.get("@context", {})
    save({"offer": offer, "ctx": ctx})


def akt_vertrag():
    state = load()
    offer, ctx = state.get("offer"), state.get("ctx", {})
    if not offer:
        fail("kein Offer im State -- erst Stufe 'katalog'")

    # Die Phase-4-Fallen: leere prohibition/obligation raus, odrl:Offer,
    # Target auf Policy-Ebene, assigner = BPN (nicht die DID!).
    policy = dict(offer)
    for k in ("odrl:prohibition", "odrl:obligation", "prohibition", "obligation"):
        if k in policy and not policy[k]:
            del policy[k]
    policy["@type"] = "odrl:Offer"
    policy["odrl:target"] = {"@id": ASSET}
    policy["odrl:assigner"] = {"@id": PROV_BPN}
    req_ctx = {**ctx, **VOCAB} if isinstance(ctx, dict) else [ctx, VOCAB]

    st, res = call("POST", f"{CONS}/v3/contractnegotiations", {
        "@context": req_ctx, "@type": "ContractRequest",
        "counterPartyAddress": PROV_DSP, "counterPartyId": PROV_DID,
        "protocol": "dataspace-protocol-http", "policy": policy})
    if st not in (200, 201):
        fail(f"Negotiation HTTP {st}: {res}")
    nid = res.get("@id")

    agreement = None
    for i in range(30):
        st, n = call("GET", f"{CONS}/v3/contractnegotiations/{nid}")
        s = n.get("state") if isinstance(n, dict) else None
        agreement = n.get("contractAgreementId") if isinstance(n, dict) else None
        print(f"  [{i}] {s}")
        if s == "FINALIZED":
            break
        if s == "TERMINATED":
            fail(json.dumps(n)[:300])
        time.sleep(3)
    if not agreement:
        fail("kein Agreement")
    print(f"  Agreement: {agreement}")
    state["agreement"] = agreement
    save(state)


def pull(state, label):
    """EDR (mit frischem Token) holen und eine Aggregation ziehen; letzte Windwerte zeigen."""
    tpid = state["tpid"]
    st, da = call("GET", f"{CONS}/v3/edrs/{tpid}/dataaddress")
    if st != 200:
        fail(f"EDR-Abruf HTTP {st}: {da}")
    print(f"  {label}: Abruf laeuft -- die Data-Plane pollt jetzt 10 x den iecsim (~10 s) ...")
    t0 = time.time()
    st, body = call("GET", da["endpoint"], token=da["authorization"], raw=True, timeout=150)
    dt = time.time() - t0
    if st != 200:
        fail(f"Pull HTTP {st}: {str(body)[:200]}")
    agg = json.loads(body)
    arrays = agg.get("dataArrays", {})

    def vals(key):
        # dataArrays ist verschachtelt: {sensor: {"keys": [...], "values": [...]}}
        v = arrays.get(key, [])
        if isinstance(v, dict):
            v = v.get("values", [])
        return v if isinstance(v, list) else []

    wind = vals("WNAC1$MX$WdSpd$mag$f")
    print(f"  HTTP 200 nach {dt:.1f} s -- Aggregation aus {agg.get('messageCount')} Messungen:")
    print(f"    Windgeschwindigkeit [m/s]: {' '.join(x[:5] for x in wind)}")
    print(f"    Leistung [kW]:  {vals('WGEN1$MX$W$mag$f')[-1:]}   "
          f"Rotor [rpm]: {vals('WROT1$MX$RotSpd$mag$f')[-1:]}   "
          f"Oeltemp [C]: {vals('WGBX1$MX$OilTmp$mag$f')[-1:]}   "
          f"Running: {vals('WTUR1$ST$Running$stVal')[-1:]}")
    return wind


def akt_daten():
    state = load()
    agreement = state.get("agreement")
    if not agreement:
        fail("kein Agreement im State -- erst Stufe 'vertrag'")

    st, tp = call("POST", f"{CONS}/v3/transferprocesses", {
        "@context": VOCAB, "@type": "TransferRequest",
        "counterPartyAddress": PROV_DSP, "protocol": "dataspace-protocol-http",
        "contractId": agreement, "assetId": ASSET,
        "transferType": "Mms-PULL", "dataDestination": {"type": "Mms"}})
    if st not in (200, 201):
        fail(f"Transfer HTTP {st}: {tp}")
    tpid = tp.get("@id")
    for i in range(30):
        st, t = call("GET", f"{CONS}/v3/transferprocesses/{tpid}")
        s = t.get("state") if isinstance(t, dict) else None
        print(f"  [{i}] {s}")
        if s == "STARTED":
            break
        if s == "TERMINATED":
            fail(json.dumps(t)[:400])
        time.sleep(3)
    else:
        fail("Transfer nicht STARTED")
    state["tpid"] = tpid
    save(state)

    wind = pull(state, "Erster Datenabruf")
    state["last_wind"] = wind[-1] if wind else None
    save(state)


def akt_schloss():
    state = load()
    tpid = state.get("tpid")
    if not tpid:
        fail("kein Transfer im State -- erst Stufe 'daten'")
    st, da = call("GET", f"{CONS}/v3/edrs/{tpid}/dataaddress")
    if st != 200:
        fail(f"EDR-Abruf HTTP {st}")

    st, _ = call("GET", da["endpoint"], raw=True)
    verdict = "ABGEWIESEN" if st in (401, 403) else "!!! NICHT ABGEWIESEN !!!"
    print(f"  Gleicher Endpunkt OHNE Token -> HTTP {st}  ({verdict})")

    print()
    wind = pull(state, "Zweiter Datenabruf (mit Token)")
    old = state.get("last_wind")
    if wind and old:
        print(f"    Vorhin: {old[:7]} m/s -> jetzt: {wind[-1][:7]} m/s  (live, kein Playback)")


if __name__ == "__main__":
    stufe = sys.argv[1] if len(sys.argv) > 1 else ""
    {"katalog": akt_katalog, "vertrag": akt_vertrag,
     "daten": akt_daten, "schloss": akt_schloss}.get(stufe, lambda: fail(f"unbekannte Stufe '{stufe}'"))()
