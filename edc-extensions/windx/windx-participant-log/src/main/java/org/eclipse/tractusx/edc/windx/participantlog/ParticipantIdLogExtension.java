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

package org.eclipse.tractusx.edc.windx.participantlog;

import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.tractusx.edc.windx.participantlog.ParticipantIdLogExtension.NAME;

/**
 * Logs the configured {@code edc.participant.id} at boot time.
 *
 * <p>Portierte Version der BIBA-Extension {@code org.eclipse.edc.sample.extension.ParticipantIdLogExtension}.
 * Statt des in EDC 0.17 als deprecated geltenden {@code context.getSetting(...)} wird der Wert
 * per {@link Setting}-Feld-Injection gelesen (tractus-x-Idiom).</p>
 */
@Extension(value = NAME)
public class ParticipantIdLogExtension implements ServiceExtension {

    public static final String NAME = "Wind-X Participant Id Log Extension";

    @Setting(key = "edc.participant.id", description = "EDC Participant Id", defaultValue = "UNKNOWN")
    private String participantId;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        context.getMonitor().info("==> Loaded property edc.participant.id = " + participantId);
    }
}
