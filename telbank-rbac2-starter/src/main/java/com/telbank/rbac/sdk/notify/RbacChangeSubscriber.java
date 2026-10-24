package com.telbank.rbac.sdk.notify;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.ConnectionInfo;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

/**
 * 区域化 Redis Pub/Sub 变更订阅（需求 §6.11，契约 §6）。
 *
 * <p>启动流程（全部失败只告警，不影响宿主启动/接口）：</p>
 * <ol>
 *   <li>调 {@code GET /openapi/sdk/connection} 获取本系统所在区域的 Redis 连接信息
 *       （<b>不使用宿主默认 Redis 配置</b>，SDK 自建 Lettuce 连接工厂）；</li>
 *   <li>订阅频道 {@code rbac:change:{region}:{systemCode}}（优先使用下发的 channelPrefix）；</li>
 *   <li>收到消息 → 按 type 全量刷新映射/页面元数据并失效用户权限集缓存；</li>
 *   <li>连接/订阅失败 → warn 日志 + 依赖 {@link RbacPollingFallback} 兜底短轮询。</li>
 * </ol>
 *
 * <p>订阅在守护线程中启动，避免权限中心/Redis 不可达时拖慢宿主启动。</p>
 */
public class RbacChangeSubscriber implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RbacChangeSubscriber.class);

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String TYPE_PERMISSION_CHANGED = "PERMISSION_CHANGED";

    private static final String TYPE_PAGE_ACTION_CHANGED = "PAGE_ACTION_CHANGED";

    private static final String TYPE_ENDPOINT_CHANGED = "ENDPOINT_CHANGED";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final RbacSdkProperties properties;

    private final RbacOpenApiClient remoteClient;

    private final RbacCacheRefresher cacheRefresher;

    private final RbacUserPermissionCache userPermissionCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile RedisMessageListenerContainer listenerContainer;

    private volatile LettuceConnectionFactory connectionFactory;

    public RbacChangeSubscriber(RbacSdkProperties properties, RbacOpenApiClient remoteClient,
            RbacCacheRefresher cacheRefresher, RbacUserPermissionCache userPermissionCache) {
        if (properties == null || remoteClient == null || cacheRefresher == null
                || userPermissionCache == null) {
            throw new IllegalArgumentException("RbacChangeSubscriber 依赖不能为空");
        }
        this.properties = properties;
        this.remoteClient = remoteClient;
        this.cacheRefresher = cacheRefresher;
        this.userPermissionCache = userPermissionCache;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                startSubscription();
            }
        }, "rbac-sdk-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isSubscribed() {
        return listenerContainer != null && listenerContainer.isRunning();
    }

    private void startSubscription() {
        try {
            ConnectionInfo connectionInfo = remoteClient.fetchConnection();
            String channel = resolveChannel(connectionInfo);
            RedisStandaloneConfiguration standalone =
                    new RedisStandaloneConfiguration(connectionInfo.getHost(),
                            connectionInfo.getPort().intValue());
            if (isNotBlank(connectionInfo.getPassword())) {
                standalone.setPassword(RedisPassword.of(connectionInfo.getPassword()));
            }
            LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                    .clientOptions(ClientOptions.builder()
                            .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                            .build())
                    .build();
            LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfiguration);
            factory.afterPropertiesSet();
            probe(factory);

            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener(new MessageListener() {
                @Override
                public void onMessage(Message message, byte[] pattern) {
                    handleMessage(new String(message.getBody(), UTF_8));
                }
            }, new ChannelTopic(channel));
            container.afterPropertiesSet();
            container.start();

            this.connectionFactory = factory;
            this.listenerContainer = container;
            log.info("RBAC 变更订阅已启动: channel={}, redis={}:{}", channel,
                    connectionInfo.getHost(), connectionInfo.getPort());
        } catch (Throwable t) {
            log.warn("RBAC Redis 变更订阅启动失败，降级为兜底短轮询（不影响业务）: {}", t.toString());
            destroyResources();
        }
    }

    private static void probe(LettuceConnectionFactory factory) {
        RedisConnection connection = factory.getConnection();
        try {
            connection.ping();
        } finally {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    private String resolveChannel(ConnectionInfo connectionInfo) {
        if (isNotBlank(connectionInfo.getChannelPrefix())) {
            return connectionInfo.getChannelPrefix() + properties.getSystemCode();
        }
        if (isNotBlank(connectionInfo.getRegion())) {
            return "rbac:change:" + connectionInfo.getRegion() + ":" + properties.getSystemCode();
        }
        throw new RbacRemoteException("Redis 连接信息缺少 channelPrefix/region");
    }

    /**
     * 处理变更消息。消息格式：
     * {@code {"systemCode":"crm","type":"PERMISSION_CHANGED","timestamp":"..."}}。
     */
    void handleMessage(String payload) {
        String type = null;
        String externalUserId = null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node != null && node.isObject()) {
                type = textOrNull(node, "type");
                externalUserId = textOrNull(node, "externalUserId");
            }
        } catch (Exception e) {
            log.warn("RBAC 变更消息解析失败，按全量刷新处理: payload={}", payload);
        }
        log.info("收到 RBAC 权限变更通知: type={}, externalUserId={}", type, externalUserId);
        try {
            if (TYPE_PAGE_ACTION_CHANGED.equals(type)) {
                cacheRefresher.refreshPageActions();
                return;
            }
            if (TYPE_ENDPOINT_CHANGED.equals(type)) {
                cacheRefresher.refreshMappings();
                return;
            }
            if (TYPE_PERMISSION_CHANGED.equals(type)) {
                cacheRefresher.refreshMappings();
                if (externalUserId == null) {
                    userPermissionCache.invalidateAll();
                } else {
                    userPermissionCache.invalidateUser(externalUserId);
                }
                return;
            }
            // 未知/缺失 type：按最保守口径全量刷新并失效全部用户缓存
            cacheRefresher.refreshMappingsAndPageActions();
            if (externalUserId == null) {
                userPermissionCache.invalidateAll();
            } else {
                userPermissionCache.invalidateUser(externalUserId);
            }
        } catch (Throwable t) {
            log.warn("处理 RBAC 变更消息失败（等待下一次轮询/通知）: {}", t.toString());
        }
    }

    @Override
    public void destroy() {
        destroyResources();
    }

    private synchronized void destroyResources() {
        RedisMessageListenerContainer container = this.listenerContainer;
        this.listenerContainer = null;
        if (container != null) {
            try {
                container.destroy();
            } catch (Exception e) {
                log.warn("关闭 RBAC Redis 订阅容器失败: {}", e.toString());
            }
        }
        LettuceConnectionFactory factory = this.connectionFactory;
        this.connectionFactory = null;
        if (factory != null) {
            try {
                factory.destroy();
            } catch (Exception e) {
                log.warn("关闭 RBAC Redis 连接工厂失败: {}", e.toString());
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
