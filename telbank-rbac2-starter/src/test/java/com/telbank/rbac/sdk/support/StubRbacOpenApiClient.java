package com.telbank.rbac.sdk.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.telbank.rbac.sdk.client.ConnectionInfo;
import com.telbank.rbac.sdk.client.EndpointInfo;
import com.telbank.rbac.sdk.client.MappingEntry;
import com.telbank.rbac.sdk.client.OnboardingApplyRequest;
import com.telbank.rbac.sdk.client.OnboardingApplyResult;
import com.telbank.rbac.sdk.client.PageAction;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.client.SystemInfo;
import com.telbank.rbac.sdk.client.UserPermissionEntry;
import com.telbank.rbac.sdk.client.UserPermissionResponse;

/**
 * 测试用 {@link RbacOpenApiClient} 桩：不依赖网络，可配置返回值/异常并记录调用次数。
 */
public class StubRbacOpenApiClient implements RbacOpenApiClient {

    public volatile List<MappingEntry> mappings = new ArrayList<MappingEntry>();

    public volatile List<PageAction> pageActions = new ArrayList<PageAction>();

    public volatile UserPermissionResponse userPermissions = response("1970-01-01T00:00:00Z");

    public volatile List<SystemInfo> systems = new ArrayList<SystemInfo>();

    public volatile ConnectionInfo connectionInfo;

    public volatile List<EndpointInfo> syncedEndpoints;

    public volatile RuntimeException failOnUserPermissions;

    public volatile RuntimeException failOnMappings;

    public volatile RuntimeException failOnPageActions;

    public volatile RuntimeException failOnConnection;

    public volatile RuntimeException failOnSync;

    public volatile RuntimeException failOnSystems;

    public final AtomicInteger userPermissionCalls = new AtomicInteger();

    public final AtomicInteger mappingCalls = new AtomicInteger();

    public final AtomicInteger pageActionCalls = new AtomicInteger();

    public final AtomicInteger connectionCalls = new AtomicInteger();

    public final AtomicInteger syncCalls = new AtomicInteger();

    public final AtomicInteger systemCalls = new AtomicInteger();

    public final AtomicInteger onboardingCalls = new AtomicInteger();

    public volatile OnboardingApplyRequest lastOnboardingRequest;

    public volatile OnboardingApplyResult onboardingResult = new OnboardingApplyResult(1L, "PENDING");

    public volatile RuntimeException failOnOnboarding;

    public volatile String lastExternalUserId;

    @Override
    public List<MappingEntry> fetchMappings() {
        mappingCalls.incrementAndGet();
        if (failOnMappings != null) {
            throw failOnMappings;
        }
        return new ArrayList<MappingEntry>(mappings);
    }

    @Override
    public UserPermissionResponse fetchUserPermissions(String externalUserId) {
        userPermissionCalls.incrementAndGet();
        lastExternalUserId = externalUserId;
        if (failOnUserPermissions != null) {
            throw failOnUserPermissions;
        }
        return userPermissions;
    }

    @Override
    public List<PageAction> fetchPageActions() {
        pageActionCalls.incrementAndGet();
        if (failOnPageActions != null) {
            throw failOnPageActions;
        }
        return new ArrayList<PageAction>(pageActions);
    }

    @Override
    public ConnectionInfo fetchConnection() {
        connectionCalls.incrementAndGet();
        if (failOnConnection != null) {
            throw failOnConnection;
        }
        return connectionInfo;
    }

    @Override
    public void syncEndpoints(List<EndpointInfo> endpoints) {
        syncCalls.incrementAndGet();
        if (failOnSync != null) {
            throw failOnSync;
        }
        this.syncedEndpoints = new ArrayList<EndpointInfo>(endpoints);
    }

    @Override
    public OnboardingApplyResult reportOnboarding(OnboardingApplyRequest request) {
        onboardingCalls.incrementAndGet();
        lastOnboardingRequest = request;
        if (failOnOnboarding != null) {
            throw failOnOnboarding;
        }
        return onboardingResult;
    }

    @Override
    public List<SystemInfo> fetchUserSystems(String externalUserId) {
        systemCalls.incrementAndGet();
        lastExternalUserId = externalUserId;
        if (failOnSystems != null) {
            throw failOnSystems;
        }
        return new ArrayList<SystemInfo>(systems);
    }

    public static UserPermissionResponse response(String serverTime, UserPermissionEntry... entries) {
        UserPermissionResponse response = new UserPermissionResponse();
        response.setServerTime(serverTime);
        response.setPermissions(entries == null ? Collections.<UserPermissionEntry>emptyList()
                : Arrays.asList(entries));
        return response;
    }

    public static UserPermissionEntry permission(String code, String validFrom, String validUntil) {
        return new UserPermissionEntry(code, validFrom, validUntil);
    }

    public static MappingEntry mapping(String method, String pattern, String code) {
        return new MappingEntry(method, pattern, code);
    }

    public static PageAction pageAction(String pageCode, String code) {
        PageAction action = new PageAction();
        action.setPageCode(pageCode);
        action.setCode(code);
        action.setText(code);
        action.setAction("callApi");
        action.setType("default");
        return action;
    }

    public static EndpointInfo endpoint(String method, String path, String candidateCode, String source) {
        return new EndpointInfo(method, path, candidateCode, source);
    }

    public static SystemInfo system(String code, String name, boolean isDefault) {
        return new SystemInfo(code, name, "region_a", Boolean.valueOf(isDefault));
    }
}
