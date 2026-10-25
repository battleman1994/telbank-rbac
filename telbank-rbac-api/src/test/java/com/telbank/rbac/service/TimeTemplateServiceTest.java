package com.telbank.rbac.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateCreateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateUpdateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间模板管理（A21）：校验、默认值、启停与列表排序。
 */
class TimeTemplateServiceTest {

    private final Map<Long, SysTimeTemplate> templates = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private TimeTemplateService service;

    @BeforeEach
    void setUp() {
        SysTimeTemplateMapper repository = TestProxies.proxy(SysTimeTemplateMapper.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(templates.get((Long) args[0]));
                    case "existsByName" -> templates.values().stream()
                            .anyMatch(template -> template.getName().equals(args[0]));
                    case "existsByNameAndIdNot" -> templates.values().stream()
                            .anyMatch(template -> template.getName().equals(args[0])
                                    && !template.getId().equals(args[1]));
                    case "findAllOrderByIdDesc" -> templates.values().stream()
                            .filter(template -> args[0] == null
                                    || template.getStatus().equals(args[0]))
                            .sorted(Comparator.comparing(SysTimeTemplate::getId).reversed())
                            .toList();
                    case "save" -> {
                        SysTimeTemplate template = (SysTimeTemplate) args[0];
                        if (template.getId() == null) {
                            template.setId(ids.incrementAndGet());
                        }
                        templates.put(template.getId(), template);
                        yield template;
                    }
                    default -> TestProxies.defaultValue(method.getReturnType());
                });
        service = new TimeTemplateService(repository);
    }

    @Test
    void createFillsDefaultsAndMapsTimesAsHhMm() {
        TimeTemplateVO vo = service.create(new TimeTemplateCreateRequest(
                " 夜间窗口 ", null, 0, "19:00", 1, "07:00", null, "  "));

        assertThat(vo.id()).isEqualTo(101L);
        assertThat(vo.name()).isEqualTo("夜间窗口");
        assertThat(vo.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(vo.startDayOffset()).isZero();
        assertThat(vo.startTime()).isEqualTo("19:00");
        assertThat(vo.endDayOffset()).isEqualTo(1);
        assertThat(vo.endTime()).isEqualTo("07:00");
        assertThat(vo.status()).isEqualTo(1);
    }

    @Test
    void listFiltersByStatusAndOrdersByIdDesc() {
        templates.put(1L, template(1L, "旧模板", 0));
        templates.put(2L, template(2L, "新模板", 1));

        assertThat(service.list(null)).extracting(TimeTemplateVO::id).containsExactly(2L, 1L);
        assertThat(service.list(1)).extracting(TimeTemplateVO::id).containsExactly(2L);
    }

    @Test
    void createRejectsDuplicateName() {
        templates.put(1L, template(1L, "夜间窗口", 1));

        assertThatThrownBy(() -> service.create(request("夜间窗口", "Asia/Shanghai",
                "19:00", "07:00", 1, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板名称已存在"));
    }

    @Test
    void createRejectsInvalidTimezone() {
        assertThatThrownBy(() -> service.create(request("夜间窗口", "Mars/Base",
                "19:00", "07:00", 1, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("时区无效"));
    }

    @Test
    void createRejectsWindowWhoseEndIsNotAfterStart() {
        assertThatThrownBy(() -> service.create(request("倒挂模板", "Asia/Shanghai",
                "09:00", "08:00", 0, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板的结束时间必须晚于开始时间"));
    }

    @Test
    void createRejectsInvalidStatus() {
        assertThatThrownBy(() -> service.create(request("异常状态", "Asia/Shanghai",
                "19:00", "07:00", 1, 5)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("status 只能为 0 或 1"));
    }

    @Test
    void updateRenamesTemplate() {
        templates.put(1L, template(1L, "夜间窗口", 1));

        TimeTemplateVO vo = service.update(1L, new TimeTemplateUpdateRequest(
                "夜间窗口-重命名", "Asia/Shanghai", 0, "20:00", 1, "08:00", null));

        assertThat(vo.name()).isEqualTo("夜间窗口-重命名");
        assertThat(vo.startTime()).isEqualTo("20:00");
        assertThat(vo.endTime()).isEqualTo("08:00");
    }

    @Test
    void disableMarksTemplateInactiveAndMissingTemplateIsNotFound() {
        templates.put(1L, template(1L, "夜间窗口", 1));

        service.disable(1L);

        assertThat(templates.get(1L).getStatus()).isZero();
        assertThatThrownBy(() -> service.disable(99L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("时间模板不存在"));
    }

    // ------------------------------------------------------------------

    private static TimeTemplateCreateRequest request(String name, String timezone,
                                                     String startTime, String endTime,
                                                     int endDayOffset, Integer status) {
        return new TimeTemplateCreateRequest(name, timezone, 0, startTime, endDayOffset, endTime,
                status, null);
    }

    private static SysTimeTemplate template(Long id, String name, int status) {
        SysTimeTemplate template = new SysTimeTemplate();
        template.setId(id);
        template.setName(name);
        template.setTimezone("Asia/Shanghai");
        template.setStartDayOffset(0);
        template.setStartTime(LocalTime.of(19, 0));
        template.setEndDayOffset(1);
        template.setEndTime(LocalTime.of(7, 0));
        template.setStatus(status);
        return template;
    }
}
