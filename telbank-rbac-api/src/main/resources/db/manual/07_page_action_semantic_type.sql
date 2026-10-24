-- =====================================================================
-- 07_page_action_semantic_type.sql
-- 页面元素类型语义化（A15）
--
-- 背景：
--   旧种子把 type 当作样式使用（primary/danger/link/default），
--   管理页只需要知道“元素是哪一类”，不再维护样式。
--
-- 处理：
--   历史样式值归一为语义类型：
--     primary / danger / default / 空 → button
--     link                            → link
--   其他自定义值保持不变。
-- =====================================================================

SET NAMES utf8mb4;

UPDATE sys_page_action
SET type = CASE
    WHEN type IS NULL OR type = '' OR type IN ('primary', 'danger', 'default') THEN 'button'
    WHEN type = 'link' THEN 'link'
    ELSE type
END;
