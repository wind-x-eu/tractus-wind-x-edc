#!/usr/bin/env python3
#################################################################################
#  Copyright (c) 2026 Contributors to the Eclipse Foundation
#
#  See the NOTICE file(s) distributed with this work for additional
#  information regarding copyright ownership.
#
#  This program and the accompanying materials are made available under the
#  terms of the Apache License, Version 2.0 which is available at
#  https://www.apache.org/licenses/LICENSE-2.0.
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#  License for the specific language governing permissions and limitations
#  under the License.
#
#  SPDX-License-Identifier: Apache-2.0
#################################################################################
"""Consumer-Agent fuer den Wind-X TCP-Tunnel (TcpTunnel-PULL).

Stellt einen lokalen TCP-Port bereit. Fuer jede eingehende Verbindung holt der Agent
den aktuellen EDR beim eigenen Connector (Management-API, ``auto_refresh=true``),
oeffnet damit eine WebSocket-Verbindung zum Tunnel-Endpoint des Providers und
kopiert die Bytes in beide Richtungen. Weil der EDR pro Verbindung neu geholt wird,
ist das Token immer gueltig -- anders als bei einem fest eingetragenen Header.

Beispiel (OPC UA ueber den Tunnel auf localhost:4840)::

    export WINDX_MGMT_API_KEY=...            # API-Key der eigenen Management-API
    python3 windx_tunnel_agent.py \\
        --listen 127.0.0.1:4840 \\
        --management-url https://alice-edc.example/management \\
        --transfer-id <transferProcessId>

Alternativ mit festem EDR (z.B. zum Testen): ``--endpoint wss://... --token ...``.
"""

import argparse
import asyncio
import json
import logging
import os
import signal
import sys
import urllib.request

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus

LOG = logging.getLogger("windx-tunnel-agent")
CHUNK = 16 * 1024


class EdrSource:
    """Liefert (endpoint, token) -- entweder fest oder pro Verbindung frisch vom Connector."""

    def __init__(self, args):
        self.endpoint = args.endpoint
        self.token = args.token
        self.management_url = args.management_url.rstrip("/") if args.management_url else None
        self.transfer_id = args.transfer_id
        self.api_key = os.environ.get(args.api_key_env)
        if self.management_url and not self.api_key:
            raise SystemExit("API-Key fehlt: Umgebungsvariable %s setzen" % args.api_key_env)

    async def get(self):
        if not self.management_url:
            return self.endpoint, self.token
        return await asyncio.get_running_loop().run_in_executor(None, self._fetch)

    def _fetch(self):
        url = "%s/v3/edrs/%s/dataaddress?auto_refresh=true" % (self.management_url, self.transfer_id)
        request = urllib.request.Request(url, headers={"x-api-key": self.api_key})
        with urllib.request.urlopen(request, timeout=15) as response:
            edr = json.load(response)
        endpoint = edr.get("endpoint") or edr.get("https://w3id.org/edc/v0.0.1/ns/endpoint")
        token = edr.get("authorization") or edr.get("https://w3id.org/edc/v0.0.1/ns/authorization")
        if not endpoint or not token:
            raise RuntimeError("EDR ohne endpoint/authorization fuer Transfer %s" % self.transfer_id)
        return endpoint, token


async def pump_tcp_to_ws(reader, ws):
    while True:
        data = await reader.read(CHUNK)
        if not data:
            await ws.close(1000, "client closed")
            return
        await ws.send(data)


async def pump_ws_to_tcp(ws, writer):
    async for message in ws:
        if isinstance(message, str):
            message = message.encode()
        writer.write(message)
        await writer.drain()


async def handle(reader, writer, edr_source):
    peer = writer.get_extra_info("peername")
    try:
        endpoint, token = await edr_source.get()
    except Exception as e:  # noqa: BLE001 - jeder Fehler beendet nur diese Verbindung
        LOG.error("EDR nicht verfuegbar (%s) -> Verbindung von %s abgelehnt", e, peer)
        writer.close()
        return

    try:
        async with connect(endpoint, additional_headers={"Authorization": token},
                           max_size=None, open_timeout=15, ping_interval=None) as ws:
            LOG.info("Tunnel offen: %s -> %s", peer, endpoint)
            tasks = [asyncio.create_task(pump_tcp_to_ws(reader, ws)),
                     asyncio.create_task(pump_ws_to_tcp(ws, writer))]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                if task.exception() and not isinstance(task.exception(), ConnectionClosed):
                    LOG.warning("Tunnel-Fehler: %s", task.exception())
            if ws.close_code not in (None, 1000):
                LOG.warning("Tunnel vom Provider geschlossen: %s %s", ws.close_code, ws.close_reason)
            else:
                LOG.info("Tunnel geschlossen: %s", peer)
    except InvalidStatus as e:
        LOG.error("Provider lehnt Tunnel ab: HTTP %s (Transfer beendet oder Token ungueltig?)",
                  e.response.status_code)
    except (OSError, ConnectionClosed) as e:
        LOG.error("Tunnel-Verbindung fehlgeschlagen: %s", e)
    finally:
        writer.close()


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--listen", default="127.0.0.1:4840", help="lokale Adresse host:port")
    parser.add_argument("--management-url", help="Management-API des eigenen Connectors, z.B. https://.../management")
    parser.add_argument("--transfer-id", help="ID des TcpTunnel-PULL-Transfers")
    parser.add_argument("--api-key-env", default="WINDX_MGMT_API_KEY",
                        help="Umgebungsvariable mit dem Management-API-Key")
    parser.add_argument("--endpoint", help="fester Tunnel-Endpoint (statt Management-API)")
    parser.add_argument("--token", help="festes EDR-Token (statt Management-API)")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    if bool(args.management_url) == bool(args.endpoint):
        parser.error("entweder --management-url/--transfer-id oder --endpoint/--token angeben")
    if args.management_url and not args.transfer_id:
        parser.error("--transfer-id fehlt")
    if args.endpoint and not args.token:
        parser.error("--token fehlt")
    return args


async def main(argv):
    args = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    edr_source = EdrSource(args)
    host, port = args.listen.rsplit(":", 1)
    server = await asyncio.start_server(lambda r, w: handle(r, w, edr_source), host, int(port))
    LOG.info("Lausche auf %s", args.listen)
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    async with server:
        await stop.wait()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
