package com.telbank.rbac.sdk.client;

/**
 * 本区域 Redis 连接信息，来自 {@code GET /openapi/sdk/connection} 的 data：
 * {@code {"channel":"redis","region":"region_a","host":"127.0.0.1","port":6379,
 * "password":"...","channelPrefix":"rbac:change:region_a:"}}。
 */
public class ConnectionInfo {

    /** 通道类型，固定为 redis。 */
    private String channel;

    private String region;

    private String host;

    private Integer port;

    private String password;

    /** 频道前缀，如 rbac:change:region_a:；最终频道 = channelPrefix + systemCode。 */
    private String channelPrefix;

    public ConnectionInfo() {
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getChannelPrefix() {
        return channelPrefix;
    }

    public void setChannelPrefix(String channelPrefix) {
        this.channelPrefix = channelPrefix;
    }

    @Override
    public String toString() {
        return "ConnectionInfo{region='" + region + "', host='" + host + "', port=" + port
                + ", channelPrefix='" + channelPrefix + "'}";
    }
}
