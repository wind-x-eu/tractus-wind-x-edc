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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Repräsentiert eine Aggregation von 100 MMS-Nachrichten
 * Schema: Arrays für Timestamps und jeden MMS-Datenpunkt
 */
public class MMSAggregation {
    
    private String aggregationId;
    private LocalDateTime aggregationTimestamp;
    private int messageCount;
    
    // Array mit allen Timestamps der Nachrichten
    private List<String> timestamps;
    
    // Map mit MMS-Labels als Keys und DataArrayEntry-Objekten als Values
    // Jedes DataArrayEntry enthält zwei Arrays: keys und values
    // z.B. "ld0-MMXU1.TotW.mag.f" -> {keys: ["ld0-MMXU1.TotW.mag.f", ...], values: [2678.45, ...]}
    private Map<String, DataArrayEntry> dataArrays;
    
    private Map<String, Object> statistics;
    
    public MMSAggregation() {
    }
    
    public String getAggregationId() {
        return aggregationId;
    }
    
    public void setAggregationId(String aggregationId) {
        this.aggregationId = aggregationId;
    }
    
    public LocalDateTime getAggregationTimestamp() {
        return aggregationTimestamp;
    }
    
    public void setAggregationTimestamp(LocalDateTime aggregationTimestamp) {
        this.aggregationTimestamp = aggregationTimestamp;
    }
    
    public int getMessageCount() {
        return messageCount;
    }
    
    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }
    
    public List<String> getTimestamps() {
        return timestamps;
    }
    
    public void setTimestamps(List<String> timestamps) {
        this.timestamps = timestamps;
    }
    
    public Map<String, DataArrayEntry> getDataArrays() {
        return dataArrays;
    }
    
    public void setDataArrays(Map<String, DataArrayEntry> dataArrays) {
        this.dataArrays = dataArrays;
    }
    
    public Map<String, Object> getStatistics() {
        return statistics;
    }
    
    public void setStatistics(Map<String, Object> statistics) {
        this.statistics = statistics;
    }
    
    @Override
    public String toString() {
        return "MMSAggregation{" +
                "aggregationId='" + aggregationId + '\'' +
                ", aggregationTimestamp=" + aggregationTimestamp +
                ", messageCount=" + messageCount +
                ", timestampsCount=" + (timestamps != null ? timestamps.size() : 0) +
                ", dataArraysCount=" + (dataArrays != null ? dataArrays.size() : 0) +
                '}';
    }
}
