-- ============================================================
-- ClearFlow 后端数据库初始化脚本（MySQL 8 / utf8mb4）
-- 幂等：全部使用 IF NOT EXISTS，可重复执行；无 Flyway，由应用启动时
-- spring.sql.init 自动执行，也可手工执行：mysql -u root -p < schema.sql
-- 本批为 M1：认证 / 成员与权限 / 项目 / 设置
-- ============================================================

-- 成员（系统级成员池；password_hash 启动时由种子初始化为 BCrypt('123456')）
CREATE TABLE IF NOT EXISTS member (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(64)  NOT NULL,
    login         VARCHAR(64)  NULL COMMENT '登录账号（可与邮箱不同，如 admin）',
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(128) NULL,
    job_title     VARCHAR(64)  NULL COMMENT '职位，对应前端 role 字段',
    perm          VARCHAR(16)  NOT NULL DEFAULT '开发' COMMENT '角色：开发/只读（项目负责人由 project.owner 承担）',
    color         VARCHAR(16)  NOT NULL DEFAULT 'sky',
    joined        VARCHAR(32)  NULL COMMENT '加入时间（展示用字符串，与 mock 一致）',
    capacity      INT          NOT NULL DEFAULT 100,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_login (login),
    KEY idx_member_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 成员 × 模块 授权（覆盖角色默认）；带项目维度：同一成员在不同项目可以有不同等级
CREATE TABLE IF NOT EXISTS permission_grant (
    project_id BIGINT      NOT NULL COMMENT '所属项目（授权只在本项目内生效）',
    member_id  BIGINT      NOT NULL,
    module_key VARCHAR(32) NOT NULL COMMENT '需求池/迭代规划/任务看板/缺陷追踪/发版计划/文档管理',
    level      VARCHAR(8)  NOT NULL COMMENT '无/查看/编辑/管理',
    PRIMARY KEY (project_id, member_id, module_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 用户偏好（当前项目）
CREATE TABLE IF NOT EXISTS user_preference (
    member_id          BIGINT NOT NULL,
    current_project_id BIGINT NULL,
    PRIMARY KEY (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 项目
CREATE TABLE IF NOT EXISTS project (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(8)   NULL COMMENT '项目代号，如 EMP',
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    owner       BIGINT       NULL COMMENT '负责人 member.id',
    state       VARCHAR(16)  NOT NULL DEFAULT 'planned' COMMENT 'planned/active/archived',
    start_date  VARCHAR(32)  NULL COMMENT '展示字符串，与 mock 一致',
    end_date    VARCHAR(32)  NULL,
    color       VARCHAR(16)  NOT NULL DEFAULT '#3370FF',
    progress    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 项目成员（引用系统成员池，联合唯一）
CREATE TABLE IF NOT EXISTS project_member (
    project_id BIGINT NOT NULL,
    member_id  BIGINT NOT NULL,
    PRIMARY KEY (project_id, member_id),
    KEY idx_pm_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 业务模块字典（项目内：支付网关 / 收银台 / 对账中心…，对齐 Jira Components）
-- 与 permission_grant.module_key（功能菜单授权）是两个概念，互不复用。
CREATE TABLE IF NOT EXISTS project_module (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    owner       BIGINT       NULL COMMENT '模块负责人 member.id',
    description VARCHAR(255) NOT NULL DEFAULT '',
    sort        INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pm_project_name (project_id, name),
    KEY idx_pm_project (project_id, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 业务编号序列（task/requirement/defect/release/doc/worklog，M2 起使用）
CREATE TABLE IF NOT EXISTS sequence (
    biz_type VARCHAR(16) NOT NULL,
    next_val BIGINT      NOT NULL,
    PRIMARY KEY (biz_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 概览页全局动态（项目/成员级操作记录，供 GET /overview 汇聚）
CREATE TABLE IF NOT EXISTS overview_activity (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    html       TEXT     NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ovact_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- M2 核心业务：迭代 / 需求 / 任务 / 缺陷
-- 业务实体主键为展示码（T-316 / R-107 / B-241 / REL-006 / DOC-4 / S24 / W-133），
-- 子表用 BIGINT 自增；日期展示字段用 VARCHAR 保持 mock 自由文本风格
-- ============================================================

-- 迭代（code 如 S24 即主键）
CREATE TABLE IF NOT EXISTS iteration (
    code       VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    name       VARCHAR(128) NOT NULL,
    iter_range VARCHAR(64)  NULL COMMENT '如 2026-10-08 ~ 2026-10-14',
    days       VARCHAR(16)  NULL,
    state      VARCHAR(16)  NOT NULL DEFAULT 'planned' COMMENT 'planned/active/released',
    pct        INT          NOT NULL DEFAULT 0,
    done       INT          NOT NULL DEFAULT 0,
    total      INT          NOT NULL DEFAULT 0,
    owner      BIGINT       NULL,
    goal       VARCHAR(512) NULL,
    retro      TEXT         NULL COMMENT '复盘：纯文本或 {summary,scores,actions} JSON',
    PRIMARY KEY (code),
    KEY idx_iter_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 迭代里程碑
CREATE TABLE IF NOT EXISTS milestone (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    project_id      BIGINT      NOT NULL,
    iteration_code  VARCHAR(16) NOT NULL,
    ms_date         VARCHAR(16) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    state           VARCHAR(8)  NOT NULL DEFAULT '' COMMENT 'done/now/空',
    PRIMARY KEY (id),
    KEY idx_ms_iter (project_id, iteration_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 迭代日快照（燃尽图数据源，唯一约束 迭代+日期）
CREATE TABLE IF NOT EXISTS iteration_snapshot (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    project_id     BIGINT      NOT NULL,
    iteration_code VARCHAR(16) NOT NULL,
    snap_date      VARCHAR(10) NOT NULL,
    remaining      INT         NOT NULL DEFAULT 0,
    done_points    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snap (project_id, iteration_code, snap_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 需求（id=R-107；status 中文枚举直存；target_release 对应前端字段 release，description 对应 desc）
CREATE TABLE IF NOT EXISTS requirement (
    id             VARCHAR(16)  NOT NULL,
    project_id     BIGINT       NOT NULL,
    module_id      BIGINT       NULL COMMENT '业务模块 project_module.id',
    title          VARCHAR(255) NOT NULL,
    type           VARCHAR(16)  NOT NULL DEFAULT '功能',
    prio           VARCHAR(8)   NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT '待评审' COMMENT '待评审/已排期/研发中/已发布',
    owner          BIGINT       NULL,
    source         VARCHAR(64)  NULL,
    iter           VARCHAR(16)  NULL COMMENT 'S24 / 待排期',
    estimate       INT          NULL,
    target_release VARCHAR(64)  NULL COMMENT '前端字段 release',
    created        VARCHAR(32)  NULL,
    completed_at   DATETIME     NULL COMMENT '首次到达终态的时间（重开时清空）',
    description    MEDIUMTEXT   NULL COMMENT '前端字段 desc',
    PRIMARY KEY (id),
    KEY idx_req_project (project_id, status),
    KEY idx_req_module (project_id, module_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 需求验收标准
CREATE TABLE IF NOT EXISTS acceptance_item (
    id      BIGINT       NOT NULL AUTO_INCREMENT,
    req_id  VARCHAR(16)  NOT NULL,
    title   VARCHAR(255) NOT NULL,
    done    TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ac_req (req_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 任务（id=T-316；due/created/updated 为展示字符串）
CREATE TABLE IF NOT EXISTS task (
    id         VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    module_id  BIGINT       NULL COMMENT '业务模块 project_module.id',
    title      VARCHAR(255) NOT NULL,
    type       VARCHAR(8)   NULL,
    prio       VARCHAR(8)   NULL,
    assignee   BIGINT       NULL,
    due        VARCHAR(32)  NULL COMMENT '待排期/今日/日期',
    hot        TINYINT(1)   NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'todo',
    sort       BIGINT       NOT NULL DEFAULT 0 COMMENT '看板同状态列内手动排序值（升序，步长 1024）',
    iter       VARCHAR(16)  NULL,
    estimate   INT          NULL,
    spent      DECIMAL(6,1) NOT NULL DEFAULT 0,
    req_id     VARCHAR(16)  NULL,
    labels     JSON         NULL,
    pred_ids   JSON         NULL COMMENT '前置任务编号列表（前端字段 predIds）',
    creator    BIGINT       NULL,
    created    VARCHAR(32)  NULL,
    updated    VARCHAR(32)  NULL,
    completed_at DATETIME   NULL COMMENT '到达终态的时间（离开终态时清空）',
    description MEDIUMTEXT  NULL COMMENT '前端字段 desc',
    PRIMARY KEY (id),
    KEY idx_task_project (project_id, status),
    KEY idx_task_board (project_id, status, sort, id),
    KEY idx_task_assignee (project_id, assignee),
    KEY idx_task_module (project_id, module_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 任务子任务
CREATE TABLE IF NOT EXISTS subtask (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    task_id    VARCHAR(16) NOT NULL,
    title      VARCHAR(255) NOT NULL,
    done       TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_subtask_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 缺陷（id=B-241）
CREATE TABLE IF NOT EXISTS defect (
    id         VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    module_id  BIGINT       NULL COMMENT '业务模块 project_module.id（未填时从关联需求继承）',
    title      VARCHAR(255) NOT NULL,
    env        VARCHAR(16)  NULL,
    prio       VARCHAR(8)   NULL,
    severity   VARCHAR(8)   NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'open',
    sort       BIGINT       NOT NULL DEFAULT 0 COMMENT '看板同状态列内手动排序值（升序，步长 1024）',
    owner      BIGINT       NULL COMMENT '当前处理人',
    reporter   VARCHAR(64)  NULL COMMENT '报告人展示名（外部反馈人如「客服-王芳」无成员账号）',
    reporter_id BIGINT      NULL COMMENT '内部报告人 member.id；外部反馈人只填 reporter',
    introduced_by BIGINT    NULL COMMENT '引入缺陷的成员（用于复盘归因，不作为个人排名指标）',
    defect_type VARCHAR(16) NOT NULL DEFAULT '功能' COMMENT '功能/性能/兼容性/安全/数据/体验',
    root_cause  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '根因分类：需求遗漏/设计缺陷/编码错误/测试遗漏/环境问题',
    fixed_in    VARCHAR(32) NULL COMMENT '修复版本',
    date       VARCHAR(32)  NULL COMMENT '发现时间',
    iter       VARCHAR(16)  NULL,
    found_in   VARCHAR(32)  NULL,
    deadline   VARCHAR(32)  NULL,
    req_id     VARCHAR(16)  NULL,
    description MEDIUMTEXT  NULL COMMENT '前端字段 desc（现象、预期、复现步骤统一写在此处）',
    spent      DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '已投入工时（由工时登记汇总回写）',
    resolved_at DATETIME    NULL COMMENT '到达已修复档的时间（重开时清空）',
    closed_at   DATETIME    NULL COMMENT '到达终态的时间（重开时清空）',
    reopen_count INT        NOT NULL DEFAULT 0 COMMENT '重开次数（终态 → 非终态）',
    PRIMARY KEY (id),
    KEY idx_def_project (project_id, status),
    KEY idx_def_board (project_id, status, sort, id),
    KEY idx_def_owner (project_id, owner),
    KEY idx_def_module (project_id, module_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 缺陷验证日志
CREATE TABLE IF NOT EXISTS verify_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    project_id BIGINT       NOT NULL,
    defect_id  VARCHAR(16)  NOT NULL,
    by_id      BIGINT       NULL,
    time       VARCHAR(32)  NULL,
    result     VARCHAR(32)  NOT NULL,
    note       VARCHAR(512) NULL,
    PRIMARY KEY (id),
    KEY idx_vl_defect (defect_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 缺陷关联（blocks / duplicate 等）
CREATE TABLE IF NOT EXISTS entity_relation (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    defect_id  VARCHAR(16) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    target     VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rel_defect (defect_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- M3 发版与协作：发版计划 / 文档 / 横切多态 / 通知
-- ============================================================

-- 发版计划（release 为 MySQL 保留字，实体用 @TableName 反引号；range/desc/read 已规避）
CREATE TABLE IF NOT EXISTS `release` (
    id         VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    version    VARCHAR(32)  NULL,
    name       VARCHAR(255) NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'planning',
    rel_date   VARCHAR(32)  NULL COMMENT '计划发版日期，前端字段 date',
    owner      BIGINT       NULL,
    channel    VARCHAR(32)  NULL,
    risk       VARCHAR(8)   NULL,
    notes      MEDIUMTEXT   NULL COMMENT '富文本发版说明',
    req_ids    JSON         NULL COMMENT '关联需求 id 数组',
    defect_ids JSON         NULL COMMENT '关联缺陷 id 数组',
    created    VARCHAR(32)  NULL,
    updated    VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_rel_project (project_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 文档（id=DOC-4，正文 Markdown）
CREATE TABLE IF NOT EXISTS doc (
    id         VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    category   VARCHAR(64)  NULL,
    content    MEDIUMTEXT   NULL,
    owner      BIGINT       NULL,
    created    VARCHAR(32)  NULL,
    updated    VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_doc_project (project_id, category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 文档分类字典（系统级，与 mock docCategories 一致）
CREATE TABLE IF NOT EXISTS doc_category (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_cat (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 评论（多态 kind+ref_id；kind 存请求原值，req/requirement 别名查询时归一）
CREATE TABLE IF NOT EXISTS comment (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    kind       VARCHAR(16) NOT NULL,
    ref_id     VARCHAR(16) NOT NULL,
    member_id  BIGINT      NOT NULL,
    text       TEXT        NOT NULL,
    mentions   JSON        NULL COMMENT '被 @ 的成员 id 数组',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_cmt_ref (project_id, kind, ref_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 附件（entity_kind 多态 + file_type 为 image/file，避免 kind 语义冲突）
CREATE TABLE IF NOT EXISTS attachment (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    project_id  BIGINT      NOT NULL,
    entity_kind VARCHAR(16) NOT NULL,
    ref_id      VARCHAR(16) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    size        BIGINT      NOT NULL DEFAULT 0,
    file_type   VARCHAR(16) NOT NULL DEFAULT 'file',
    url         VARCHAR(512) NULL,
    storage_key VARCHAR(512) NULL COMMENT 'RustFS 对象键，读取时预签名',
    member_id   BIGINT      NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_att_ref (project_id, entity_kind, ref_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 关注者（联合唯一）
CREATE TABLE IF NOT EXISTS watcher (
    project_id  BIGINT      NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    ref_id      VARCHAR(16) NOT NULL,
    member_id   BIGINT      NOT NULL,
    PRIMARY KEY (project_id, kind, ref_id, member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 实体动态（后端生成，审计可信；created_at 为排序时间）
CREATE TABLE IF NOT EXISTS activity (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    kind       VARCHAR(16) NOT NULL,
    ref_id     VARCHAR(16) NOT NULL,
    member_id  BIGINT      NULL,
    type       VARCHAR(16) NOT NULL COMMENT 'create/status/field/comment/attach/subtask/verify/watch',
    text       TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_act_ref (project_id, kind, ref_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 结构化状态流转事件（周期/停留时长/重开率的唯一口径；与 activity 的展示文本解耦）
CREATE TABLE IF NOT EXISTS status_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    project_id  BIGINT      NOT NULL,
    kind        VARCHAR(16) NOT NULL COMMENT 'task / defect / requirement',
    entity_id   VARCHAR(16) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status   VARCHAR(32) NOT NULL,
    by_member   BIGINT      NULL,
    at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_se_entity (project_id, kind, entity_id, at),
    KEY idx_se_flow (project_id, kind, to_status, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 站内通知（kind：mention/assign/release/defect/due/status/ci；is_read 对应前端 read）
-- 查询/已读/删除均限定 member_id = 当前登录人，idx_notify_member 覆盖未读数与分页
CREATE TABLE IF NOT EXISTS notification (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    is_read    TINYINT(1)  NOT NULL DEFAULT 0,
    kind       VARCHAR(16) NOT NULL,
    title      VARCHAR(128) NULL,
    text       TEXT        NOT NULL,
    ref_kind   VARCHAR(16) NULL,
    ref_id     VARCHAR(32) NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notify_member (member_id, is_read, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- 已有库升级段（幂等）：富文本列 TEXT → MEDIUMTEXT，容纳编辑器内嵌图片（base64）等长内容
-- 缺陷 steps 列已废弃（复现步骤并入 description），已有库请执行 migrate_defect_steps.sql
-- ============================================================
ALTER TABLE requirement MODIFY COLUMN description MEDIUMTEXT NULL COMMENT '前端字段 desc';
ALTER TABLE task         MODIFY COLUMN description MEDIUMTEXT NULL COMMENT '前端字段 desc';
ALTER TABLE defect       MODIFY COLUMN description MEDIUMTEXT NULL COMMENT '前端字段 desc';
ALTER TABLE `release`    MODIFY COLUMN notes       MEDIUMTEXT NULL COMMENT '富文本发版说明';

-- 工时记录（id=W-133；who→member_id、date→work_date、task→ref_text 为前端字段映射）
CREATE TABLE IF NOT EXISTS worklog (
    id         VARCHAR(16)  NOT NULL,
    project_id BIGINT       NOT NULL,
    member_id  BIGINT       NOT NULL,
    work_date  VARCHAR(32)  NULL,
    ref_text   VARCHAR(128) NULL COMMENT '前端字段 task（关联对象编号 + 标题，或自定义内容）',
    ref_kind   VARCHAR(16)  NULL COMMENT '关联类型：task / defect / other（NULL 为旧数据，按 ref_text 推断）',
    ref_id     VARCHAR(16)  NULL COMMENT '关联实体编号（T-xxx / B-xxx；其他为空）',
    hours      DECIMAL(5,1) NOT NULL DEFAULT 0,
    note       VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_wl_project (project_id, work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- 项目级设置：工作流 / 选项配置，每项目独立
-- ============================================================
CREATE TABLE IF NOT EXISTS project_setting (
    project_id BIGINT      NOT NULL,
    section    VARCHAR(32) NOT NULL,
    data       JSON        NOT NULL,
    PRIMARY KEY (project_id, section),
    CONSTRAINT fk_pset_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- 成员级设置：通知开关等个人偏好，每人一份
-- ============================================================
CREATE TABLE IF NOT EXISTS member_setting (
    member_id BIGINT      NOT NULL,
    section   VARCHAR(32) NOT NULL,
    data      JSON        NOT NULL,
    PRIMARY KEY (member_id, section),
    CONSTRAINT fk_mset_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 历史遗留的全局 settings 表已废弃：workflow/options 已迁到 project_setting，
-- notify 已迁到 member_setting（每人一份），profile 已删除。这里真正删掉旧表。
DROP TABLE IF EXISTS settings;

-- ============================================================
-- 已有库升级段（幂等）：created/updated 的 mock 短格式（MM.DD，如 10.16）
-- 统一为完整时间戳（2026-MM-DD 00:00:00；短格式均产生于 2026 年）
-- ============================================================
UPDATE task SET created = CONCAT('2026-', SUBSTRING(created, 1, 2), '-', SUBSTRING(created, 4, 2), ' 00:00:00')
WHERE created REGEXP '^[0-9]{2}\\.[0-9]{2}$';
UPDATE task SET updated = CONCAT('2026-', SUBSTRING(updated, 1, 2), '-', SUBSTRING(updated, 4, 2), ' 00:00:00')
WHERE updated REGEXP '^[0-9]{2}\\.[0-9]{2}$';
UPDATE defect SET date = CONCAT('2026-', SUBSTRING(date, 1, 2), '-', SUBSTRING(date, 4, 2), ' 00:00:00')
WHERE date REGEXP '^[0-9]{2}\\.[0-9]{2}$';

-- ============================================================
-- 已有库升级段（幂等）：工作空间（workspace）已废弃，清理相关表与列
-- ============================================================
DROP TABLE IF EXISTS workspace;

SET @drop_project_ws := (
    SELECT IF(COUNT(*) > 0, 'ALTER TABLE project DROP COLUMN ws', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND COLUMN_NAME = 'ws'
);
PREPARE stmt FROM @drop_project_ws;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_pref_ws := (
    SELECT IF(COUNT(*) > 0, 'ALTER TABLE user_preference DROP COLUMN current_workspace_id', 'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference' AND COLUMN_NAME = 'current_workspace_id'
);
PREPARE stmt FROM @drop_pref_ws;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 已有库升级段（幂等）：授权改为「按项目隔离」
-- permission_grant 增加 project_id 并把主键改成 (project_id, member_id, module_key)：
-- 旧数据（全局授权）展开为「该成员所在每个项目各一条」，语义不变。
-- ============================================================
SET @add_grant_pid := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE permission_grant ADD COLUMN project_id BIGINT NOT NULL DEFAULT 0 COMMENT ''所属项目''',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'permission_grant' AND COLUMN_NAME = 'project_id'
);
PREPARE stmt FROM @add_grant_pid;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 先摘掉旧主键 (member_id, module_key)：否则展开出的行会撞旧主键被 INSERT IGNORE 丢掉
SET @drop_grant_pk := (
    SELECT IF(COUNT(*) = 0, 'ALTER TABLE permission_grant DROP PRIMARY KEY', 'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'permission_grant'
      AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'project_id'
);
PREPARE stmt FROM @drop_grant_pk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO permission_grant (project_id, member_id, module_key, level)
SELECT pm.project_id, g.member_id, g.module_key, g.level
FROM permission_grant g
JOIN project_member pm ON pm.member_id = g.member_id
WHERE g.project_id = 0;

DELETE FROM permission_grant WHERE project_id = 0;

-- 已下线模块（度量报表 / 成员与权限）：其权限已由「系统管理员或项目负责人」身份直接决定
DELETE FROM permission_grant
WHERE module_key NOT IN ('需求池', '迭代规划', '任务看板', '缺陷追踪', '发版计划', '文档管理');

SET @grant_pk := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE permission_grant ADD PRIMARY KEY (project_id, member_id, module_key), ALTER COLUMN project_id DROP DEFAULT',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'permission_grant'
      AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'project_id'
);
PREPARE stmt FROM @grant_pk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 角色收敛为「开发 / 只读」：历史「管理员」角色的成员改为「开发」
-- （系统管理员是内置 admin 账号，由账号身份决定，不再是角色）
UPDATE member SET perm = '开发' WHERE perm NOT IN ('开发', '只读');

-- ============================================================
-- 已有库升级段（幂等）：工时支持任务/缺陷实体引用，缺陷增加已投入工时
-- 新增列后旧记录按 ref_text 前缀回填关联类型与编号；其余保持 NULL，读取时按「其他」处理
-- ============================================================
SET @add_def_spent := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE defect ADD COLUMN spent DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT ''已投入工时（由工时登记汇总回写）''',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'defect' AND COLUMN_NAME = 'spent'
);
PREPARE stmt FROM @add_def_spent;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_wl_kind := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE worklog ADD COLUMN ref_kind VARCHAR(16) NULL COMMENT ''关联类型：task / defect / other''',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worklog' AND COLUMN_NAME = 'ref_kind'
);
PREPARE stmt FROM @add_wl_kind;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_wl_ref := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE worklog ADD COLUMN ref_id VARCHAR(16) NULL COMMENT ''关联实体编号（T-xxx / B-xxx）''',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worklog' AND COLUMN_NAME = 'ref_id'
);
PREPARE stmt FROM @add_wl_ref;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE worklog
SET ref_kind = 'task', ref_id = SUBSTRING_INDEX(TRIM(ref_text), ' ', 1)
WHERE ref_kind IS NULL AND TRIM(ref_text) REGEXP '^T-[0-9]+([[:space:]]|$)';

UPDATE worklog
SET ref_kind = 'defect', ref_id = SUBSTRING_INDEX(TRIM(ref_text), ' ', 1)
WHERE ref_kind IS NULL AND TRIM(ref_text) REGEXP '^B-[0-9]+([[:space:]]|$)';
