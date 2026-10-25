package com.telbank.rbac.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.service.RoleTimeWindowService.TimeWindow;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间模板窗口计算（A21）：按锚点时刻换算模板时区本地窗口，起点已过则 clamp 到当前时刻。
 */
class RoleTimeWindowServiceTest {

    private static final Instant ANCHOR = Instant.parse("2026-10-25T03:00:00Z"); // 11:00 Asia/Shanghai
    private static final Instant INSIDE_WINDOW = Instant.parse("2026-10-25T12:30:00Z"); // 20:30 本地

    private final Map<Long, SysTimeTemplate> templates = new HashMap<>();
    private RoleTimeWindowService service;

    @BeforeEach
    void setUp() {
        SysTimeTemplateMapper repository = TestProxies.proxy(SysTimeTemplateMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(templates.get((Long) args[0]));
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        service = new RoleTimeWindowService(repository);
    }

    @Test
    void roleWithoutTemplateIsPermanent() {
        SysRole role = role(null);

        assertThat(service.windowFor(role)).isEmpty();
    }

    @Test
    void roleWithMissingOrDisabledTemplateIsRejected() {
        assertThatThrownBy(() -> service.windowFor(role(99L)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板不存在或已停用"));

        templates.put(5L, template(5L, 0, "19:00", 1, "07:00", 0));
        assertThatThrownBy(() -> service.windowFor(role(5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板不存在或已停用"));
    }

    @Test
    void computeUsesTemplateTimezoneAndEndDayOffset() {
        SysTimeTemplate template = template(1L, 0, "19:00", 1, "07:00", 1);

        TimeWindow window = service.compute(template, ANCHOR);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-25T11:00:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    }

    @Test
    void computeClampsStartToAnchorWhenWindowAlreadyStarted() {
        SysTimeTemplate template = template(1L, 0, "19:00", 1, "07:00", 1);

        TimeWindow window = service.compute(template, INSIDE_WINDOW);

        assertThat(window.from()).isEqualTo(INSIDE_WINDOW);
        assertThat(window.until()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    }

    @Test
    void computeRejectsTemplateWhoseEndIsNotAfterStart() {
        SysTimeTemplate template = template(1L, 0, "09:00", 0, "08:00", 1);

        assertThatThrownBy(() -> service.compute(template, ANCHOR))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板的结束时间必须晚于开始时间"));
    }

    @Test
    void computeRejectsExpiredWindowWithoutRollingForward() {
        SysTimeTemplate template = template(1L, 0, "00:00", 0, "01:00", 1);

        assertThatThrownBy(() -> service.compute(template, ANCHOR))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("时间窗口已失效"));
    }

    @Test
    void describeBuildsHumanReadableWindowText() {
        assertThat(RoleTimeWindowService.describe(template(1L, 0, "19:00", 1, "07:00", 1)))
                .isEqualTo("审批后当天 19:00 ~ 次日 07:00");
        assertThat(RoleTimeWindowService.describe(template(2L, 3, "08:00", 5, "18:00", 1)))
                .isEqualTo("审批后第3天 08:00 ~ 第5天 18:00");
    }

    @Test
    void parseTimeValidatesFormat() {
        assertThat(RoleTimeWindowService.parseTime("09:05", "startTime")).isEqualTo(LocalTime.of(9, 5));
        assertThatThrownBy(() -> RoleTimeWindowService.parseTime(" ", "startTime"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("不能为空"));
        assertThatThrownBy(() -> RoleTimeWindowService.parseTime("9", "endTime"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("必须为 HH:mm 格式"));
    }

    private static SysRole role(Long timeTemplateId) {
        SysRole role = new SysRole();
        role.setId(1L);
        role.setTimeTemplateId(timeTemplateId);
        return role;
    }

    static SysTimeTemplate template(Long id, int startOffset, String startTime,
                                    int endOffset, String endTime, int status) {
        SysTimeTemplate template = new SysTimeTemplate();
        template.setId(id);
        template.setName("模板" + id);
        template.setTimezone("Asia/Shanghai");
        template.setStartDayOffset(startOffset);
        template.setStartTime(RoleTimeWindowService.parseTime(startTime, "startTime"));
        template.setEndDayOffset(endOffset);
        template.setEndTime(RoleTimeWindowService.parseTime(endTime, "endTime"));
        template.setStatus(status);
        return template;
    }
}
