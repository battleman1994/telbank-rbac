package com.telbank.rbac.web.admin;

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
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.service.PageActionService;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionCreateRequest;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionUpdateRequest;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionVO;

import jakarta.validation.Valid;

/**
 * 页面操作元数据（契约 §3.8），需要系统内 {@code page:manage}。
 */
@RestController
@RequestMapping("/api/page-actions")
@PreAuthorize("@perm.has('page:manage')")
public class PageActionController {

    private final PageActionService pageActionService;

    public PageActionController(PageActionService pageActionService) {
        this.pageActionService = pageActionService;
    }

    @GetMapping
    public ApiResponse<PageResult<PageActionVO>> page(@RequestParam(required = false) Long systemId,
                                                      @RequestParam(required = false) String pageCode,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(pageActionService.page(systemId, pageCode, PageQuery.of(page, size)));
    }

    @PostMapping
    public ApiResponse<PageActionVO> create(@Valid @RequestBody PageActionCreateRequest request) {
        return ApiResponse.ok(pageActionService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PageActionVO> update(@PathVariable Long id,
                                            @Valid @RequestBody PageActionUpdateRequest request) {
        return ApiResponse.ok(pageActionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        pageActionService.disable(id);
        return ApiResponse.ok();
    }
}
