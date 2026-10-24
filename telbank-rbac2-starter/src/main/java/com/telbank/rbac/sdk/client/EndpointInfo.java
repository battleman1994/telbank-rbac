package com.telbank.rbac.sdk.client;

/**
 * SDK 扫描到的端点（上报用），对应 {@code POST /openapi/sdk/endpoints/sync} 请求体：
 * {@code {"method":"GET","path":"/api/order/list","candidateCode":"crm:order:list","source":"GENERATED"}}。
 */
public class EndpointInfo {

    /** 候选码来源：注解 {@code @RbacPermission} 或按规则生成。 */
    public static final String SOURCE_ANNOTATION = "ANNOTATION";

    /** 候选码来源：按 code-generator 规则生成。 */
    public static final String SOURCE_GENERATED = "GENERATED";

    private String method;

    private String path;

    private String candidateCode;

    private String source;

    public EndpointInfo() {
    }

    public EndpointInfo(String method, String path, String candidateCode, String source) {
        this.method = method;
        this.path = path;
        this.candidateCode = candidateCode;
        this.source = source;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCandidateCode() {
        return candidateCode;
    }

    public void setCandidateCode(String candidateCode) {
        this.candidateCode = candidateCode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "EndpointInfo{" + method + ' ' + path + " -> " + candidateCode + " (" + source + ")}";
    }
}
