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

plugins {
    `maven-publish`
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)                // ServiceExtension, @Setting/@Inject/@Extension, Monitor, Hostname, DataFlowStartMessage
    implementation(libs.edc.spi.dataplane.dataplane) // DataSource(Factory), PipelineService, PublicEndpointGeneratorService, EndpointDataReferenceServiceRegistry, DataPlaneAuthorizationService, Endpoint
    implementation("com.google.code.gson:gson:2.10.1") // portierter mms.client-Stack serialisiert Aggregationen mit gson
    // slf4j-api ist bereits ueber den allprojects-Block auf dem Classpath (portierter mms.client nutzt es)

    testImplementation(libs.edc.junit)
    // Fuer NonFiniteSinkConsumptionTest: die non-finite-PipelineService + der Standard-ParallelSink
    // (aus data-plane-util; kein Katalog-Alias, daher direkt mit der zentralen EDC-Version).
    testImplementation(project(":edc-extensions:non-finite-provider-push:non-finite-provider-push-core"))
    testImplementation("org.eclipse.edc:data-plane-util:${libs.versions.edc.asProvider().get()}")
}

// Der portierte mms.client-Stack (roher IEC-61850/MMS-Codec, ~3800 Z. Fremdcode) folgt nicht den
// tractus-x-Checkstyle-Regeln (Star-Imports, Tabs, System.out, Naming). Checkstyle fuer dieses Modul
// deaktiviert; der neu geschriebene Extension-/Integrations-Code wird trotzdem sauber gehalten.
tasks.withType<Checkstyle> {
    enabled = false
}
