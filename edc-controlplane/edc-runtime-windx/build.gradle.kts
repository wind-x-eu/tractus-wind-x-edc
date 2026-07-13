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
    implementation(project(":edc-controlplane:edc-controlplane-base"))
    implementation(project(":edc-dataplane:edc-dataplane-base")) {
        exclude("org.eclipse.edc", "data-plane-selector-client")
    }

    implementation(project(":core:core-utils"))
    implementation(project(":edc-extensions:single-participant-vault"))
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.participant.context.single)

    // Wind-X: portierte BIBA-Extensions
    implementation(project(":edc-extensions:windx:windx-participant-log"))
    implementation(project(":edc-extensions:windx:windx-mediator-proxy"))
    implementation(project(":edc-extensions:windx:windx-mms"))
    // Ersetzt die Standard-PipelineService durch die non-finite-faehige Variante (kontinuierlicher Mms-PUSH,
    // Source-Property edc:isNonFinite=true). Nicht-default @Provider ueberschreibt den isDefault-Standard.
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
