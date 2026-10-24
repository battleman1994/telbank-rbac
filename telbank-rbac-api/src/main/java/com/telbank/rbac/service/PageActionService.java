package com.telbank.rbac.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.common.JsonPayloads;
import com.telbank.rbac.common.PageQuery;
import com.telbank.rbac.common.PageResult;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.repository.SysPageActionRepository;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionCreateRequest;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionUpdateRequest;
import com.telbank.rbac.web.dto.PageActionDtos.PageActionVO;

import tools.jackson.databind.json.JsonMapper;

/**
 * 页面操作元数据管理（契约 §3.8，模式 B）。
 */
@Service
public class PageActionService {

    private final SysPageActionRepository pageActionRepository;
    private final SysSystemRepository systemRepository;
    private final LocalCacheManager localCacheManager;
    private final ChangeNotifyService changeNotifyService;
    private final JsonMapper jsonMapper;

    public PageActionService(SysPageActionRepository pageActionRepository,
                             SysSystemRepository systemRepository,
                             LocalCacheManager localCacheManager,
                             ChangeNotifyService changeNotifyService,
                             JsonMapper jsonMapper) {
        this.pageActionRepository = pageActionRepository;
        this.systemRepository = systemRepository;
        this.localCacheManager = localCacheManager;
        this.changeNotifyService = changeNotifyService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<PageActionVO> page(Long systemId, String pageCode, PageQuery pageQuery) {
        if (systemId == null) {
            throw ApiException.badRequest("systemId is required");
        }
        String normalizedPageCode = pageCode == null ? "" : pageCode.trim();
        Page<SysPageAction> page = pageActionRepository.search(systemId, normalizedPageCode,
                pageQuery.toPageable(Sort.by(Sort.Order.asc("sort"), Sort.Order.asc("id"))));
        return PageResult.of(page.getContent().stream().map(PageActionService::toVO).toList(),
                page.getTotalElements(), pageQuery);
    }

    @Transactional
    public PageActionVO create(PageActionCreateRequest request) {
        if (!systemRepository.existsById(request.systemId())) {
            throw ApiException.notFound("system not found: " + request.systemId());
        }
        String pageCode = request.pageCode().trim();
        String code = request.code().trim();
        if (pageActionRepository.existsBySystemIdAndPageCodeAndCode(request.systemId(), pageCode, code)) {
            throw ApiException.conflict("page action already exists: " + pageCode + " / " + code);
        }
        SysPageAction action = new SysPageAction();
        action.setSystemId(request.systemId());
        action.setPageCode(pageCode);
        action.setCode(code);
        applyEditableFields(action, request.text(), request.type(), request.action(),
                request.config(), request.sort(), request.status());
        SysPageAction saved = pageActionRepository.save(action);
        invalidateAndPublish(saved.getSystemId());
        return toVO(saved);
    }

    @Transactional
    public PageActionVO update(Long id, PageActionUpdateRequest request) {
        SysPageAction action = pageActionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("page action not found: " + id));
        String pageCode = request.pageCode().trim();
        String code = request.code().trim();
        if ((!pageCode.equals(action.getPageCode()) || !code.equals(action.getCode()))
                && pageActionRepository.existsBySystemIdAndPageCodeAndCodeAndIdNot(
                        action.getSystemId(), pageCode, code, id)) {
            throw ApiException.conflict("page action already exists: " + pageCode + " / " + code);
        }
        action.setPageCode(pageCode);
        action.setCode(code);
        applyEditableFields(action, request.text(), request.type(), request.action(),
                request.config(), request.sort(), request.status());
        SysPageAction saved = pageActionRepository.save(action);
        invalidateAndPublish(saved.getSystemId());
        return toVO(saved);
    }

    /**
     * DELETE 为停用。
     */
    @Transactional
    public void disable(Long id) {
        SysPageAction action = pageActionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("page action not found: " + id));
        action.setStatus(RbacConstants.STATUS_DISABLED);
        pageActionRepository.save(action);
        invalidateAndPublish(action.getSystemId());
    }

    private void applyEditableFields(SysPageAction action, String text, String type, String actionName,
                                     tools.jackson.databind.JsonNode config, Integer sort, Integer status) {
        action.setText(text == null ? null : text.trim());
        action.setType(type == null || type.isBlank() ? "default" : type.trim());
        action.setAction(validateAction(actionName));
        try {
            action.setConfig(JsonPayloads.normalize(config, jsonMapper));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
        if (sort != null) {
            action.setSort(sort);
        }
        if (status != null) {
            action.setStatus(status);
        }
    }

    private static String validateAction(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            throw ApiException.badRequest("action is required");
        }
        String normalized = actionName.trim();
        if (!RbacConstants.PAGE_ACTION_TYPES.contains(normalized)) {
            throw ApiException.badRequest("action must be one of " + RbacConstants.PAGE_ACTION_TYPES);
        }
        return normalized;
    }

    private void invalidateAndPublish(Long systemId) {
        localCacheManager.invalidatePageActions(systemId);
        changeNotifyService.publishAfterCommit(systemId, RbacConstants.CHANGE_TYPE_PAGE_ACTION);
    }

    public static PageActionVO toVO(SysPageAction action) {
        return new PageActionVO(action.getId(), action.getSystemId(), action.getPageCode(), action.getCode(),
                action.getText(), action.getType(), action.getAction(), action.getConfig(),
                action.getSort(), action.getStatus());
    }
}
