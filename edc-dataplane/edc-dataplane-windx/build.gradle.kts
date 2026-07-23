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
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    // Identisch zu edc-dataplane-hashicorp-vault: offizielle Data-Plane mit SQL-Persistenz
    // (feature-sql-BOM + connector-migration/Flyway) und HashiCorp-Vault.
    // Bewusst KEIN exclude von data-plane-selector-client: eine eigenstaendige Data-Plane muss
    // sich bei der Control-Plane registrieren (anders als in der alten kombinierten Runtime).
    runtimeOnly(libs.edc.bom.dataplane.feature.sql)
    runtimeOnly(libs.edc.vault.hashicorp)
    implementation(project(":edc-dataplane:edc-dataplane-base"))
    implementation(project(":edc-extensions:migrations:connector-migration"))

    // Wind-X-Fachlogik. Alle vier sind reine Data-Plane-Extensions (haengen nur an
    // edc.spi.core / edc.spi.web / edc.spi.dataplane.dataplane) -- kein Control-Plane-Bezug.
    // Die Control-Plane bleibt dadurch unveraendertes Upstream.
    implementation(project(":edc-extensions:windx:windx-mediator-proxy"))
    implementation(project(":edc-extensions:windx:windx-mms"))
    implementation(project(":edc-extensions:windx:windx-participant-log"))
    implementation(project(":edc-extensions:non-finite-provider-push:non-finite-provider-push-core"))

    testImplementation(libs.edc.junit)
    testImplementation(libs.edc.lib.boot)
}

tasks.shadowJar {
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveFileName.set("${project.name}.jar")
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer())
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}
