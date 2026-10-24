package com.telbank.rbac.sdk.util;

import java.io.IOException;
import java.security.Principal;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求工具（SDK 内部）：路径归一化、当前用户 externalUserId 解析、JSON 转义。
 *
 * <p>身份获取口径（契约 §7.6）：</p>
 * <ol>
 *   <li>{@code HttpServletRequest.getUserPrincipal().getName()}（宿主已认证并写入 principal）；</li>
 *   <li>解析 {@code Authorization: Bearer <jwt>} 的 payload（仅 Base64URL 解码，<b>不验签</b>）中的
 *       {@code sub}；JWT 有效性由宿主认证链/外部认证系统负责，SDK 不感知、不校验 JWT；</li>
 *   <li>都没有 → anonymous（返回 null）。</li>
 * </ol>
 *
 * <p>不读取任何可被客户端伪造的自定义用户头（如 X-External-User-Id）。</p>
 */
public final class RbacRequestUtils {

    private static final Logger log = LoggerFactory.getLogger(RbacRequestUtils.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RbacRequestUtils() {
    }

    /**
     * 解析当前请求的 externalUserId。
     *
     * @return 取不到返回 null（anonymous）
     */
    public static String resolveExternalUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Principal principal = request.getUserPrincipal();
        if (principal != null) {
            String name = trimToNull(principal.getName());
            if (name != null) {
                return name;
            }
        }
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null) {
            return null;
        }
        String header = authorization.trim();
        if (header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return extractJwtSubject(header.substring(BEARER_PREFIX.length()).trim());
    }

    /**
     * 解析 JWT payload 中的 sub（仅 Base64URL 解码，不验签）。
     */
    public static String extractJwtSubject(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode node = OBJECT_MAPPER.readTree(payload);
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode sub = node.get("sub");
            if (sub == null || !sub.isTextual()) {
                return null;
            }
            return trimToNull(sub.asText());
        } catch (RuntimeException e) {
            log.debug("解析 Authorization JWT payload 失败（忽略该身份来源）: {}", e.toString());
            return null;
        } catch (IOException e) {
            log.debug("解析 Authorization JWT payload 失败（忽略该身份来源）: {}", e.toString());
            return null;
        }
    }

    /**
     * 归一化请求路径：剥离 servlet contextPath、去掉 query，折叠重复斜杠。
     */
    public static String resolvePath(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty()) {
            uri = "/";
        }
        return com.telbank.rbac.sdk.cache.RbacPathMatcher.normalize(uri);
    }

    /**
     * 极简 JSON 字符串转义（用于错误响应 body）。
     */
    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", Integer.valueOf(ch)));
                    } else {
                        builder.append(ch);
                    }
            }
        }
        return builder.toString();
    }

    /**
     * Base64URL 无填充 → 补齐 {@code =}（Java 8 的 URL Decoder 对部分长度需要填充）。
     */
    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 2) {
            return value + "==";
        }
        if (remainder == 3) {
            return value + "=";
        }
        if (remainder == 1) {
            return value + "===";
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
