#!/usr/bin/env python3
"""Wind-X MMS-Forwarding-Service (Nachbildung, in-memory) -- Push-Ziel fuer `Mms-PUSH`.

Minimale, API-kompatible Nachbildung des BIBA `simple.mms.forwarding.service` (dessen
Quellcode/Image liegt in keinem unserer Repos): die Provider-Data-Plane POSTet jede
MMS-Aggregation an /log?signal=<sensor>, Leser (spaeter die Data-Explorer-UI) holen die
Nachrichten FIFO wieder ab. Bewusst Python-stdlib in python:3.12-alpine (Code kommt als
ConfigMap, kein Image-Build) -- gleiches Muster wie windx-cx-dashboard.py.

API (kompatibel zu dem, was die UI erwartet -- s. dataexplorer live-sensor.service.ts):
    POST /log?signal=<s>   Aggregation einreihen (Queue-Deckel 1000, aeltestes faellt raus)
    GET  /log?signal=<s>   aelteste Nachricht ausliefern UND entfernen; 204 wenn leer
    GET  /log/queues       {count, queues:[{signal, size, totalReceived, lastPushAt}]}
                           -- Huelle wie im Original; die UI liest `resp.queues`.
    GET  /health           Liveness/Readiness

NICHT nachgebildet (das Original kann mehr): /api/connections (Verbindungsmonitor) sowie der
Betreiber-Direktpfad /api/{sensors,register,getValue,unregister}. Im Tractus-X-Modus liefert der
Verbindungsmonitor dafuer nichts; die Live-Preview des Betreibers laeuft ueber unser eigenes
Dashboard-Backend (windx-cx-dashboard.py), nicht ueber diesen Dienst.

In-memory: ein Pod-Neustart leert die Queues -- fuer den Demonstrator ausreichend.
Liefert der Kollege das Original-Image, ist es ein Drop-in (gleiche API, gleicher Service-Name).
"""

import json
import os
import threading
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from windx_mms_client import MmsClient

PORT = 4000
QUEUE_CAP = 1000

# MMS-Server, aus dem die Sensorliste stammt. Wie beim Original bestimmt der Dienst die Adresse
# selbst -- das Frontend schickt sie bewusst nicht mit.
MMS_HOST = os.environ.get("MMS_HOST", "iecsim.aas.svc.cluster.local")
MMS_PORT = int(os.environ.get("MMS_PORT", "102"))
# Das Frontend sendet hart 'WPPS' (BIBA-Simulator); unserer heisst anders. Deshalb gilt die
# eigene Konfiguration, analog zur Server-Adresse.
MMS_LOGICAL_DEVICE = os.environ.get("MMS_LOGICAL_DEVICE", "WPPSLD2")

_mms = MmsClient(MMS_HOST, MMS_PORT)
_sensor_cache = {"namen": [], "stand": 0.0}
_sensor_lock = threading.Lock()
CACHE_SEKUNDEN = 300

_lock = threading.Lock()
_queues: dict[str, deque] = {}          # signal -> FIFO der Aggregations-Bodies (bytes)
_total: dict[str, int] = {}             # signal -> insgesamt empfangene Nachrichten
_last_push: dict[str, str] = {}         # signal -> ISO-Zeitstempel des letzten POST


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Klartextnamen fuer die Signalauswahl.
# Der MMS-Server liefert nur Referenzen wie "WGBX1$MX$OilTmp$mag$f". Das Original des Kollegen
# gibt zu jedem Sensor einen lesbaren Namen zurueck ("Bedeutung · Bereich"), den die UI als
# Label uebernimmt (sonst faellt sie auf eine abgeleitete Kurzform zurueck). Abgedeckt sind die
# Logical Nodes nach IEC 61400-25, die unser Simulator fuehrt.
# ---------------------------------------------------------------------------
BEREICHE = {
    "WTUR": "Turbine", "WROT": "Rotor", "WGEN": "Generator", "WNAC": "Gondel",
    "WGBX": "Getriebe", "WTRM": "Triebstrang", "WPIT": "Pitch", "WYAW": "Azimut",
    "WCNV": "Umrichter", "WTRF": "Transformator", "WAPC": "Wirkleistungsregelung",
    "WRPC": "Blindleistungsregelung", "WSLG": "Betriebsprotokoll", "WALG": "Alarme",
    "WMET": "Meteorologie", "MMXU": "Messwerte", "CSWI": "Schalter", "XCBR": "Leistungsschalter",
    "LLN0": "Logischer Knoten 0", "LPHD": "Geraet", "LCCS": "Kommunikation",
}
GROESSEN = {
    "W": "Wirkleistung", "TotW": "Wirkleistung gesamt", "VAr": "Blindleistung",
    "Hz": "Netzfrequenz", "TotPF": "Leistungsfaktor", "A": "Strom", "PhV": "Spannung",
    "WdSpd": "Windgeschwindigkeit", "WdDir": "Windrichtung", "EnvTmp": "Aussentemperatur",
    "RotSpd": "Rotordrehzahl", "Spd": "Drehzahl", "OilTmp": "Oeltemperatur",
    "OilPrs": "Oeldruck", "BrgTmp": "Lagertemperatur", "WdgTmp": "Wicklungstemperatur",
    "IntlTmp": "Innentemperatur", "VibRms": "Vibration (RMS)", "PtAng": "Pitchwinkel",
    "PtAng1": "Pitchwinkel Blatt 1", "YawAng": "Azimutwinkel", "Running": "In Betrieb",
    "GridCon": "Netzkopplung", "Wrn": "Warnung", "Flt": "Stoerung", "Alm": "Alarm",
    "EvtTxt": "Ereignistext", "Mod": "Betriebsart", "Beh": "Verhalten", "Health": "Zustand",
    "Loc": "Vor-Ort-Steuerung", "Pos": "Stellung", "Cond": "Zustand",
    "PhyHealth": "Geraetezustand", "TotWh": "Energieertrag",
    # weitere Groessen, die unser Simulator fuehrt
    "TotVAr": "Blindleistung gesamt", "TotVA": "Scheinleistung gesamt",
    "GbxOilTmp": "Getriebeoeltemperatur", "GbxOilLev": "Getriebeoelstand",
    "Dir": "Richtung", "Avl": "Verfuegbarkeit", "Evt": "Ereignis", "FltSev": "Stoerungsschwere",
    "DclV": "Zwischenkreisspannung", "DclA": "Zwischenkreisstrom",
    "DclTmp": "Zwischenkreistemperatur", "ClSt": "Kuehlungszustand",
    "CnvOpSt": "Umrichter-Betriebszustand", "BrkOpSt": "Bremsenzustand",
    "FilSt": "Filterzustand", "EEHealth": "Zustand (Betriebsmittel)",
    "EEName": "Betriebsmittelname", "BlkOpn": "Oeffnen gesperrt", "BlkCls": "Schliessen gesperrt",
    "CnvFail": "Stoerung Umrichter", "GenFail": "Stoerung Generator",
    "CnvTmpHi": "Uebertemperatur Umrichter",
    "CnvClStFail": "Stoerung Umrichterkuehlung", "GenClStFail": "Stoerung Generatorkuehlung",
}
# Statistik-Suffixe der 10-Minuten-Werte (IEC 61400-25 fuehrt sie je Groesse).
STATISTIK = (("Avg10m", "10-min-Mittel"), ("Max10m", "10-min-Max"),
             ("Min10m", "10-min-Min"), ("StdVar10m", "10-min-Streuung"))


def _klartext(ref: str) -> str:
    """"WGBX1$MX$OilTmp$mag$f" -> "Oeltemperatur · Getriebe"."""
    teile = ref.split("$")
    if not teile:
        return ref
    ln = teile[0]
    bereich = BEREICHE.get(ln[:4], "")
    # Das Data Object steht hinter dem Functional Constraint (MX/ST/CF/...).
    do = teile[2] if len(teile) > 2 else ln
    zusatz = ""
    for suffix, text in STATISTIK:
        if do.endswith(suffix):
            do, zusatz = do[: -len(suffix)], f" ({text})"
            break
    groesse = GROESSEN.get(do, do)
    if bereich and groesse:
        return f"{groesse}{zusatz} · {bereich}"
    return f"{groesse}{zusatz}" if groesse else ref


def _sensoren(erzwinge: bool = False):
    """Sensorliste per MMS GetNameList, mit kurzem Cache (die Abfrage kostet ~1 s)."""
    import time as _time
    with _sensor_lock:
        frisch = _time.time() - _sensor_cache["stand"] < CACHE_SEKUNDEN
        if _sensor_cache["namen"] and frisch and not erzwinge:
            return _sensor_cache["namen"]
    namen = _mms.liste_variablen(MMS_LOGICAL_DEVICE)
    with _sensor_lock:
        _sensor_cache["namen"] = namen
        _sensor_cache["stand"] = _time.time()
    return namen


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ---- Antwort-Helfer -------------------------------------------------
    def _send(self, status: int, body: bytes = b"", content_type: str = "application/json") -> None:
        self.send_response(status)
        if body:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _signal(self) -> str:
        query = parse_qs(urlparse(self.path).query)
        return (query.get("signal") or [""])[0]

    # ---- Routen ---------------------------------------------------------
    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler-API)
        path = urlparse(self.path).path
        if path == "/api/sensors":
            # Signalauswahl des Betreibers: alle Named Variables des Logical Device, live per
            # MMS GetNameList. Die UI filtert daraus die lesbaren Blaetter ($f/$stVal) und
            # nimmt `name` als Label. Body des Frontends (anlage) wird nicht gebraucht --
            # Server und Domain kommen aus der eigenen Konfiguration.
            laenge = int(self.headers.get("Content-Length") or 0)
            if laenge:
                self.rfile.read(laenge)
            try:
                namen = _sensoren()
            except Exception as e:                            # noqa: BLE001
                self._send(502, json.dumps(
                    {"error": f"MMS-Abfrage fehlgeschlagen: {str(e)[:150]}"}).encode())
                return
            sensoren = [{"id": n, "name": _klartext(n)} for n in namen]
            self._send(200, json.dumps({"sensors": sensoren}).encode("utf-8"))
            return
        if path != "/log":
            self._send(404, b'{"error":"unbekannter Pfad"}')
            return
        signal = self._signal()
        if not signal:
            self._send(400, b'{"error":"Query-Parameter signal fehlt"}')
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        with _lock:
            queue = _queues.setdefault(signal, deque(maxlen=QUEUE_CAP))
            queue.append(body)
            _total[signal] = _total.get(signal, 0) + 1
            _last_push[signal] = _now()
        self._send(200, b'{"ok":true}')

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self._send(200, b'{"status":"ok"}')
            return
        if path == "/log/queues":
            with _lock:
                infos = [
                    {"signal": s, "size": len(q), "totalReceived": _total.get(s, 0),
                     "lastPushAt": _last_push.get(s, "")}
                    for s, q in _queues.items()
                ]
            # Huelle {count, queues} wie im Original -- die UI liest `resp.queues`
            # (dataexplorer live-sensor.service.ts, LogQueuesResponse). Ein nacktes Array
            # laesst den Verbindungsmonitor stillschweigend leer.
            body = {"count": len(infos), "queues": infos}
            self._send(200, json.dumps(body).encode("utf-8"))
            return
        if path == "/log":
            signal = self._signal()
            if not signal:
                self._send(400, b'{"error":"Query-Parameter signal fehlt"}')
                return
            with _lock:
                queue = _queues.get(signal)
                message = queue.popleft() if queue else None
            if message is None:
                self._send(204)  # leer -- die UI wertet das als "Queue abgearbeitet"
            else:
                self._send(200, message)
            return
        self._send(404, b'{"error":"unbekannter Pfad"}')

    def log_message(self, fmt: str, *args) -> None:
        # Ein Log-Eintrag pro Request genuegt (POST-Frequenz ~1/s pro Transfer).
        print("[forwarding] %s -- %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    print(f"[forwarding] Wind-X MMS-Forwarding-Service (Nachbildung) auf :{PORT}", flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
