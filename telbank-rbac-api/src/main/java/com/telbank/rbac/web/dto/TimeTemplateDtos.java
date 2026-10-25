package com.telbank.rbac.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 角色时间模板（A21）相关 DTO。
 */
public final class TimeTemplateDtos {

    private TimeTemplateDtos() {
    }

    /**
     * 模板视图：{@code startTime/endTime} 输出为 {@code HH:mm} 字符串。
     */
    public record TimeTemplateVO(Long id,
                                 String name,
                                 String timezone,
                                 Integer startDayOffset,
                                 String startTime,
                                 Integer endDayOffset,
                                 String endTime,
                                 Integer status,
                                 String remark) {
    }

    public record TimeTemplateCreateRequest(@NotBlank @Size(max = 100) String name,
                                            @Size(max = 64) String timezone,
                                            @NotNull @Min(0) @Max(3650) Integer startDayOffset,
                                            @NotBlank String startTime,
                                            @NotNull @Min(0) @Max(3650) Integer endDayOffset,
                                            @NotBlank String endTime,
                                            Integer status,
                                            @Size(max = 255) String remark) {
    }

    public record TimeTemplateUpdateRequest(@NotBlank @Size(max = 100) String name,
                                            @Size(max = 64) String timezone,
                                            @NotNull @Min(0) @Max(3650) Integer startDayOffset,
                                            @NotBlank String startTime,
                                            @NotNull @Min(0) @Max(3650) Integer endDayOffset,
                                            @NotBlank String endTime,
                                            @Size(max = 255) String remark) {
    }
}
