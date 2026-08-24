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

import java.util.ArrayList;
import java.util.List;

/**
 * Konfigurationsklasse für MMS-Client-Parameter
 */
public class MMSClientConfig {
    
    private String serverHost;
    private int serverPort;
    private String logicalDevice;
    private int reconnectInterval;
    private boolean autoReconnect;
    private List<String> observedSensors = new ArrayList<String>(); // Liste der zu beobachtenden Sensoren
    private int updateInterval; // Aktualisierungsintervall in Millisekunden


    /**
     * Standard-Konstruktor mit Default-Werten
     */
    public MMSClientConfig() {
        this.serverHost = "localhost";
        this.serverPort = 102; // Standard MMS Port
        this.logicalDevice = "WPPS";
        this.reconnectInterval = 5000; // 5 Sekunden
        this.autoReconnect = true;
        this.updateInterval = 1000; // 1 Sekunde
        observedSensors.add("LCCS1$ST$Mod$stVal"); // Beispiel-Sensor
    }
    
    public String getServerHost() {
        return serverHost;
    }

    
    public List<String> getObservedSensors() {
		return observedSensors;
	}

	public void setObservedSensors(List<String> observedSensors) {
		this.observedSensors = observedSensors;
	}

	public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
    }
    
    public int getServerPort() {
        return serverPort;
    }
    
    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }
    
    public String getLogicalDevice() {
        return logicalDevice;
    }
    
    public void setLogicalDevice(String logicalDevice) {
        this.logicalDevice = logicalDevice;
    }
    
    public int getReconnectInterval() {
        return reconnectInterval;
    }
    
    public void setReconnectInterval(int reconnectInterval) {
        this.reconnectInterval = reconnectInterval;
    }
    
    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public int getUpdateInterval() {
        return updateInterval;
    }

    public void setUpdateInterval(int updateInterval) {
        this.updateInterval = updateInterval;
    }
    
    @Override
	public String toString() {
		return "MMSClientConfig [serverHost=" + serverHost + ", serverPort=" + serverPort + ", logicalDevice="
				+ logicalDevice + ", reconnectInterval=" + reconnectInterval + ", autoReconnect=" + autoReconnect
				+ ", updateInterval=" + updateInterval + ", observedSensors=" + observedSensors + "]";
	}
}
