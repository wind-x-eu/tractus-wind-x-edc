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

package org.eclipse.tractusx.edc.windx.mms.client;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import org.eclipse.tractusx.edc.windx.mms.client.MMSClientConfig;

public class MMSClientConfigLoader {

	private static final Logger logger = LoggerFactory.getLogger(MMSClientConfigLoader.class);

	public static MMSClientConfig loadConfigFromFile(String configFilePath) {
		logger.info("Lade Konfiguration aus Datei: {}", configFilePath);

		File configFile = new File(configFilePath);
		if (!configFile.exists()) {
			logger.error("Konfigurationsdatei nicht gefunden: {}", configFilePath);
			return null;
		}

		Gson gson = new Gson();
		try (Reader reader = new FileReader(configFilePath)) {
			MMSClientConfig config = gson.fromJson(reader, MMSClientConfig.class);
			logger.info("Konfiguration erfolgreich geladen: Host={}, Port={}, LogicalDevice={}",
					config.getServerHost(), config.getServerPort(), config.getLogicalDevice());
			logger.debug("Beobachtete Sensoren: {}", config.getObservedSensors());
			return config;
		} catch (IOException e) {
			logger.error("Fehler beim Lesen der Konfigurationsdatei: {}", configFilePath, e);
			return null;
		}
	}

}
