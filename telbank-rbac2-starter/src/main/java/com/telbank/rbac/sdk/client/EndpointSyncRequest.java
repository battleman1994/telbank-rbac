package com.telbank.rbac.sdk.client;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /openapi/sdk/endpoints/sync} 的请求体（内部使用）。
 */
class EndpointSyncRequest {

    private String systemCode;

    private List<EndpointInfo> endpoints = new ArrayList<EndpointInfo>();

    EndpointSyncRequest() {
    }

    EndpointSyncRequest(String systemCode, List<EndpointInfo> endpoints) {
        this.systemCode = systemCode;
        this.endpoints = endpoints;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public List<EndpointInfo> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<EndpointInfo> endpoints) {
        this.endpoints = endpoints;
    }
}
