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

package org.eclipse.tractusx.edc.windx.mediator;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static jakarta.ws.rs.core.MediaType.WILDCARD;
import static jakarta.ws.rs.core.Response.Status.BAD_GATEWAY;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static java.util.Collections.emptyMap;

/**
 * Autorisierter GET → Semantic-Mediator-POST-Adapter (portierte BIBA-{@code MediatorController}, nur GET).
 *
 * <p>Liest das EDR-Token, autorisiert es, baut aus den DataAddress-Properties
 * ({@code configurationID}/{@code responseType}/{@code referenceModell}) einen JSON-Body und ruft
 * {@code <mediatorBaseUrl>/forwardRealDataSource} auf. Die Mediator-Basis-URL ist zentral konfiguriert
 * (kein per-Asset-Property, kein hartkodierter Fallback mehr), Logging laeuft ueber den {@link Monitor}.</p>
 */
@Path("{any:.*}")
@Consumes(WILDCARD)
@Produces(WILDCARD)
public class MediatorController {

    private static final String CONFIGURATION_ID = "configurationID";
    private static final String RESPONSE_TYPE = "responseType";
    private static final String REFERENCE_MODELL = "referenceModell";

    private static final int DEFAULT_CONFIGURATION_ID = 27;
    private static final String DEFAULT_RESPONSE_TYPE = "application/json";
    private static final String DEFAULT_REFERENCE_MODELL = "default-referenceModell";

    private static final String FORWARD_PATH = "/forwardRealDataSource";
    private static final String CREATE_PATH = "/createVirtualDataSource";

    private static final String HEADER_CONFIGURATION_ID = "X-Configuration-ID";
    private static final String HEADER_RESPONSE_TYPE = "X-Response-Type";
    private static final String HEADER_REFERENCE_MODELL = "X-Reference-Modell";

    private final DataPlaneAuthorizationService authorizationService;
    private final Monitor monitor;
    private final String mediatorBaseUrl;
    // HTTP/1.1 erzwingen: der Semantic Mediator ist ein HTTP/1.1-Backend; mit dem Default (HTTP/2) scheitert
    // der gestreamte PUT-Upload (chunked, unbekannte Laenge) am h2-Upgrade ("GOAWAY received").
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public MediatorController(DataPlaneAuthorizationService authorizationService, Monitor monitor, String mediatorBaseUrl) {
        this.authorizationService = authorizationService;
        this.monitor = monitor;
        this.mediatorBaseUrl = mediatorBaseUrl;
    }

    @GET
    public Response proxyGet(@Context ContainerRequestContext requestContext) {
        var token = requestContext.getHeaderString(AUTHORIZATION);
        if (token == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        var authorization = authorizationService.authorize(token, emptyMap());
        if (authorization.failed()) {
            return Response.status(FORBIDDEN).build();
        }

        var sourceDataAddress = authorization.getContent();
        var requestBody = buildMediatorRequestBody(sourceDataAddress);
        var mediatorUrl = mediatorBaseUrl + FORWARD_PATH;

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(mediatorUrl))
                    .header(CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                monitor.warning("Semantic Mediator at %s returned status %d".formatted(mediatorUrl, response.statusCode()));
                return Response.status(BAD_GATEWAY)
                        .entity("{\"error\": \"Semantic Mediator returned status " + response.statusCode() + "\"}")
                        .build();
            }

            monitor.debug("Successfully notified Semantic Mediator at %s".formatted(mediatorUrl));
            return Response.status(response.statusCode())
                    .header(CONTENT_TYPE, response.headers().firstValue(CONTENT_TYPE).orElse(APPLICATION_OCTET_STREAM))
                    .entity(response.body())
                    .build();
        } catch (Exception e) {
            monitor.severe("Failed to contact Semantic Mediator at %s".formatted(mediatorUrl), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Response.status(BAD_GATEWAY)
                    .entity("{\"error\": \"Failed to contact backend service\"}")
                    .build();
        }
    }

    /**
     * Autorisierter PUT → Semantic-Mediator-{@code /createVirtualDataSource} (portierte BIBA-{@code proxyPut}).
     *
     * <p>Streamt den Consumer-Request-Body an den Mediator und leitet die DataAddress-Properties als
     * {@code X-*}-Header sowie den eingehenden Content-Type weiter. Anders als BIBA: eine zentrale
     * {@link #mediatorBaseUrl} (kein per-Asset {@code MEDIATOR-URL}, kein hartkodierter Fallback).</p>
     */
    @PUT
    public Response proxyPut(@Context ContainerRequestContext requestContext) {
        var token = requestContext.getHeaderString(AUTHORIZATION);
        if (token == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        var authorization = authorizationService.authorize(token, emptyMap());
        if (authorization.failed()) {
            return Response.status(FORBIDDEN).build();
        }

        var sourceDataAddress = authorization.getContent();
        var configurationId = parseConfigurationId(sourceDataAddress.getStringProperty(CONFIGURATION_ID));
        var responseType = orDefault(sourceDataAddress.getStringProperty(RESPONSE_TYPE), DEFAULT_RESPONSE_TYPE);
        var referenceModell = orDefault(sourceDataAddress.getStringProperty(REFERENCE_MODELL), DEFAULT_REFERENCE_MODELL);
        var mediatorUrl = mediatorBaseUrl + CREATE_PATH;

        try {
            // Der Mediator-Endpoint erwartet einen POST (wie beim GET-Pfad); der eingehende PUT waehlt nur
            // Ziel-Endpoint und leitet den Consumer-Body als Stream weiter (BIBA-treu).
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(mediatorUrl))
                    .header(HEADER_CONFIGURATION_ID, Integer.toString(configurationId))
                    .header(HEADER_RESPONSE_TYPE, responseType)
                    .header(HEADER_REFERENCE_MODELL, referenceModell)
                    .POST(HttpRequest.BodyPublishers.ofInputStream(requestContext::getEntityStream));

            var incomingContentType = requestContext.getHeaderString(CONTENT_TYPE);
            if (incomingContentType != null) {
                builder.header(CONTENT_TYPE, incomingContentType);
            }

            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                monitor.warning("Semantic Mediator at %s returned status %d".formatted(mediatorUrl, response.statusCode()));
                return Response.status(BAD_GATEWAY)
                        .entity("{\"error\": \"Semantic Mediator returned status " + response.statusCode() + "\"}")
                        .build();
            }

            monitor.debug("Successfully created virtual data source at Semantic Mediator %s".formatted(mediatorUrl));
            return Response.status(response.statusCode())
                    .header(CONTENT_TYPE, response.headers().firstValue(CONTENT_TYPE).orElse(APPLICATION_OCTET_STREAM))
                    .entity(response.body())
                    .build();
        } catch (Exception e) {
            monitor.severe("Failed to contact Semantic Mediator at %s".formatted(mediatorUrl), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Response.status(BAD_GATEWAY)
                    .entity("{\"error\": \"Failed to contact backend service\"}")
                    .build();
        }
    }

    private String buildMediatorRequestBody(DataAddress sourceDataAddress) {
        var configurationId = parseConfigurationId(sourceDataAddress.getStringProperty(CONFIGURATION_ID));
        var responseType = orDefault(sourceDataAddress.getStringProperty(RESPONSE_TYPE), DEFAULT_RESPONSE_TYPE);
        var referenceModell = orDefault(sourceDataAddress.getStringProperty(REFERENCE_MODELL), DEFAULT_REFERENCE_MODELL);

        return """
                {
                    "configurationID": %d,
                    "responseType": "%s",
                    "referenceModell": "%s"
                }
                """.formatted(configurationId, responseType, referenceModell);
    }

    private int parseConfigurationId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CONFIGURATION_ID;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            monitor.warning("configurationID '%s' is not numeric, using default %d".formatted(raw, DEFAULT_CONFIGURATION_ID));
            return DEFAULT_CONFIGURATION_ID;
        }
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
