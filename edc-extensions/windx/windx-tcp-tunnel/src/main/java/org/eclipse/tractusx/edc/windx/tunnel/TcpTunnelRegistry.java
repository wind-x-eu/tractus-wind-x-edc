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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Haelt die offenen Tunnel je Flow, begrenzt deren Anzahl und schliesst sie, sobald der Flow nicht mehr aktiv ist.
 *
 * <p>Ein Watchdog laeuft im Intervall {@code watchdogInterval}: er sendet Keep-alive-Pings (Ingress-Idle-Timeouts)
 * und schliesst die Tunnel aller Flows, fuer die {@code flowActive} {@code false} liefert.</p>
 */
public class TcpTunnelRegistry {

    private final Map<String, Set<TcpTunnelSession>> sessions = new ConcurrentHashMap<>();
    private final Predicate<String> flowActive;
    private final Duration watchdogInterval;
    private final Monitor monitor;
    private ScheduledExecutorService scheduler;

    public TcpTunnelRegistry(Predicate<String> flowActive, Duration watchdogInterval, Monitor monitor) {
        this.flowActive = flowActive;
        this.watchdogInterval = watchdogInterval;
        this.monitor = monitor;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "tcp-tunnel-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        var millis = watchdogInterval.toMillis();
        scheduler.scheduleWithFixedDelay(this::watchdog, millis, millis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        sessions.keySet().forEach(flowId -> closeFlow(flowId, "data plane shutdown"));
    }

    public boolean isFlowActive(String flowId) {
        return flowActive.test(flowId);
    }

    /**
     * Registriert eine Verbindung, sofern das Limit des Flows noch nicht erreicht ist.
     *
     * @return {@code false}, wenn bereits {@code maxConnections} Verbindungen offen sind
     */
    public boolean tryAdd(String flowId, TcpTunnelSession session, int maxConnections) {
        var added = new boolean[1];
        sessions.compute(flowId, (id, set) -> {
            var current = set == null ? ConcurrentHashMap.<TcpTunnelSession>newKeySet() : set;
            if (current.size() < maxConnections) {
                added[0] = current.add(session);
            }
            return current.isEmpty() ? null : current;
        });
        return added[0];
    }

    public void remove(String flowId, TcpTunnelSession session) {
        sessions.computeIfPresent(flowId, (id, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }

    public int connectionCount(String flowId) {
        var set = sessions.get(flowId);
        return set == null ? 0 : set.size();
    }

    /**
     * Schliesst alle Tunnel eines Flows.
     *
     * @return Anzahl der geschlossenen Verbindungen
     */
    public int closeFlow(String flowId, String reason) {
        var set = sessions.remove(flowId);
        if (set == null) {
            return 0;
        }
        set.forEach(session -> session.close(TcpTunnelSession.POLICY_VIOLATION, reason));
        return set.size();
    }

    private void watchdog() {
        try {
            for (var flowId : List.copyOf(sessions.keySet())) {
                if (!flowActive.test(flowId)) {
                    var closed = closeFlow(flowId, "transfer no longer active");
                    monitor.info("Watchdog closed %d tunnel connection(s) of inactive flow %s".formatted(closed, flowId));
                }
            }
            sessions.values().forEach(set -> set.forEach(TcpTunnelSession::ping));
        } catch (Exception e) {
            monitor.warning("Tunnel watchdog run failed: " + e.getMessage(), e);
        }
    }
}
