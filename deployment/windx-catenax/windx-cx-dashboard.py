#!/usr/bin/env python3
"""Wind-X Demonstrator -- Backend (laeuft als Pod im Cluster, ns windx).

Zwei-Rollen-Pipeline nach dem Vorbild des Wind-X-Dashboards:
  Betreiber:     Live-Preview der Anlagendaten (DIREKTPFAD, nicht ueber den Datenraum)
                 + Freigabe/Zurueckziehen des Angebots im Datenraum.
  Dienstleister: Freigaben einsehen (Katalog) -> Verbinden (Negotiation + Transfer)
                 -> Daten abrufen (Mms-PULL, ~10 s pro Aggregation).

DIREKTPFAD: eigener minimaler IEC-61850/MMS-Client, portiert aus dem Java-Client
(AasIecConnector2/MMSClient in edc-extensions/windx/windx-mms): statischer COTP-Connect
und Associate-Request (Capture-Templates), BER-kodierter Read-Request, Tag-Scanner-Decoder.
Nur Standardbibliothek -- kein pip, kein Framework.

EDC-Kette wie in windx-cx-demo.py verifiziert (alle Fallen dokumentiert in
docs/windx/demonstrator-catenax-plan.md): BPN-Assigner, Zwei-Schritt-Transfer, EDR-Refresh.
"""
import json
import struct
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- Konfiguration
MMS_HOST, MMS_PORT = "iecsim.aas.svc.cluster.local", 102
MMS_DOMAIN = "WPPSLD2"
SENSOREN = {                       # Anzeigename -> MMS-ItemId
    "wind":   "WNAC1$MX$WdSpd$mag$f",
    "leistung": "WGEN1$MX$W$mag$f",
    "rotor":  "WROT1$MX$RotSpd$mag$f",
    "oel":    "WGBX1$MX$OilTmp$mag$f",
    "laeuft": "WTUR1$ST$Running$stVal",
}

PROV_MGMT = "http://windx-provider-tractusx-connector-controlplane:8081/management"
CONS_MGMT = "http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP = "http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID = "did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
PROV_BPN = "BPNL00000003AVTH"
ASSET = "windx-mms-asset-1"
CDEF = "windx-cx-cd-mms"
POLICY = "windx-membership-policy"
VOCAB = {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}
HTML_DATEI = "/app/dashboard.html"

# ---------------------------------------------------------------- Protokoll
LOG = deque(maxlen=400)
LOG_LOCK = threading.Lock()


def log(text, art="info"):
    with LOG_LOCK:
        LOG.append({"t": time.strftime("%H:%M:%S"), "text": text, "art": art})


# ================================================================ MMS-Direktpfad
# Portiert aus AasIecConnector2.java (Capture-Templates) + MMSClient.decodeDataAndReturnValue.
COTP_CONNECT = bytes.fromhex("0300001611e00000000800c0010ac2020001c1020000")
ASSOCIATE = bytes.fromhex(
    "030000bb02f0800db20506130100160102140200023302000134020001c19c318199"
    "a003800101a28191810400000001820400000001a423300f0201010604520100013004"
    "060251013010020103060528ca220201300406025101615e305c020101a0576055a107"
    "060528ca220203a20706052901876701a30302010ca606060429018767a70302010cbe"
    "2f282d020103a028a826800300fde881010a82010a830105a416800101810305f100"
    "820c03ee1c00000408000079ef18")


def _ber(tag, content):
    n = len(content)
    if n < 0x80:
        lb = bytes([n])
    elif n < 0x100:
        lb = bytes([0x81, n])
    else:
        lb = bytes([0x82, n >> 8, n & 0xFF])
    return bytes([tag]) + lb + content


def _read_request(domain, item, invoke):
    ds = _ber(0x1A, domain.encode()) + _ber(0x1A, item.encode())
    va = _ber(0xA1, _ber(0xA0, _ber(0x30, _ber(0xA0, _ber(0xA1, ds)))))
    rr = _ber(0xA4, b"\x80\x01\x01" + va)
    csp = _ber(0xA0, bytes([0x02, 0x01, invoke]) + rr)
    mms = _ber(0x61, _ber(0x30, b"\x02\x01\x03" + _ber(0xA0, csp)))
    payload = b"\x02\xf0\x80\x01\x00\x01\x00" + mms
    tlen = 4 + len(payload)
    return bytes([0x03, 0x00, tlen >> 8, tlen & 0xFF]) + payload


def _ber_len(d, i):
    """BER-Laenge ab i. Gibt (None, 0) zurueck, statt bei kurzen Paketen zu werfen."""
    if i >= len(d):
        return None, 0
    b = d[i]
    if b & 0x80 == 0:
        return b, 1
    n = b & 0x7F
    if n == 0 or i + n >= len(d):
        return None, 0
    ln = 0
    for j in range(n):
        ln = (ln << 8) | d[i + 1 + j]
    return ln, 1 + n


def _wert(tag, d, o, ln):
    if o + ln > len(d):
        return None
    if tag == 0x83:                          # boolean
        return "TRUE" if (ln and d[o]) else "FALSE"
    if tag == 0x87 and ln >= 5:              # float (1 Byte Exponentbreite + IEEE 754)
        return repr(struct.unpack(">f", d[o + 1:o + 5])[0])
    if tag in (0x85, 0x86):                  # integer / unsigned
        return str(int.from_bytes(d[o:o + ln], "big", signed=(tag == 0x85)))
    return "0x" + d[o:o + ln].hex()


def _dekodiere(d):
    """Wert aus einer MMS-Read-Response. Gibt None zurueck, wenn das Paket etwas anderes ist
    (z. B. Associate-Response oder unaufgeforderter Report) -- der Aufrufer liest dann weiter.
    Defensiv wie der Java-Decoder: NIEMALS werfen, sonst reisst ein Fremdpaket den Direktpfad ab."""
    i = d.find(b"\xa4")
    if i < 0:
        return None
    i += 1
    _, ll = _ber_len(d, i)
    if ll == 0:
        return None
    i += ll
    if i >= len(d) or d[i] != 0xA1:
        return None
    i += 1
    l2, ll2 = _ber_len(d, i)
    if ll2 == 0:
        return None
    ende = min(i + ll2 + l2, len(d))
    i += ll2
    werte = []
    while i < ende:
        tag = d[i]
        i += 1
        ln, ll3 = _ber_len(d, i)
        if ll3 == 0:
            break
        i += ll3
        w = _wert(tag, d, i, ln)
        if w is None:
            break
        werte.append(w)
        i += ln
    return werte[0] if len(werte) == 1 else ("\n".join(werte) if werte else None)


class MmsDirekt:
    """Eine Verbindung, serialisierte Reads, Reconnect bei Fehlern."""

    def __init__(self, host, port):
        self.host, self.port = host, port
        self._sock = None
        self._lock = threading.Lock()
        self._invoke = 0x20

    def _connect(self):
        import socket
        self._sock = socket.create_connection((self.host, self.port), timeout=8)
        self._sock.settimeout(12)            # grosszuegig: iecsim bedient parallel Data-Plane + Web-UI
        self._sock.sendall(COTP_CONNECT)
        antwort = self._frame()
        if len(antwort) < 6 or (antwort[5] & 0xF0) != 0xD0:
            raise IOError("kein COTP Connection Confirm")
        self._sock.sendall(ASSOCIATE)
        self._frame()                        # Associate-Response (wird nicht geprueft, wie im Java-Client)

    def _frame(self):
        kopf = self._exakt(4)                # TPKT: Version, Reserviert, Laenge (2 Byte)
        ln = (kopf[2] << 8) | kopf[3]
        if ln < 4 or ln > 65535:
            raise IOError(f"unplausible TPKT-Laenge {ln}")
        return kopf + self._exakt(ln - 4)

    def _exakt(self, n):
        teile = b""
        while len(teile) < n:
            t = self._sock.recv(n - len(teile))
            if not t:
                raise IOError("Verbindung beendet")
            teile += t
        return teile

    def lese(self, domain, item):
        with self._lock:
            if self._sock is None:
                self._connect()
            try:
                self._invoke = 0x20 if self._invoke >= 0xFF else self._invoke + 1
                self._sock.sendall(_read_request(domain, item, self._invoke))
                for _ in range(3):
                    wert = _dekodiere(self._frame())
                    if wert is not None:
                        return wert
                raise IOError("keine Read-Response")
            except Exception:
                try:
                    self._sock.close()
                except Exception:
                    pass
                self._sock = None
                raise


# Preview-Puffer: der Hintergrund-Thread pollt die Anlage direkt (Betreiber-Sicht).
PREVIEW = deque(maxlen=300)
PREVIEW_STATUS = {"ok": False}
_mms = MmsDirekt(MMS_HOST, MMS_PORT)


def preview_schleife():
    """Pollt die Anlage direkt. Einzelne Aussetzer werden stillschweigend weggesteckt:
    iecsim bedient parallel die Data-Plane (10 Polls je Datenraum-Abruf) und die eigene Web-UI,
    da kommt es zu vereinzelten Zeitueberschreitungen. Erst nach MEHREREN Fehlschlaegen in Folge
    gilt der Pfad als gestoert -- sonst entstuende in der Vorfuehrung Fehlerrauschen."""
    TOLERANZ = 3
    fehler_serie = 0
    gemeldet = False
    while True:
        try:
            punkt = {"t": time.strftime("%H:%M:%S")}
            for name, item in SENSOREN.items():
                punkt[name] = _mms.lese(MMS_DOMAIN, item)
            PREVIEW.append(punkt)
            fehler_serie = 0
            if not PREVIEW_STATUS["ok"]:
                PREVIEW_STATUS["ok"] = True
                if gemeldet:
                    log("Direktpfad zur Anlage wiederhergestellt", "gut")
                else:
                    log(f"Direktpfad zur Anlage aufgebaut ({MMS_HOST}:{MMS_PORT}, IEC 61850/MMS)", "gut")
                gemeldet = False
        except Exception as e:                                # noqa: BLE001
            fehler_serie += 1
            if fehler_serie >= TOLERANZ:
                if not gemeldet:
                    log(f"Direktpfad gestoert: {str(e)[:120]}", "fehler")
                    gemeldet = True
                PREVIEW_STATUS["ok"] = False
            time.sleep(2)
        time.sleep(1.5)


# ================================================================ EDC-Kette
def edc(methode, url, body=None, token=None, roh=False, timeout=30):
    daten = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=daten, method=methode)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    else:
        req.add_header("X-Api-Key", "password")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            inhalt = r.read().decode()
            return r.status, (inhalt if roh else (json.loads(inhalt) if inhalt else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)[:200]


ZUSTAND = {"tpid": None, "agreement": None}


def api_status():
    st, _ = edc("GET", f"{PROV_MGMT}/v3/contractdefinitions/{CDEF}")
    return {"freigegeben": st == 200, "verbunden": ZUSTAND["tpid"] is not None,
            "agreement": ZUSTAND["agreement"], "edc": "tractus-x", "direktpfad": PREVIEW_STATUS["ok"]}


def api_preview():
    return {"ok": PREVIEW_STATUS["ok"], "punkte": list(PREVIEW)[-180:]}


def api_freigabe():
    """Asset + Policy + ContractDefinition anlegen (idempotent) -- das Angebot im Datenraum."""
    sensoren = ",".join(SENSOREN[k] for k in ("leistung", "wind", "rotor", "oel", "laeuft"))
    st1, _ = edc("POST", f"{PROV_MGMT}/v3/assets", {
        "@context": VOCAB, "@id": ASSET,
        "properties": {"name": "Wind-X Turbinen-Telemetrie (IEC 61850/MMS, iecsim)"},
        "dataAddress": {"type": "Mms", "mms:serverHost": MMS_HOST, "mms:serverPort": str(MMS_PORT),
                        "mms:logicalDevice": MMS_DOMAIN, "mms:sensors": sensoren,
                        "mms:updateInterval": "1000"}})
    st2, _ = edc("POST", f"{PROV_MGMT}/v3/policydefinitions", {
        "@context": [{"@vocab": "https://w3id.org/edc/v0.0.1/ns/",
                      "odrl": "http://www.w3.org/ns/odrl/2/"}],
        "@id": POLICY,
        "policy": {"@type": "odrl:Set", "permission": [{
            "action": "https://w3id.org/catenax/2025/9/policy/access",
            "constraint": {"@type": "LogicalConstraint", "and": [{
                "leftOperand": "https://w3id.org/catenax/2025/9/policy/Membership",
                "operator": "eq", "rightOperand": "active"}]}}]}})
    st3, o = edc("POST", f"{PROV_MGMT}/v3/contractdefinitions", {
        "@context": VOCAB, "@id": CDEF,
        "accessPolicyId": POLICY, "contractPolicyId": POLICY,
        "assetsSelector": {"@type": "Criterion",
                           "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
                           "operator": "=", "operandRight": ASSET}})
    if st3 in (200, 409):
        log("Betreiber: Telemetrie im Datenraum freigegeben (Policy: Membership eq active)", "gut")
        return {"ok": True}
    log(f"Freigabe fehlgeschlagen: HTTP {st3}", "fehler")
    return {"ok": False, "fehler": f"Asset {st1} / Policy {st2} / ContractDef {st3}: {str(o)[:200]}"}


def api_zurueckziehen():
    st, o = edc("DELETE", f"{PROV_MGMT}/v3/contractdefinitions/{CDEF}")
    if st in (200, 204, 404):
        log("Betreiber: Freigabe zurueckgezogen -- das Angebot verschwindet aus dem Katalog", "warn")
        return {"ok": True}
    return {"ok": False, "fehler": f"HTTP {st}: {str(o)[:200]}"}


def _katalog():
    st, kat = edc("POST", f"{CONS_MGMT}/v3/catalog/request", {
        "@context": VOCAB, "counterPartyAddress": PROV_DSP,
        "counterPartyId": PROV_DID, "protocol": "dataspace-protocol-http"})
    if st != 200:
        raise RuntimeError(f"Katalog HTTP {st}: {str(kat)[:200]}")
    ds = kat.get("dcat:dataset") or kat.get("dataset") or []
    return kat, ([ds] if isinstance(ds, dict) else ds)


def api_freigaben():
    """Dienstleister-Sicht: welche Mms-Angebote sind im Katalog sichtbar?"""
    try:
        _, datasets = _katalog()
    except RuntimeError as e:
        log(str(e), "fehler")
        return {"ok": False, "fehler": str(e)}
    eintraege = []
    for d in datasets:
        # NICHT ueber die Transferart filtern: 'Mms-PULL' steht in der Distribution JEDES Assets,
        # weil es eine Faehigkeit der Data-Plane ist, kein Merkmal des Datensatzes. Sonst tauchen
        # fremde Assets als vermeintliche Freigaben auf. Dieser Demonstrator verwaltet genau einen
        # Datensatz -- entsprechend gezielt danach suchen.
        if d.get("@id") != ASSET:
            continue
        eintraege.append({
            "id": d.get("@id"),
            "name": d.get("name") or (d.get("properties") or {}).get("name") or d.get("@id"),
            "anbieter": PROV_BPN,
            "policy": "Membership eq active",
            "transferarten": ["Mms-PULL", "HttpData-PUSH"],
        })
    log(f"Dienstleister: Katalog abgefragt -- {len(eintraege)} Freigabe(n) sichtbar (DCP-Nachweis ok)")
    return {"ok": True, "freigaben": eintraege}


def api_verbinden():
    """Negotiation (Membership-Pruefung) -> FINALIZED -> Transfer Mms-PULL -> STARTED."""
    try:
        kat, datasets = _katalog()
    except RuntimeError as e:
        log(str(e), "fehler")
        return {"ok": False, "fehler": str(e)}
    ds = next((d for d in datasets if d.get("@id") == ASSET), None)
    if not ds:
        log("Verbinden nicht moeglich: keine Freigabe im Katalog", "fehler")
        return {"ok": False, "fehler": "Keine Freigabe im Katalog -- hat der Betreiber freigegeben?"}
    offer = ds.get("odrl:hasPolicy") or ds.get("hasPolicy")
    if isinstance(offer, list):
        offer = offer[0]

    # Die dokumentierten Fallen: leere Rules raus, odrl:Offer, Target auf Policy-Ebene,
    # assigner = BPN (nicht die DID).
    policy = dict(offer)
    for k in ("odrl:prohibition", "odrl:obligation", "prohibition", "obligation"):
        if k in policy and not policy[k]:
            del policy[k]
    policy["@type"] = "odrl:Offer"
    policy["odrl:target"] = {"@id": ASSET}
    policy["odrl:assigner"] = {"@id": PROV_BPN}
    ctx = kat.get("@context", {})
    req_ctx = {**ctx, **VOCAB} if isinstance(ctx, dict) else [ctx, VOCAB]

    st, res = edc("POST", f"{CONS_MGMT}/v3/contractnegotiations", {
        "@context": req_ctx, "@type": "ContractRequest",
        "counterPartyAddress": PROV_DSP, "counterPartyId": PROV_DID,
        "protocol": "dataspace-protocol-http", "policy": policy})
    if st not in (200, 201):
        log(f"Negotiation abgelehnt: HTTP {st}", "fehler")
        return {"ok": False, "fehler": str(res)[:250]}
    nid = res.get("@id")
    log("Dienstleister: Vertragsverhandlung gestartet -- Provider prueft das MembershipCredential")

    agreement = None
    for _ in range(30):
        st, n = edc("GET", f"{CONS_MGMT}/v3/contractnegotiations/{nid}")
        zustand = n.get("state") if isinstance(n, dict) else None
        agreement = n.get("contractAgreementId") if isinstance(n, dict) else None
        if zustand == "FINALIZED":
            break
        if zustand == "TERMINATED":
            log("Negotiation TERMINATED", "fehler")
            return {"ok": False, "fehler": json.dumps(n)[:250]}
        time.sleep(2)
    if not agreement:
        return {"ok": False, "fehler": "kein Agreement (Timeout)"}
    log(f"Vertrag FINALIZED -- Agreement {agreement[:8]}...", "gut")

    st, tp = edc("POST", f"{CONS_MGMT}/v3/transferprocesses", {
        "@context": VOCAB, "@type": "TransferRequest",
        "counterPartyAddress": PROV_DSP, "protocol": "dataspace-protocol-http",
        "contractId": agreement, "assetId": ASSET,
        "transferType": "Mms-PULL", "dataDestination": {"type": "Mms"}})
    if st not in (200, 201):
        return {"ok": False, "fehler": str(tp)[:250]}
    tpid = tp.get("@id")
    for _ in range(30):
        st, t = edc("GET", f"{CONS_MGMT}/v3/transferprocesses/{tpid}")
        zustand = t.get("state") if isinstance(t, dict) else None
        if zustand == "STARTED":
            break
        if zustand == "TERMINATED":
            return {"ok": False, "fehler": json.dumps(t)[:250]}
        time.sleep(2)
    ZUSTAND["tpid"], ZUSTAND["agreement"] = tpid, agreement
    log("Transfer Mms-PULL STARTED -- Zugangsdaten (EDR) ausgestellt", "gut")
    return {"ok": True, "agreement": agreement, "tpid": tpid}


def _edr():
    st, da = edc("GET", f"{CONS_MGMT}/v3/edrs/{ZUSTAND['tpid']}/dataaddress")
    if st != 200:
        raise RuntimeError(f"EDR HTTP {st}")
    return da


def api_daten():
    """Ein Datenraum-Abruf: ~10 s (10 Polls der Data-Plane gegen die Anlage)."""
    if not ZUSTAND["tpid"]:
        return {"ok": False, "fehler": "nicht verbunden"}
    try:
        da = _edr()
    except RuntimeError as e:
        return {"ok": False, "fehler": str(e)}
    t0 = time.time()
    st, body = edc("GET", da["endpoint"], token=da["authorization"], roh=True, timeout=150)
    dauer = time.time() - t0
    if st != 200:
        log(f"Datenraum-Abruf fehlgeschlagen: HTTP {st}", "fehler")
        return {"ok": False, "fehler": f"HTTP {st}: {str(body)[:200]}"}
    agg = json.loads(body)
    arrays = agg.get("dataArrays", {})

    def werte(item):
        v = arrays.get(item, [])
        return v.get("values", []) if isinstance(v, dict) else v

    log(f"Datenraum-Abruf: {agg.get('messageCount')} Messungen in {dauer:.1f} s (HTTP 200)")
    return {"ok": True, "dauer": round(dauer, 1),
            "zeiten": agg.get("timestamps", []),
            "wind": werte(SENSOREN["wind"]), "leistung": werte(SENSOREN["leistung"]),
            "rotor": werte(SENSOREN["rotor"]), "oel": werte(SENSOREN["oel"]),
            "laeuft": werte(SENSOREN["laeuft"])}


def api_gegenprobe():
    if not ZUSTAND["tpid"]:
        return {"ok": False, "fehler": "nicht verbunden"}
    try:
        da = _edr()
    except RuntimeError as e:
        return {"ok": False, "fehler": str(e)}
    st, _ = edc("GET", da["endpoint"], roh=True)
    if st in (401, 403):
        log(f"Gegenprobe: gleicher Endpunkt OHNE Berechtigung -> HTTP {st} abgewiesen", "gut")
    else:
        log(f"Gegenprobe unerwartet: HTTP {st}", "fehler")
    return {"ok": True, "status": st}


def api_log():
    with LOG_LOCK:
        return {"zeilen": list(LOG)}


# ================================================================ HTTP-Server
ROUTEN = {
    ("GET", "/api/status"): api_status,
    ("GET", "/api/preview"): api_preview,
    ("GET", "/api/log"): api_log,
    ("POST", "/api/freigabe"): api_freigabe,
    ("POST", "/api/zurueckziehen"): api_zurueckziehen,
    ("GET", "/api/freigaben"): api_freigaben,
    ("POST", "/api/verbinden"): api_verbinden,
    ("GET", "/api/daten"): api_daten,
    ("POST", "/api/gegenprobe"): api_gegenprobe,
}


class Handler(BaseHTTPRequestHandler):
    def _antwort(self, code, inhalt, typ="application/json; charset=utf-8"):
        daten = inhalt if isinstance(inhalt, bytes) else json.dumps(inhalt).encode()
        self.send_response(code)
        self.send_header("Content-Type", typ)
        self.send_header("Content-Length", str(len(daten)))
        self.end_headers()
        self.wfile.write(daten)

    def _route(self, methode):
        pfad = self.path.split("?")[0]
        if methode == "GET" and pfad in ("/", "/index.html"):
            with open(HTML_DATEI, "rb") as f:
                return self._antwort(200, f.read(), "text/html; charset=utf-8")
        fn = ROUTEN.get((methode, pfad))
        if not fn:
            return self._antwort(404, {"fehler": "unbekannter Pfad"})
        try:
            return self._antwort(200, fn())
        except Exception as e:                                # noqa: BLE001
            return self._antwort(500, {"fehler": str(e)[:300]})

    def do_GET(self):                                         # noqa: N802
        self._route("GET")

    def do_POST(self):                                        # noqa: N802
        self._route("POST")

    def log_message(self, *args):                             # eigenes Protokoll statt stderr-Spam
        pass


if __name__ == "__main__":
    log("Demonstrator-Backend gestartet")
    threading.Thread(target=preview_schleife, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
