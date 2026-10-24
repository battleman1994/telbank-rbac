package com.telbank.rbac.domain.mapper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.telbank.rbac.domain.entity.BaseEntity;

/**
 * 权限中心 Mapper 基类：在 MyBatis-Plus {@link BaseMapper} 之上提供与原 Spring Data
 * Repository 语义一致的便捷方法（save/insert-or-update、findById、findAllById、existsById、saveAll），
 * 使 Service 层从 JPA 迁移时保持表达清晰。
 *
 * @param <T> 实体类型
 */
public interface RbacMapper<T extends BaseEntity> extends BaseMapper<T> {

    /**
     * 保存（id 为空 insert，否则 updateById），与原 JPA save 语义一致。
     */
    default T save(T entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<T> saveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        for (T entity : entities) {
            saved.add(save(entity));
        }
        return saved;
    }

    default Optional<T> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default boolean existsById(Long id) {
        return selectById(id) != null;
    }

    default List<T> findAllById(Iterable<Long> ids) {
        Collection<Serializable> keys = new ArrayList<>();
        for (Long id : ids) {
            keys.add(id);
        }
        return keys.isEmpty() ? List.of() : selectBatchIds(keys);
    }
}
