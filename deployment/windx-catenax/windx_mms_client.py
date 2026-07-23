#!/usr/bin/env python3
"""Minimaler IEC-61850/MMS-Client (Python-stdlib) -- gemeinsame Basis von Dashboard und Forwarding.

Portiert aus dem Java-Client des Repos (`AasIecConnector2` + `MMSClient` in
edc-extensions/windx/windx-mms): statischer COTP-Connect und Associate-Request als
Capture-Templates, BER-kodierte Requests, Tag-Scanner-Decoder.

Zwei Operationen:
    lese(domain, item)      MMS Read     -- ein Datenpunkt als String
    liste_variablen(domain) GetNameList  -- alle Named Variables einer Domain (Logical Device)

Bewusst OHNE externe Abhaengigkeiten, damit beide Dienste als ConfigMap in python:3.12-alpine
laufen koennen (kein Image-Build). Wird von windx-cx-dashboard.py und windx-forwarding.py
per ConfigMap in dasselbe Verzeichnis gelegt und importiert.

DEFENSIV: Der Decoder wirft nie. Der iecsim bedient parallel Data-Plane, Web-UI und uns; dabei
kommen Fremdpakete und Timeouts. Ein geworfener Fehler wuerde die Verbindung abreissen lassen.
"""
import socket
import struct
import threading

# Capture-Templates (identisch zum Java-Client)
COTP_CONNECT = bytes.fromhex("0300001611e00000000800c0010ac2020001c1020000")
ASSOCIATE = bytes.fromhex(
    "030000bb02f0800db20506130100160102140200023302000134020001c19c318199"
    "a003800101a28191810400000001820400000001a423300f0201010604520100013004"
    "060251013010020103060528ca220201300406025101615e305c020101a0576055a107"
    "060528ca220203a20706052901876701a30302010ca606060429018767a70302010cbe"
    "2f282d020103a028a826800300fde881010a82010a830105a416800101810305f100"
    "820c03ee1c00000408000079ef18")

# MMS-Fehlermarkierung: DataAccessError (Tag 0x80) ist KEIN Messwert. Der BIBA-Dienst liefert
# dafuer "ERROR code=<n>", und genau darauf prueft die UI (LiveSensorService.toNumber verwirft
# alles, was /error/i matcht). Ein roher Hexwert wie "0x0a" wuerde dort als Zahl 0 im Graphen
# landen -- deshalb dasselbe Textformat verwenden.
FEHLER_PREFIX = "ERROR code="


def _ber(tag, content):
    """BER-TLV mit kurzer/langer Laengenform."""
    n = len(content)
    if n < 0x80:
        lb = bytes([n])
    elif n < 0x100:
        lb = bytes([0x81, n])
    else:
        lb = bytes([0x82, n >> 8, n & 0xFF])
    return bytes([tag]) + lb + content


def _ber_len(d, i):
    """Laenge ab i. Gibt (None, 0) zurueck statt zu werfen, wenn das Paket zu kurz ist."""
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


# ---------------------------------------------------------------- Requests
def _read_request(domain, item, invoke):
    """MMS Read auf domainSpecific(domain, item)."""
    ds = _ber(0x1A, domain.encode()) + _ber(0x1A, item.encode())
    va = _ber(0xA1, _ber(0xA0, _ber(0x30, _ber(0xA0, _ber(0xA1, ds)))))
    rr = _ber(0xA4, b"\x80\x01\x01" + va)
    csp = _ber(0xA0, bytes([0x02, 0x01, invoke]) + rr)
    mms = _ber(0x61, _ber(0x30, b"\x02\x01\x03" + _ber(0xA0, csp)))
    return _tpkt(mms)


def _namelist_request(domain, invoke, weiter_ab=None):
    """MMS GetNameList: alle Named Variables einer Domain.

    Abweichung vom Java-Client, der nur die Domains listet (objectClass=domain(9),
    scope=vmdSpecific): hier objectClass=namedVariable(0) und scope=domainSpecific(<domain>),
    denn gebraucht werden die Variablen INNERHALB des Logical Device.
    `weiter_ab` setzt continueAfter [3] -- der Server antwortet bei vielen Namen
    haeppchenweise und meldet moreFollows.
    """
    object_class = _ber(0xA0, b"\x80\x01\x00")                       # namedVariable = 0
    object_scope = _ber(0xA1, _ber(0x81, domain.encode()))           # domainSpecific
    inhalt = object_class + object_scope
    if weiter_ab:
        inhalt += _ber(0x83, weiter_ab.encode())                     # continueAfter
    get_name_list = _ber(0xA1, inhalt)
    inner = _ber(0xA0, bytes([0x02, 0x01, invoke]) + get_name_list)
    outer = _ber(0xA0, inner)
    mms = _ber(0x61, _ber(0x30, b"\x02\x01\x03" + outer))
    return _tpkt(mms)


def _tpkt(mms_pdu):
    """COTP/Session/Presentation-Rahmen + TPKT-Header um eine MMS-PDU."""
    payload = b"\x02\xf0\x80\x01\x00\x01\x00" + mms_pdu
    tlen = 4 + len(payload)
    return bytes([0x03, 0x00, (tlen >> 8) & 0xFF, tlen & 0xFF]) + payload


# ---------------------------------------------------------------- Decoder
def _wert(tag, d, o, ln):
    if o + ln > len(d):
        return None
    if tag == 0x80:                          # DataAccessError -- kein Messwert
        code = d[o] if ln else "?"
        return f"{FEHLER_PREFIX}{code}"
    if tag == 0x83:                          # boolean
        return "TRUE" if (ln and d[o]) else "FALSE"
    if tag == 0x87 and ln >= 5:              # float (1 Byte Exponentbreite + IEEE 754)
        return repr(struct.unpack(">f", d[o + 1:o + 5])[0])
    if tag in (0x85, 0x86):                  # integer / unsigned
        return str(int.from_bytes(d[o:o + ln], "big", signed=(tag == 0x85)))
    return "0x" + d[o:o + ln].hex()


def dekodiere_read(d):
    """Wert aus einer Read-Response, oder None wenn das Paket etwas anderes ist."""
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


def dekodiere_namelist(d):
    """(Namen, moreFollows) aus einer GetNameList-Response.

    Struktur: confirmedResponse … getNameList [1]=0xA1 { listOfIdentifier [0]=0xA0 {
    VisibleString(0x1A)* }, moreFollows [1]=0x81 BOOLEAN }.
    Gesucht wird die aeusserste 0xA0-Liste NACH dem 0xA1-Response-Tag; darin liegen die Namen.
    """
    namen = []
    more = False
    i = d.find(b"\xa1")
    while i >= 0:
        j = i + 1
        ln, ll = _ber_len(d, j)
        if ll == 0:
            break
        j += ll
        ende = min(j + (ln or 0), len(d))
        # innerhalb: die Liste der Identifier
        if j < len(d) and d[j] == 0xA0:
            k = j + 1
            l2, ll2 = _ber_len(d, k)
            if ll2:
                k += ll2
                list_ende = min(k + (l2 or 0), len(d))
                while k < list_ende:
                    tag = d[k]
                    k += 1
                    l3, ll3 = _ber_len(d, k)
                    if ll3 == 0:
                        break
                    k += ll3
                    if tag == 0x1A and k + l3 <= len(d):
                        namen.append(d[k:k + l3].decode("ascii", errors="replace"))
                    k += l3
                # moreFollows direkt hinter der Liste
                if k < ende and d[k] == 0x81:
                    l4, ll4 = _ber_len(d, k + 1)
                    if ll4 and k + 1 + ll4 < len(d):
                        more = bool(d[k + 1 + ll4])
                if namen:
                    break
        i = d.find(b"\xa1", i + 1)
    return namen, more


# ---------------------------------------------------------------- Verbindung
class MmsClient:
    """Eine Verbindung, serialisierte Requests, Reconnect bei Fehlern."""

    def __init__(self, host, port=102, timeout=12):
        self.host, self.port, self.timeout = host, port, timeout
        self._sock = None
        self._lock = threading.Lock()
        self._invoke = 0x20

    # -- intern --------------------------------------------------
    def _connect(self):
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._sock.settimeout(self.timeout)
        self._sock.sendall(COTP_CONNECT)
        antwort = self._frame()          # COTP-Handshake ist immer ein einzelnes Frame
        if len(antwort) < 6 or (antwort[5] & 0xF0) != 0xD0:
            raise IOError("kein COTP Connection Confirm")
        self._sock.sendall(ASSOCIATE)
        self._message()

    def _frame(self):
        kopf = self._exakt(4)                # TPKT: Version, Reserviert, Laenge (2 Byte)
        ln = (kopf[2] << 8) | kopf[3]
        if ln < 4 or ln > 65535:
            raise IOError(f"unplausible TPKT-Laenge {ln}")
        return kopf + self._exakt(ln - 4)

    def _message(self, max_fragmente=64):
        """Vollstaendige MMS-Antwort: COTP-Fragmente sammeln, bis EOT gesetzt ist.

        Grosse Antworten (GetNameList!) verteilt COTP auf mehrere TPKT-Frames. Im DT-TPDU
        traegt das Byte hinter dem TPDU-Code das EOT-Flag (0x80); ist es nicht gesetzt, folgt
        ein weiteres Fragment. Wer nur das erste liest, bekommt einen abgeschnittenen
        BER-Baum -- die Namensliste endet dann willkuerlich mitten im Alphabet und das
        abschliessende moreFollows fehlt.
        """
        daten = b""
        for _ in range(max_fragmente):
            frame = self._frame()
            if len(frame) < 6:
                return daten + frame
            li = frame[4]                    # COTP Length Indicator
            eot_index = 4 + li
            eot = frame[eot_index] & 0x80 if eot_index < len(frame) else 0x80
            daten += frame[5 + li:]          # Nutzdaten hinter dem COTP-Header
            if eot:
                return daten
        return daten

    def _exakt(self, n):
        teile = b""
        while len(teile) < n:
            t = self._sock.recv(n - len(teile))
            if not t:
                raise IOError("Verbindung beendet")
            teile += t
        return teile

    def _naechste_invoke(self):
        self._invoke = 0x20 if self._invoke >= 0xFF else self._invoke + 1
        return self._invoke

    def _reset(self):
        try:
            if self._sock:
                self._sock.close()
        except Exception:                                     # noqa: BLE001
            pass
        self._sock = None

    # -- oeffentlich ---------------------------------------------
    def lese(self, domain, item):
        """Einen Datenpunkt lesen. Wirft bei Verbindungsfehlern; MMS-Fehler kommen als Text."""
        with self._lock:
            if self._sock is None:
                self._connect()
            try:
                self._sock.sendall(_read_request(domain, item, self._naechste_invoke()))
                for _ in range(3):
                    wert = dekodiere_read(self._message())
                    if wert is not None:
                        return wert
                raise IOError("keine Read-Response")
            except Exception:
                self._reset()
                raise

    def liste_variablen(self, domain, max_runden=12):
        """Alle Named Variables eines Logical Device (GetNameList, mit Fortsetzung)."""
        with self._lock:
            if self._sock is None:
                self._connect()
            try:
                alle = []
                weiter_ab = None
                for _ in range(max_runden):
                    self._sock.sendall(
                        _namelist_request(domain, self._naechste_invoke(), weiter_ab))
                    namen, more = [], False
                    for _ in range(3):
                        namen, more = dekodiere_namelist(self._message())
                        if namen:
                            break
                    if not namen:
                        break
                    alle.extend(namen)
                    if not more:
                        break
                    weiter_ab = namen[-1]
                return alle
            except Exception:
                self._reset()
                raise
