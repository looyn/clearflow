-- 看板列窗口取数（虚拟滚动）所需索引：列内 ORDER BY sort,id + LIMIT 走覆盖索引，避免回表 filesort。
-- 已有库可重复执行（幂等）；新库直接用 schema.sql，已包含同名索引。

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task'
                  AND INDEX_NAME = 'idx_task_board') = 0,
               'ALTER TABLE task ADD INDEX idx_task_board (project_id, status, sort, id)',
               'SELECT ''idx_task_board 已存在，跳过''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'defect'
                  AND INDEX_NAME = 'idx_def_board') = 0,
               'ALTER TABLE defect ADD INDEX idx_def_board (project_id, status, sort, id)',
               'SELECT ''idx_def_board 已存在，跳过''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
