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
}

dependencies {
    // identisch zum Standard-DCP-IH-Runtime ...
    implementation(project(":edc-tests:runtime:dcp:runtime-memory-dcp-ih"))
    // ... plus die Wind-X-Mediator-Data-Plane-Extension, damit sie im Runtime-Classloader
    //     via ServiceLoader geladen wird (statt aus dem Test-Classpath, was die Data-Plane-SPI
    //     doppeln und die Injection brechen wuerde).
    implementation(project(":edc-extensions:windx:windx-mediator-proxy"))
    // ... und die Wind-X-MMS-Data-Plane-Extension (registriert die 'mms'-Source-Factory), damit der
    //     Provider fuer 'mms'-Assets 'Mms-PULL' und 'HttpData-PUSH' anbietet.
    implementation(project(":edc-extensions:windx:windx-mms"))
    // ... und die non-finite-PipelineService (ersetzt die Standard-PipelineService) fuer kontinuierlichen
    //     Mms-PUSH (Source-Property edc:isNonFinite=true).
    implementation(project(":edc-extensions:non-finite-provider-push:non-finite-provider-push-core"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}
