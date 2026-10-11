/********************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.edc.windx.tunnel;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Eine Tunnel-Verbindung: WebSocket (Consumer) &lt;-&gt; TCP (Ziel hinter der Data Plane).
 *
 * <ul>
 *   <li>WebSocket -&gt; TCP: jede Binary-Message wird blockierend auf den Socket geschrieben; erst danach wird der
 *       Callback bestaetigt und ({@link Session.Listener.AutoDemanding}) die naechste Message angefordert. Damit
 *       bremst ein langsames Ziel den Consumer (Backpressure).</li>
 *   <li>TCP -&gt; WebSocket: ein eigener Thread liest vom Socket und sendet jeden Chunk als Binary-Message; er wartet
 *       auf den Send-Callback, bevor er weiterliest.</li>
 *   <li>Schliesst eine Seite, wird die andere ebenfalls geschlossen.</li>
 * </ul>
 */
public class TcpTunnelSession implements Session.Listener.AutoDemanding {

    static final int NORMAL = 1000;
    static final int POLICY_VIOLATION = 1008;
    static final int TRY_AGAIN_LATER = 1013;
    static final int SERVER_ERROR = 1011;
    static final int BAD_DATA = 1003;

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final long MAX_MESSAGE_SIZE = 1024 * 1024;

    private final String flowId;
    private final String host;
    private final int port;
    private final int maxConnections;
    private final int connectTimeoutMs;
    private final Duration idleTimeout;
    private final TcpTunnelRegistry registry;
    private final Monitor monitor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Session session;
    private volatile Socket socket;
    private volatile OutputStream upstream;

    public TcpTunnelSession(String flowId, String host, int port, int maxConnections, int connectTimeoutMs,
                            Duration idleTimeout, TcpTunnelRegistry registry, Monitor monitor) {
        this.flowId = flowId;
        this.host = host;
        this.port = port;
        this.maxConnections = maxConnections;
        this.connectTimeoutMs = connectTimeoutMs;
        this.idleTimeout = idleTimeout;
        this.registry = registry;
        this.monitor = monitor;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        this.session = session;
        session.setIdleTimeout(idleTimeout);
        session.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);

        if (!registry.tryAdd(flowId, this, maxConnections)) {
            closeWebSocket(TRY_AGAIN_LATER, "connection limit of %d reached".formatted(maxConnections));
            return;
        }

        try {
            var target = new Socket();
            target.setTcpNoDelay(true);
            target.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket = target;
            upstream = target.getOutputStream();
        } catch (IOException e) {
            monitor.warning("Tunnel target %s:%d of flow %s not reachable: %s".formatted(host, port, flowId, e.getMessage()));
            close(SERVER_ERROR, "tunnel target not reachable");
            return;
        }

        var pump = new Thread(this::pumpTargetToClient, "tcp-tunnel-" + flowId);
        pump.setDaemon(true);
        pump.start();
        monitor.info("Tunnel opened: %s -> %s:%d (flow %s)".formatted(session.getRemoteSocketAddress(), host, port, flowId));
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
        var out = upstream;
        if (out == null) {
            callback.fail(new IOException("tunnel not connected"));
            return;
        }
        try {
            if (payload.hasArray()) {
                out.write(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining());
            } else {
                var bytes = new byte[payload.remaining()];
                payload.get(bytes);
                out.write(bytes);
            }
            out.flush();
            callback.succeed();
        } catch (IOException e) {
            callback.fail(e);
            close(SERVER_ERROR, "write to tunnel target failed");
        }
    }

    @Override
    public void onWebSocketText(String message) {
        close(BAD_DATA, "tunnel accepts binary messages only");
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        closeSocket();
        registry.remove(flowId, this);
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        monitor.debug("Tunnel of flow %s failed: %s".formatted(flowId, cause.getMessage()));
        closeSocket();
        registry.remove(flowId, this);
    }

    /**
     * Schliesst WebSocket und TCP-Verbindung.
     */
    public void close(int statusCode, String reason) {
        closeWebSocket(statusCode, reason);
        closeSocket();
        registry.remove(flowId, this);
    }

    /**
     * Keep-alive, damit Ingress/Load-Balancer eine ruhige Verbindung nicht trennen.
     */
    public void ping() {
        var current = session;
        if (current != null && current.isOpen()) {
            current.sendPing(ByteBuffer.allocate(0), Callback.NOOP);
        }
    }

    private void pumpTargetToClient() {
        var buffer = new byte[BUFFER_SIZE];
        try (var in = socket.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                var sent = new CompletableFuture<Void>();
                session.sendBinary(ByteBuffer.wrap(buffer, 0, read), Callback.from(() -> sent.complete(null), sent::completeExceptionally));
                // auf den Versand warten, bevor der Puffer wiederverwendet wird (Backpressure Richtung Ziel)
                sent.get();
            }
            close(NORMAL, "tunnel target closed the connection");
        } catch (IOException | ExecutionException e) {
            if (!closed.get()) {
                close(SERVER_ERROR, "tunnel target connection failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(SERVER_ERROR, "tunnel interrupted");
        }
    }

    private void closeWebSocket(int statusCode, String reason) {
        var current = session;
        if (current != null && current.isOpen()) {
            current.close(statusCode, reason, Callback.NOOP);
        }
    }

    private void closeSocket() {
        if (closed.compareAndSet(false, true)) {
            var current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // beim Schliessen nicht relevant
                }
                monitor.info("Tunnel closed: %s:%d (flow %s)".formatted(host, port, flowId));
            }
        }
    }
}
