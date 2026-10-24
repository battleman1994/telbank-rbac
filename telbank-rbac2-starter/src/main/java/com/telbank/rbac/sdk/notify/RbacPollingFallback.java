package com.telbank.rbac.sdk.notify;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 兜底短轮询（需求 §6.11，契约 §7.1）。
 *
 * <p>SDK 自持单线程守护 {@link ScheduledExecutorService}，默认每 300s 全量刷新
 * 映射表 + 页面操作元数据；不依赖宿主 {@code @EnableScheduling}，也不需要宿主线程池。</p>
 *
 * <p>Redis Pub/Sub 不可用时它就是唯一的变更感知通道；Pub/Sub 正常时低频轮询也作为
 * 消息丢失的兜底。刷新失败只告警，保留旧缓存。</p>
 */
public class RbacPollingFallback implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RbacPollingFallback.class);

    private final RbacCacheRefresher cacheRefresher;

    private final long intervalMillis;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;

    public RbacPollingFallback(RbacCacheRefresher cacheRefresher, RbacSdkProperties properties) {
        if (cacheRefresher == null || properties == null) {
            throw new IllegalArgumentException("RbacPollingFallback 依赖不能为空");
        }
        this.cacheRefresher = cacheRefresher;
        this.intervalMillis = properties.getCache().getFallbackPollIntervalMillis();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /**
     * 启动轮询（幂等）；首次刷新立即执行，不阻塞宿主启动线程。
     */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "rbac-sdk-polling");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                refreshSafely();
            }
        }, 0L, intervalMillis, TimeUnit.MILLISECONDS);
        this.executor = scheduler;
        log.info("RBAC 兜底短轮询已启动: interval={}ms", Long.valueOf(intervalMillis));
    }

    public boolean isStarted() {
        return started.get();
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    private void refreshSafely() {
        try {
            cacheRefresher.refreshMappingsAndPageActions();
        } catch (Throwable t) {
            log.warn("RBAC 兜底轮询刷新失败（保留旧缓存）: {}", t.toString());
        }
    }

    @Override
    public void destroy() {
        ScheduledExecutorService scheduler = this.executor;
        this.executor = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("RBAC 兜底短轮询已停止");
        }
    }
}
