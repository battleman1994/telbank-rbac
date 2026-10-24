package com.telbank.rbac.web.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telbank.rbac.common.ApiResponse;
import com.telbank.rbac.service.UserSystemService;
import com.telbank.rbac.web.dto.UserSystemDtos.UserSystemCreateRequest;
import com.telbank.rbac.web.dto.UserSystemDtos.UserSystemVO;

import jakarta.validation.Valid;

/**
 * 用户-系统开通（契约 §3.7），需要 {@code rbac:system:manage}。
 */
@RestController
@RequestMapping("/api/user-systems")
@PreAuthorize("@perm.has('rbac:system:manage')")
public class UserSystemController {

    private final UserSystemService userSystemService;

    public UserSystemController(UserSystemService userSystemService) {
        this.userSystemService = userSystemService;
    }

    @GetMapping
    public ApiResponse<List<UserSystemVO>> list(@RequestParam String externalUserId) {
        return ApiResponse.ok(userSystemService.list(externalUserId));
    }

    @PostMapping
    public ApiResponse<UserSystemVO> create(@Valid @RequestBody UserSystemCreateRequest request) {
        return ApiResponse.ok(userSystemService.create(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        userSystemService.disable(id);
        return ApiResponse.ok();
    }
}
