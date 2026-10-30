package com.telbank.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 启动参数预处理：{@code --standalone} 等价于 {@code --spring.profiles.active=standalone}
 * （H2 内存库 + 本地缓存，无需 MySQL/Redis）。
 */
class RbacApiApplicationTest {

    @Test
    void nullOrEmptyArgsAreReturnedAsIs() {
        assertThat(RbacApiApplication.prepareArgs(null)).isNull();
        assertThat(RbacApiApplication.prepareArgs(new String[0])).isEmpty();
    }

    @Test
    void argsWithoutStandaloneAreUnchanged() {
        String[] args = {"--server.port=18080", "--spring.profiles.active=prod"};
        assertThat(RbacApiApplication.prepareArgs(args))
                .containsExactly("--server.port=18080", "--spring.profiles.active=prod");
    }

    @Test
    void standaloneFlagAppendsStandaloneProfileAndKeepsOtherArgs() {
        String[] args = {"--standalone", "--server.port=18080"};
        assertThat(RbacApiApplication.prepareArgs(args))
                .containsExactly("--server.port=18080", "--spring.profiles.active=standalone");
    }

    @Test
    void standaloneFlagCanBeCombinedWithOtherFlags() {
        String[] args = {"--server.port=18080", "--standalone", "--logging.level.com.telbank.rbac=DEBUG"};
        assertThat(RbacApiApplication.prepareArgs(args))
                .containsExactly("--server.port=18080", "--logging.level.com.telbank.rbac=DEBUG",
                        "--spring.profiles.active=standalone");
    }
}
