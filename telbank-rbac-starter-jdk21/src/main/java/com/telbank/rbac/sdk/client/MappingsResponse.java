package com.telbank.rbac.sdk.client;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /openapi/sdk/mappings} 的 data 结构（内部使用）。
 */
class MappingsResponse {

    private String systemCode;

    private List<MappingEntry> mappings = new ArrayList<MappingEntry>();

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public List<MappingEntry> getMappings() {
        return mappings;
    }

    public void setMappings(List<MappingEntry> mappings) {
        this.mappings = mappings;
    }
}
