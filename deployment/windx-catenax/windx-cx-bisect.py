#!/usr/bin/env python3
"""Bisect: welcher Teil der Policy laesst PolicyEquality scheitern?

Der Consumer prueft (EDC 0.17.0, ContractValidationServiceImpl):
    policyEquality.test(agreement.getPolicy().withTarget(offer.getAssetId()), offer.getPolicy())
PolicyEquality serialisiert beide Policies nach JSON und entfernt NUR auf oberster Ebene
"@type", "assignee", "target". Verglichen werden damit:
    permissions, prohibitions, obligations, profiles, extensibleProperties, inheritsFrom, assigner

Wir isolieren, woran es liegt, indem wir ein zweites Asset mit MINIMALER Policy (leeres Set)
anlegen und beide Assets je mit und ohne assigner verhandeln.

  minimal + ok   -> Problem steckt in Permission/Constraint
  minimal + fail -> Problem steckt in assigner / profiles

Laeuft IM Cluster. Nur Standardbibliothek.
"""
import json
import time
import urllib.error
import urllib.request

PROV = "http://windx-provider-tractusx-connector-controlplane:8081/management"
CONS = "http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP = "http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID = "did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}
HEALTH = "http://windx-provider-tractusx-connector-controlplane:8080/api/check/health"


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Api-Key", "password")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            p = r.read().decode()
            return r.status, (json.loads(p) if p else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


print("==== Setup: minimales Asset + leere Policy ====")
st, _ = call("POST", f"{PROV}/v3/assets", {
    "@context": VOCAB, "@id": "windx-min-asset",
    "properties": {"name": "minimal"},
    "dataAddress": {"type": "HttpData", "baseUrl": HEALTH}})
print(f"  asset      -> HTTP {st}")

# Leeres Set: keine permissions/prohibitions/obligations -> erlaubt alles
st, o = call("POST", f"{PROV}/v3/policydefinitions", {
    "@context": [{"@vocab": "https://w3id.org/edc/v0.0.1/ns/",
                  "odrl": "http://www.w3.org/ns/odrl/2/"}],
    "@id": "windx-min-policy", "policy": {"@type": "odrl:Set"}})
print(f"  policy     -> HTTP {st}" + ("" if st in (200, 409) else f"  {o}"))

st, o = call("POST", f"{PROV}/v3/contractdefinitions", {
    "@context": VOCAB, "@id": "windx-min-cd",
    "accessPolicyId": "windx-min-policy", "contractPolicyId": "windx-min-policy",
    "assetsSelector": {"@type": "Criterion",
                       "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
                       "operator": "=", "operandRight": "windx-min-asset"}})
print(f"  contractdef-> HTTP {st}" + ("" if st in (200, 409) else f"  {o}"))


def negotiate(asset, assigner):
    """Katalog -> Offer 1:1 zurueckschicken -> Endzustand der Negotiation melden."""
    st, cat = call("POST", f"{CONS}/v3/catalog/request", {
        "@context": VOCAB, "counterPartyAddress": PROV_DSP,
        "counterPartyId": PROV_DID, "protocol": "dataspace-protocol-http"})
    if st != 200:
        return f"Katalog HTTP {st}", None
    dss = cat.get("dcat:dataset") or cat.get("dataset") or []
    if isinstance(dss, dict):
        dss = [dss]
    ds = next((d for d in dss if d.get("@id") == asset), None)
    if not ds:
        return "Dataset fehlt", None
    offer = ds.get("odrl:hasPolicy") or ds.get("hasPolicy")
    if isinstance(offer, list):
        offer = offer[0]

    policy = dict(offer)
    for k in ("odrl:prohibition", "odrl:obligation"):
        if k in policy and not policy[k]:
            del policy[k]
    policy["@type"] = "odrl:Offer"
    policy["odrl:target"] = {"@id": asset}
    policy["odrl:assigner"] = {"@id": assigner}

    ctx = cat.get("@context", {})
    req_ctx = {**ctx, **VOCAB} if isinstance(ctx, dict) else [ctx, VOCAB]

    st, res = call("POST", f"{CONS}/v3/contractnegotiations", {
        "@context": req_ctx, "@type": "ContractRequest",
        "counterPartyAddress": PROV_DSP, "counterPartyId": PROV_DID,
        "protocol": "dataspace-protocol-http", "policy": policy})
    if st not in (200, 201):
        return f"initiate HTTP {st}: {str(res)[:160]}", None
    nid = res.get("@id")
    for _ in range(14):
        time.sleep(3)
        st, n = call("GET", f"{CONS}/v3/contractnegotiations/{nid}")
        state = n.get("state") if isinstance(n, dict) else None
        if state in ("FINALIZED", "TERMINATED", "VERIFIED"):
            return state, (n.get("errorDetail") or "")[:170]
    return "haengt in " + str(state), None


ASSIGNERS = {"DID": PROV_DID, "BPN": "BPNL00000003AVTH"}

for asset in ("windx-min-asset", "windx-cx-asset-1"):
    for name, val in ASSIGNERS.items():
        state, err = negotiate(asset, val)
        print(f"  {asset:18s} assigner={name:4s} -> {state}")
        if err:
            print(f"      {err}")
