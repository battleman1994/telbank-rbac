package com.telbank.rbac.sdk.client;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /openapi/sdk/page-actions} 的 data 结构（内部使用）。
 */
class PageActionsResponse {

    private String systemCode;

    private List<PageAction> pageActions = new ArrayList<PageAction>();

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public List<PageAction> getPageActions() {
        return pageActions;
    }

    public void setPageActions(List<PageAction> pageActions) {
        this.pageActions = pageActions;
    }
}
