package com.telbank.rbac.web.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.service.AccessApplicationService;
import com.telbank.rbac.web.dto.AccessApplicationDtos.ApplicationVO;
import com.telbank.rbac.web.dto.AccessApplicationDtos.OptionsVO;

/**
 * 业务用户临时权限申请（申请人侧）：可申请选项、我的申请分页与详情。
 *
 * <p>不加 {@code @PreAuthorize}，也不要求管理权限码；由服务层通过
 * {@code ManagementContextService.requireContext()} 校验当前业务系统与申请人，
 * 并强制 {@code system_id + external_user_id} 数据归属。</p>
 */
@RestController
@RequestMapping("/api/access-applications")
public class AccessApplicationController {

    private final AccessApplicationService accessApplicationService;

    public AccessApplicationController(AccessApplicationService accessApplicationService) {
        this.accessApplicationService = accessApplicationService;
    }

    @GetMapping("/options")
    public ApiResponse<OptionsVO> options() {
        return ApiResponse.ok(accessApplicationService.options());
    }

    @GetMapping
    public ApiResponse<PageResult<ApplicationVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(accessApplicationService.page(status, PageQuery.of(page, size)));
    }

    @GetMapping("/{applicationNo}")
    public ApiResponse<ApplicationVO> detail(@PathVariable String applicationNo) {
        return ApiResponse.ok(accessApplicationService.detail(applicationNo));
    }
}
