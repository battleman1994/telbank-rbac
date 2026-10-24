package com.telbank.rbac.sdk.notify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * Redis 变更消息处理语义（契约 §6）：不同类型触发不同的缓存刷新与失效动作。
 */
class RbacChangeSubscriberTest {

    private final RbacCacheRefresher refresher = mock(RbacCacheRefresher.class);

    private final RbacUserPermissionCache userPermissionCache = mock(RbacUserPermissionCache.class);

    @Test
    void permissionChangedRefreshesMappingsAndInvalidatesAllUsers() {
        subscriber().handleMessage("{\"systemCode\":\"crm\",\"type\":\"PERMISSION_CHANGED\"}");

        verify(refresher).refreshMappings();
        verify(userPermissionCache).invalidateAll();
        verify(refresher, never()).refreshPageActions();
        verify(refresher, never()).refreshMappingsAndPageActions();
    }

    @Test
    void permissionChangedWithExternalUserIdInvalidatesOnlyThatUser() {
        subscriber().handleMessage(
                "{\"systemCode\":\"crm\",\"type\":\"PERMISSION_CHANGED\",\"externalUserId\":\"13600136000\"}");

        verify(userPermissionCache).invalidateUser("13600136000");
        verify(userPermissionCache, never()).invalidateAll();
    }

    @Test
    void pageActionChangedOnlyRefreshesPageActions() {
        subscriber().handleMessage("{\"systemCode\":\"crm\",\"type\":\"PAGE_ACTION_CHANGED\"}");

        verify(refresher).refreshPageActions();
        verify(refresher, never()).refreshMappings();
        verify(userPermissionCache, never()).invalidateAll();
    }

    @Test
    void endpointChangedRefreshesMappingsOnly() {
        subscriber().handleMessage("{\"systemCode\":\"crm\",\"type\":\"ENDPOINT_CHANGED\"}");

        verify(refresher).refreshMappings();
        verify(refresher, never()).refreshPageActions();
        verify(userPermissionCache, never()).invalidateAll();
    }

    @Test
    void unknownTypeFallsBackToFullRefresh() {
        subscriber().handleMessage("{\"systemCode\":\"crm\",\"type\":\"SOMETHING_ELSE\"}");

        verify(refresher).refreshMappingsAndPageActions();
        verify(userPermissionCache).invalidateAll();
    }

    @Test
    void malformedPayloadDoesNotThrowAndFallsBackToFullRefresh() {
        RbacChangeSubscriber subscriber = subscriber();

        assertThatCode(() -> subscriber.handleMessage("not-a-json")).doesNotThrowAnyException();
        verify(refresher).refreshMappingsAndPageActions();
        verify(userPermissionCache).invalidateAll();
    }

    private RbacChangeSubscriber subscriber() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        properties.setPlatformBaseUrl("http://127.0.0.1:8080");
        properties.setAppKey("crm");
        properties.setAppSecret("crm-dev-secret");
        return new RbacChangeSubscriber(properties, new StubRbacOpenApiClient(), refresher,
                userPermissionCache);
    }
}
