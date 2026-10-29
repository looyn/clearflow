-- 看板列内拖拽排序（已有库执行一次；新库直接用 schema.sql 已含该列）
ALTER TABLE task ADD COLUMN sort BIGINT NOT NULL DEFAULT 0 COMMENT '看板同状态列内手动排序值（升序，步长 1024）' AFTER status;
ALTER TABLE defect ADD COLUMN sort BIGINT NOT NULL DEFAULT 0 COMMENT '看板同状态列内手动排序值（升序，步长 1024）' AFTER status;
