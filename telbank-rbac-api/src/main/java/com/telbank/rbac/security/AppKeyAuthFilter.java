package com.telbank.rbac.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /openapi/**} 的 AppKey + HMAC-SHA256 验签过滤器（契约 §4.1）。
 *
 * <p>校验顺序：请求头完整 → 时间戳窗口（±5 分钟）→ AppKey 存在且密钥已配置
 * → 签名常量时间比较 → nonce 防重放 → appKey 绑定 systemCode 一致。
 * 任一失败返回 401 信封 {@code {"code":401,"message":"invalid signature","data":null}}。</p>
 */
public class AppKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AppKeyAuthFilter.class);

    private static final String UNAUTHORIZED_MESSAGE = "invalid signature";

    private final JsonMapper jsonMapper;
    private final RbacProperties properties;
    private final NonceStore nonceStore;

    public AppKeyAuthFilter(JsonMapper jsonMapper, RbacProperties properties, NonceStore nonceStore) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.nonceStore = nonceStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        String path = path(request);
        if (!path.startsWith(RbacConstants.OPENAPI_PATH_PREFIX)) {
            return true;
        }
        // CORS 预检请求不带签名，直接放过（由 CORS 配置处理）
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = path(request);
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        try {
            OpenApiCaller caller = authenticate(request, path, body);
            request.setAttribute(RbacConstants.ATTR_OPENAPI_CALLER, caller);
        } catch (AuthFailure ex) {
            log.warn("openapi authentication failed: path={} reason={}", path, ex.getMessage());
            writeUnauthorized(response);
            return;
        }
        HttpServletRequest requestToUse = body.length > 0
                ? new CachedBodyHttpServletRequest(request, body)
                : request;
        filterChain.doFilter(requestToUse, response);
    }

    private OpenApiCaller authenticate(HttpServletRequest request, String path, byte[] body) {
        String appKey = trimToNull(request.getHeader(RbacConstants.HEADER_APP_KEY));
        String timestamp = trimToNull(request.getHeader(RbacConstants.HEADER_TIMESTAMP));
        String nonce = trimToNull(request.getHeader(RbacConstants.HEADER_NONCE));
        String signature = trimToNull(request.getHeader(RbacConstants.HEADER_SIGNATURE));
        if (appKey == null || timestamp == null || nonce == null || signature == null) {
            throw new AuthFailure("missing authentication headers");
        }
        if (nonce.length() > 128) {
            throw new AuthFailure("nonce too long");
        }
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new AuthFailure("invalid timestamp");
        }
        long clockSkew = Math.abs(System.currentTimeMillis() - timestampMillis);
        if (clockSkew > RbacConstants.SIGNATURE_WINDOW_MILLIS) {
            throw new AuthFailure("timestamp out of window");
        }

        boolean approval = path.startsWith(RbacConstants.OPENAPI_APPROVAL_PATH_PREFIX);
        String appSecret;
        String boundSystemCode = null;
        if (approval) {
            RbacProperties.Approval approvalProperties = properties.getApproval();
            if (approvalProperties.getAppKey() == null || !approvalProperties.getAppKey().equals(appKey)) {
                throw new AuthFailure("unknown appKey");
            }
            appSecret = trimToNull(approvalProperties.getAppSecret());
        } else {
            RbacProperties.Client client = findClient(appKey);
            appSecret = trimToNull(client.getAppSecret());
            boundSystemCode = client.getSystemCode();
        }
        if (appSecret == null) {
            throw new AuthFailure("appSecret not configured");
        }

        boolean signatureValid = OpenApiSignature.verify(
                appSecret, appKey, timestamp, nonce, request.getMethod(), path,
                request.getQueryString(), body, signature);
        if (!signatureValid) {
            throw new AuthFailure("signature mismatch");
        }

        String nonceKey = appKey + ":" + nonce;
        if (!nonceStore.storeIfAbsent(nonceKey, RbacConstants.NONCE_TTL)) {
            throw new AuthFailure("nonce replayed");
        }

        if (!approval) {
            String requestSystemCode = extractSystemCode(request, body);
            if (requestSystemCode != null && !requestSystemCode.equals(boundSystemCode)) {
                throw new AuthFailure("appKey does not match systemCode");
            }
        }
        return new OpenApiCaller(appKey, approval ? null : boundSystemCode, approval);
    }

    private RbacProperties.Client findClient(String appKey) {
        for (RbacProperties.Client client : properties.getClients()) {
            if (appKey.equals(client.getAppKey())) {
                return client;
            }
        }
        throw new AuthFailure("unknown appKey");
    }

    /**
     * 从 query 参数或 JSON body 中提取 systemCode；两者都没有时返回 null（如 user-systems 接口）。
     */
    private String extractSystemCode(HttpServletRequest request, byte[] body) {
        String queryValue = request.getParameter("systemCode");
        if (queryValue == null || queryValue.isBlank()) {
            // Servlet 容器会解析 query 参数；此兜底保证包装/测试场景下也能拿到原始 query 值
            queryValue = rawQueryParameter(request.getQueryString(), "systemCode");
        }
        if (queryValue != null && !queryValue.isBlank()) {
            return queryValue.trim();
        }
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null) {
                return null;
            }
            JsonNode node = root.get("systemCode");
            if (node == null || !node.isString()) {
                return null;
            }
            String value = node.asString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException ex) {
            // body 不是合法 JSON 时交给 Controller 返回 400；验签已完成，不影响鉴权结论
            return null;
        }
    }

    private static String rawQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String segment : rawQuery.split("&")) {
            int index = segment.indexOf('=');
            String key = index < 0 ? segment : segment.substring(0, index);
            if (!name.equals(key)) {
                continue;
            }
            String value = index < 0 ? "" : segment.substring(index + 1);
            try {
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            } catch (RuntimeException ignored) {
                return value;
            }
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_MESSAGE));
        response.flushBuffer();
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
        return path == null ? "" : path;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class AuthFailure extends RuntimeException {
        private AuthFailure(String message) {
            super(message, null, false, false);
        }
    }
}
