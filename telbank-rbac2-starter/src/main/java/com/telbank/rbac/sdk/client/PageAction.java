package com.telbank.rbac.sdk.client;

/**
 * 页面操作元数据（模式 B），来自 {@code sys_page_action}。
 *
 * <p>开放接口 {@code GET /openapi/sdk/page-actions} 的 data.pageActions 元素：
 * {@code {"id":1,"pageCode":"order:list","code":"order:export","text":"导出",
 * "type":"default","action":"callApi","config":"{}","sort":10}}</p>
 *
 * <p>{@code config} 是权限中心下发的 JSON 配置，SDK 原样透传，类型使用 Object 以兼容
 * 「JSON 字符串」与「JSON 对象」两种序列化形态。</p>
 */
public class PageAction {

    private Long id;

    private String pageCode;

    private String code;

    private String text;

    private String type;

    private String action;

    private Object config;

    private Integer sort;

    public PageAction() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPageCode() {
        return pageCode;
    }

    public void setPageCode(String pageCode) {
        this.pageCode = pageCode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Object getConfig() {
        return config;
    }

    public void setConfig(Object config) {
        this.config = config;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    @Override
    public String toString() {
        return "PageAction{pageCode='" + pageCode + "', code='" + code + "', action='" + action
                + "', sort=" + sort + '}';
    }
}
