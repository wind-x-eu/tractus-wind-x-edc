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
    // Erbt die komplette windx-Runtime: edc-controlplane-base MIT den Excludes bdrs-client + cx-dcp,
    // edc-dataplane-base, single-participant-vault, VaultSeedExtension (+ Service-Registrierung) und alle
    // windx-Extensions (participant-log, mediator-proxy, mms, bdrs-static, non-finite-provider-push).
    // Der project()-Verweis zieht das duenne runtimeElements-Jar (nicht das Shadow-Jar) inkl. transitiver Deps.
    implementation(project(":edc-controlplane:edc-runtime-windx"))

    // Persistenz: die SQL-Stores ueberschreiben die In-Memory-Default-@Provider aus base (CP + DP).
    // Gleiches Muster wie edc-controlplane-postgresql-hashicorp-vault / edc-dataplane-hashicorp-vault.
    runtimeOnly(libs.edc.bom.controlplane.feature.sql)
    runtimeOnly(libs.edc.bom.dataplane.feature.sql)

    // tractusx-eigene SQL-Stores (nicht Teil des Upstream-feature-sql-BOM) + Flyway-Migrationen.
    implementation(project(":edc-extensions:agreements:retirement-evaluation-store-sql"))
    implementation(project(":edc-extensions:agreements-bpns:bpns-evaluation-store-sql"))
    implementation(project(":edc-extensions:bpn-validation:business-partner-store-sql"))
    implementation(project(":edc-extensions:edr:edr-index-lock-sql"))
    implementation(project(":edc-extensions:migrations:connector-migration"))

    // Der Connector-Vault bleibt der single-participant-vault (via VaultSeedExtension aus dem k8s-Secret
    // bei jedem Boot geseedet). HashiCorp-Vault ist ein optionaler Folgeschritt.

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
