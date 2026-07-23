#!/usr/bin/env python3
"""Phase 4: Catena-X-Grundfluss -- Katalog -> Negotiation -> EDR -> Datenabruf (+401-Gegenprobe).

WICHTIG (teuer gelernt): Die Policy im EDR-/Negotiation-Request muss EXAKT dem Angebot aus dem
Katalog entsprechen. Baut man sie von Hand nach, lehnt der Consumer das Agreement ab mit
"Policy in the contract agreement is not equal to the one in the contract offer".
Deshalb wird das Offer hier unveraendert aus der Katalogantwort uebernommen.

Laeuft IM Cluster (python-Image), da die Connectoren nur clusterintern erreichbar sind.
Nur Standardbibliothek -- kein pip noetig.
"""
import json
import time
import urllib.error
import urllib.request

CONS = "http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP = "http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID = "did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
# Der Assigner der Policy ist die BPN, NICHT die DID (empirisch bisektiert, siehe unten).
PROV_BPN = "BPNL00000003AVTH"
ASSET = "windx-cx-asset-1"
KEY = "password"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}


def call(method, url, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    else:
        req.add_header("X-Api-Key", KEY)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read().decode()
            return r.status, (payload if raw else (json.loads(payload) if payload else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


print("==== A) Katalog abfragen ====")
st, cat = call("POST", f"{CONS}/v3/catalog/request", {
    "@context": VOCAB, "counterPartyAddress": PROV_DSP,
    "counterPartyId": PROV_DID, "protocol": "dataspace-protocol-http"})
print(f"  POST /v3/catalog/request -> HTTP {st}")
if st != 200:
    print("  FEHLER:", cat)
    raise SystemExit(1)

# Dataset heraussuchen (kann Objekt oder Liste sein) und das Offer UNVERAENDERT uebernehmen
datasets = cat.get("dcat:dataset") or cat.get("dataset") or []
if isinstance(datasets, dict):
    datasets = [datasets]
ds = next((d for d in datasets if ASSET in json.dumps(d)), None)
if not ds:
    print("  FEHLER: Dataset nicht im Katalog:", json.dumps(cat)[:400])
    raise SystemExit(1)
offer = ds.get("odrl:hasPolicy") or ds.get("hasPolicy")
if isinstance(offer, list):
    offer = offer[0]
print(f"  dataset: {ASSET}")
print(f"  offerId: {offer.get('@id')}")

# Offer 1:1 als Policy uebernehmen, nur die vom Negotiation-Request geforderten Felder ergaenzen.
# ODRL-Praefix explizit setzen: mit @vocab=edc wuerde "Offer" zu edc:Offer expandieren, erwartet
# wird aber odrl:Offer ("was expected to be http://www.w3.org/ns/odrl/2/Offer").
policy = dict(offer)
# Leere prohibition/obligation-Arrays entfernen: der Katalog liefert sie mit, die Policy des
# Providers hat sie nicht -> sonst scheitert der Gleichheitsvergleich auf der Consumer-Seite
# ("Policy in the contract agreement is not equal to the one in the contract offer").
for _k in ("odrl:prohibition", "odrl:obligation", "prohibition", "obligation"):
    if _k in policy and not policy[_k]:
        del policy[_k]
# assigner/target muessen JSON-LD-Knoten sein ("...assigner/@id cannot be null or blank"),
# nicht einfache Strings.
policy["@type"] = "odrl:Offer"
policy["odrl:target"] = {"@id": ds.get("@id", ASSET)}

# DER ASSIGNER IST DIE BPN, NICHT DIE DID.
# Teuer bisektiert: mit der DID haengt jede Negotiation in REQUESTED und der Provider meldet
# "Policy in the contract agreement is not equal to the one in the contract offer" -- auch bei
# einer voellig leeren Policy. Grund (EDC 0.17.0, ContractValidationServiceImpl):
#     policyEquality.test(agreement.getPolicy().withTarget(offer.getAssetId()), offer.getPolicy())
# PolicyEquality entfernt vor dem Vergleich nur "@type", "assignee" und "target" -- "assigner"
# wird also mitverglichen. Im Agreement traegt er die participantId des Providers, und die ist
# die BPN; die DID ist nur die DSP-/DID-Identitaet (counterPartyId).
policy["odrl:assigner"] = {"@id": PROV_BPN}

# Kein Target in die einzelnen Rules setzen: Policy.withTarget() setzt das Target NUR auf
# Policy-Ebene, nicht in den Rules -- ein Rule-Target wuerde hier erst recht ungleich machen.

# Den JSON-LD-Kontext des KATALOGS uebernehmen: das Offer nutzt Praefixe (odrl:, cx-policy:),
# die sonst nicht aufgeloest werden ("contains a prefix 'odrl' which was not expended correctly").
# Gleicher Kontext -> gleiche Expansion -> Agreement-Policy == Offer-Policy.
ctx = cat.get("@context", {})
if isinstance(ctx, dict):
    req_ctx = {**ctx, **VOCAB}
elif isinstance(ctx, list):
    req_ctx = ctx + [VOCAB]
else:
    req_ctx = [ctx, VOCAB]

print("==== B) EDR aushandeln (Negotiation + Transfer) ====")
st, edr = call("POST", f"{CONS}/v3/edrs", {
    "@context": req_ctx, "@type": "ContractRequest",
    "counterPartyAddress": PROV_DSP, "counterPartyId": PROV_DID,
    "protocol": "dataspace-protocol-http", "policy": policy})
print(f"  POST /v3/edrs -> HTTP {st}")
if st not in (200, 201):
    print("  FEHLER:", edr)
    raise SystemExit(1)
nid = edr.get("@id")
print(f"  negotiationId: {nid}")

print("==== C) auf FINALIZED warten ====")
state = None
for i in range(40):
    st, n = call("GET", f"{CONS}/v3/contractnegotiations/{nid}")
    state = n.get("state") if isinstance(n, dict) else None
    agr = n.get("contractAgreementId") if isinstance(n, dict) else None
    print(f"  [{i}] state={state} agreement={agr or ''}")
    if state == "FINALIZED":
        break
    if state == "TERMINATED":
        print("  TERMINATED:", json.dumps(n)[:400])
        raise SystemExit(1)
    time.sleep(3)

print("==== D) EDR abholen ====")
tpid = None
for _ in range(20):
    # QuerySpec braucht @type -- sonst expandiert der Body zu nichts und die API antwortet mit
    # HTTP 400 "result was empty, it could be caused by missing '@context'".
    st, edrs = call("POST", f"{CONS}/v3/edrs/request",
                    {"@context": VOCAB, "@type": "QuerySpec", "limit": 50})
    if isinstance(edrs, list) and edrs:
        tpid = edrs[-1].get("transferProcessId") or edrs[-1].get("@id")
        if tpid:
            break
    elif st != 200:
        print(f"  /v3/edrs/request -> HTTP {st}: {str(edrs)[:200]}")
    time.sleep(3)
print(f"  transferProcessId: {tpid}")
if not tpid:
    raise SystemExit("  FEHLER: keine EDR erhalten")

st, da = call("GET", f"{CONS}/v3/edrs/{tpid}/dataaddress")
endpoint = da.get("endpoint")
token = da.get("authorization")
print(f"  endpoint: {endpoint}")
print(f"  token:    {str(token)[:28]}...")

print("==== E) DATENABRUF ====")
st, body = call("GET", endpoint, token=token, raw=True)
print(f"--- MIT Token  -> [HTTP {st}]")
print(f"    {str(body)[:200]}")
st2, _ = call("GET", endpoint, raw=True)
print(f"--- OHNE Token -> [HTTP {st2}]  (erwartet 401/403)")

ok = (state == "FINALIZED") and st == 200 and st2 in (401, 403)
print("\n==== ERGEBNIS:", "GRUEN" if ok else "ROT", "====")
