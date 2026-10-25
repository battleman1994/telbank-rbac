package com.telbank.rbac.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysTimeTemplate;
import com.telbank.rbac.domain.mapper.SysTimeTemplateMapper;

/**
 * 角色时间窗口计算（A21）。
 *
 * <p>时间模板本身只描述「相对锚点日期的窗口」，真正的绝对生效窗口在写入用户角色或审批授权时，
 * 以「写入时刻」为锚点计算：</p>
 *
 * <pre>
 * anchor      = now() 转换为模板时区
 * startLocal  = anchor 日期 + start_day_offset 天 at start_time
 * endLocal    = anchor 日期 + end_day_offset   天 at end_time
 * </pre>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>计算出的本地结束时间必须晚于开始时间，否则 400；</li>
 *   <li>结束时刻不晚于当前时刻 → 409「时间窗口已失效」，不自动滚动到下一个周期；</li>
 *   <li>开始时刻已过 → clamp 到当前时刻（{@code max(start, now)}）；</li>
 *   <li>无模板角色 = 永久角色，返回 {@link Optional#empty()}。</li>
 * </ul>
 */
@Service
public class RoleTimeWindowService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final SysTimeTemplateMapper templateRepository;

    public RoleTimeWindowService(SysTimeTemplateMapper templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * 角色绑定的时间窗口；永久角色（无模板）返回 empty。
     *
     * @throws com.telbank.rbac.common.ApiException 409 模板不存在/已停用，或模板窗口已失效
     */
    public Optional<TimeWindow> windowFor(SysRole role) {
        if (role == null || role.getTimeTemplateId() == null) {
            return Optional.empty();
        }
        SysTimeTemplate template = requireEnabled(role.getTimeTemplateId());
        return Optional.of(compute(template));
    }

    /**
     * 读取启用中的模板，不存在或已停用 → 409。
     */
    public SysTimeTemplate requireEnabled(Long templateId) {
        return templateRepository.findById(templateId)
                .filter(template -> Objects.equals(template.getStatus(), RbacConstants.STATUS_ENABLED))
                .orElseThrow(() -> ApiException.conflict("时间模板不存在或已停用"));
    }

    public TimeWindow compute(SysTimeTemplate template) {
        return compute(template, Instant.now());
    }

    /**
     * 以给定锚点时刻计算窗口（便于测试注入固定时间）。
     */
    public TimeWindow compute(SysTimeTemplate template, Instant anchor) {
        ZoneId zone = zoneOf(template.getTimezone());
        Instant anchorInstant = anchor == null ? Instant.now() : anchor;
        LocalDate anchorDate = anchorInstant.atZone(zone).toLocalDate();

        LocalTime startTime = template.getStartTime();
        LocalTime endTime = template.getEndTime();
        if (startTime == null || endTime == null) {
            throw ApiException.badRequest("时间模板的开始/结束时间不能为空");
        }

        int startOffset = template.getStartDayOffset() == null ? 0 : template.getStartDayOffset();
        int endOffset = template.getEndDayOffset() == null ? 0 : template.getEndDayOffset();
        LocalDateTime startLocal = anchorDate.plusDays(startOffset).atTime(startTime);
        LocalDateTime endLocal = anchorDate.plusDays(endOffset).atTime(endTime);
        if (!endLocal.isAfter(startLocal)) {
            throw ApiException.badRequest("时间模板的结束时间必须晚于开始时间");
        }

        Instant startInstant = startLocal.atZone(zone).toInstant();
        Instant endInstant = endLocal.atZone(zone).toInstant();
        if (!endInstant.isAfter(anchorInstant)) {
            throw ApiException.conflict("按时间模板计算的时间窗口已失效，请调整模板或稍后再授权");
        }
        Instant from = startInstant.isAfter(anchorInstant) ? startInstant : anchorInstant;
        return new TimeWindow(from, endInstant);
    }

    /**
     * 模板文案，例如「审批后当天 19:00 ~ 次日 07:00」「审批后第3天 08:00 ~ 第5天 18:00」。
     */
    public static String describe(SysTimeTemplate template) {
        return "审批后" + dayLabel(template.getStartDayOffset() == null ? 0 : template.getStartDayOffset())
                + " " + format(template.getStartTime())
                + " ~ "
                + dayLabel(template.getEndDayOffset() == null ? 0 : template.getEndDayOffset())
                + " " + format(template.getEndTime());
    }

    public static String format(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private static String dayLabel(int offset) {
        if (offset == 0) {
            return "当天";
        }
        if (offset == 1) {
            return "次日";
        }
        return "第" + offset + "天";
    }

    private static ZoneId zoneOf(String timezone) {
        String zoneText = (timezone == null || timezone.isBlank()) ? DEFAULT_TIMEZONE : timezone.trim();
        try {
            return ZoneId.of(zoneText);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("时区无效: " + zoneText);
        }
    }

    /**
     * 解析 {@code HH:mm}；供模板校验复用。
     */
    static LocalTime parseTime(String text, String field) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空，格式 HH:mm");
        }
        try {
            return LocalTime.parse(text.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 必须为 HH:mm 格式");
        }
    }

    /**
     * 授权窗口（UTC 绝对时间）。
     */
    public record TimeWindow(Instant from, Instant until) {
    }
}
