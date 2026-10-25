package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.TimeTemplateService;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateCreateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateUpdateRequest;
import com.telbank.rbac.web.dto.TimeTemplateDtos.TimeTemplateVO;

import jakarta.validation.Valid;

/**
 * 角色时间模板（A21）：读取需系统内 {@code role:manage}，写操作仅平台超管。
 */
@RestController
@RequestMapping("/api/time-templates")
public class TimeTemplateController {

    private final TimeTemplateService timeTemplateService;

    public TimeTemplateController(TimeTemplateService timeTemplateService) {
        this.timeTemplateService = timeTemplateService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('role:manage')")
    public ApiResponse<List<TimeTemplateVO>> list(@RequestParam(required = false) Integer status) {
        return ApiResponse.ok(timeTemplateService.list(status));
    }

    @PostMapping
    @PreAuthorize("@perm.isPlatformAdmin()")
    public ApiResponse<TimeTemplateVO> create(@Valid @RequestBody TimeTemplateCreateRequest request) {
        return ApiResponse.ok(timeTemplateService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.isPlatformAdmin()")
    public ApiResponse<TimeTemplateVO> update(@PathVariable Long id,
                                              @Valid @RequestBody TimeTemplateUpdateRequest request) {
        return ApiResponse.ok(timeTemplateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.isPlatformAdmin()")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        timeTemplateService.disable(id);
        return ApiResponse.ok();
    }
}
