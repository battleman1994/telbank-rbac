package com.telbank.rbac.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateCreateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateUpdateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateVO;

/**
 * 角色时间模板管理（A21）。
 *
 * <p>模板是全局资源（不按系统隔离），仅平台超管可写；业务系统管理员可读取用于绑定角色。</p>
 */
@Service
public class TimeTemplateService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final SysTimeTemplateMapper templateRepository;

    public TimeTemplateService(SysTimeTemplateMapper templateRepository) {
        this.templateRepository = templateRepository;
    }

    public List<TimeTemplateVO> list(Integer status) {
        return templateRepository.findAllOrderByIdDesc(status).stream()
                .map(TimeTemplateService::toVO)
                .toList();
    }

    @Transactional
    public TimeTemplateVO create(TimeTemplateCreateRequest request) {
        String name = normalizeName(request.name());
        if (templateRepository.existsByName(name)) {
            throw ApiException.conflict("时间模板名称已存在");
        }
        SysTimeTemplate template = new SysTimeTemplate();
        template.setName(name);
        validateAndFill(template, request.timezone(), request.startDayOffset(), request.startTime(),
                request.endDayOffset(), request.endTime());
        template.setStatus(validStatus(request.status(), RbacConstants.STATUS_ENABLED));
        template.setRemark(trimToNull(request.remark()));
        return toVO(templateRepository.save(template));
    }

    @Transactional
    public TimeTemplateVO update(Long id, TimeTemplateUpdateRequest request) {
        SysTimeTemplate template = require(id);
        String name = normalizeName(request.name());
        if (templateRepository.existsByNameAndIdNot(name, id)) {
            throw ApiException.conflict("时间模板名称已存在");
        }
        template.setName(name);
        validateAndFill(template, request.timezone(), request.startDayOffset(), request.startTime(),
                request.endDayOffset(), request.endTime());
        template.setRemark(trimToNull(request.remark()));
        return toVO(templateRepository.save(template));
    }

    /**
     * 逻辑停用模板；已绑定该模板的角色会因「模板已停用」而无法再计算窗口。
     */
    @Transactional
    public void disable(Long id) {
        SysTimeTemplate template = require(id);
        template.setStatus(RbacConstants.STATUS_DISABLED);
        templateRepository.save(template);
    }

    public SysTimeTemplate require(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("时间模板不存在"));
    }

    private void validateAndFill(SysTimeTemplate template, String timezone, Integer startDayOffset,
                                 String startTime, Integer endDayOffset, String endTime) {
        String zoneText = (timezone == null || timezone.isBlank()) ? DEFAULT_TIMEZONE : timezone.trim();
        ZoneId zone = zoneOf(zoneText);
        LocalTime start = RoleTimeWindowService.parseTime(startTime, "startTime");
        LocalTime end = RoleTimeWindowService.parseTime(endTime, "endTime");
        int startOffset = startDayOffset == null ? 0 : startDayOffset;
        int endOffset = endDayOffset == null ? 0 : endDayOffset;

        LocalDate anchor = LocalDate.now(zone);
        LocalDateTime startLocal = anchor.plusDays(startOffset).atTime(start);
        LocalDateTime endLocal = anchor.plusDays(endOffset).atTime(end);
        if (!endLocal.isAfter(startLocal)) {
            throw ApiException.badRequest("时间模板的结束时间必须晚于开始时间");
        }

        template.setTimezone(zoneText);
        template.setStartDayOffset(startOffset);
        template.setStartTime(start);
        template.setEndDayOffset(endOffset);
        template.setEndTime(end);
    }

    private static ZoneId zoneOf(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("时区无效: " + timezone);
        }
    }

    private static int validStatus(Integer status, int fallback) {
        int value = status == null ? fallback : status;
        if (value != RbacConstants.STATUS_ENABLED && value != RbacConstants.STATUS_DISABLED) {
            throw ApiException.badRequest("status 只能为 0 或 1");
        }
        return value;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("模板名称不能为空");
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

    public static TimeTemplateVO toVO(SysTimeTemplate template) {
        return new TimeTemplateVO(
                template.getId(),
                template.getName(),
                template.getTimezone(),
                template.getStartDayOffset(),
                RoleTimeWindowService.format(template.getStartTime()),
                template.getEndDayOffset(),
                RoleTimeWindowService.format(template.getEndTime()),
                template.getStatus(),
                template.getRemark());
    }
}
