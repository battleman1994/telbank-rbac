package com.telbank.rbac.testutil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * 测试用轻量 fake：用 JDK 动态代理实现 Repository 接口，避免依赖 Mockito / 数据库。
 */
public final class TestProxies {

    private TestProxies() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return type.getSimpleName() + "Fake";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return handler.invoke(proxy, method, args);
                    }
                });
    }

    public static <T> T noop(Class<T> type) {
        return proxy(type, (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
