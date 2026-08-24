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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Repräsentiert eine MMS-Nachricht (Report) vom Server
 * Nach IEC 61850-8-1 (MMS Mapping)
 */
public class MMSMessage {
    
    private String reportId;
    private String dataSet;
    private LocalDateTime timestamp;
    private Map<String, Object> data;
    private String sequenceNumber;
    private boolean timeOfEntry;
    private boolean reasonCode;
    
    public MMSMessage() {
        this.data = new HashMap<>();
        this.timestamp = LocalDateTime.now();
    }
    
    /**
     * Report ID - Identifikation des Reports
     */
    public String getReportId() {
        return reportId;
    }
    
    public void setReportId(String reportId) {
        this.reportId = reportId;
    }
    
    /**
     * DataSet Reference - Referenz zum zugehörigen Dataset
     */
    public String getDataSet() {
        return dataSet;
    }
    
    public void setDataSet(String dataSet) {
        this.dataSet = dataSet;
    }
    
    /**
     * Zeitstempel der Nachricht
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    /**
     * Daten der Nachricht als Map (Datenpunkt -> Wert)
     */
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
    
    /**
     * Fügt einen Datenpunkt hinzu
     */
    public void addDataPoint(String reference, Object value) {
        this.data.put(reference, value);
    }
    
    /**
     * Sequenznummer des Reports
     */
    public String getSequenceNumber() {
        return sequenceNumber;
    }
    
    public void setSequenceNumber(String sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
    
    /**
     * Konvertiert die Nachricht in JSON-Format
     * 
     * @return JSON-String der Nachricht
     */
    public String toJson() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
        
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, 
                    (JsonSerializer<LocalDateTime>) (src, typeOfSrc, context) -> 
                        context.serialize(src.format(formatter)))
                .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, typeOfT, context) ->
                        LocalDateTime.parse(json.getAsString(), formatter))
                .create();
        return gson.toJson(this);
    }
    
    /**
     * Konvertiert die Nachricht in kompaktes JSON-Format (ohne Formatierung)
     * 
     * @return Kompakter JSON-String
     */
    public String toCompactJson() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
        
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, 
                    (JsonSerializer<LocalDateTime>) (src, typeOfSrc, context) -> 
                        context.serialize(src.format(formatter)))
                .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, typeOfT, context) ->
                        LocalDateTime.parse(json.getAsString(), formatter))
                .create();
        return gson.toJson(this);
    }
    
    @Override
    public String toString() {
        return "MMSMessage{" +
                "reportId='" + reportId + '\'' +
                ", dataSet='" + dataSet + '\'' +
                ", timestamp=" + timestamp +
                ", data=" + data +
                ", sequenceNumber='" + sequenceNumber + '\'' +
                '}';
    }
}
