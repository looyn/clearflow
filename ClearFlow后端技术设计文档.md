# ClearFlow 清流 · 后端技术设计文档

> 版本：v1.1（选型确认稿）｜日期：2026-10-12｜状态：技术选型已确认，待最终评审后编码
>
> 本文档基于对 `clearflow-web` 前端的全局分析编写。
>
> **已拍板决策（v1.1）：**
> 1. 技术栈：**MySQL + Spring Boot 4**；
> 2. 业务表统一加 **`project_id` 按项目隔离**；
> 3. **服务端分页**；
> 4. 邮件**只生成内容**，不真实发送；
> 5. 附件使用 **RustFS 对象存储**。

---

## 1. 现状分析：前端给了后端什么约束

### 1.1 技术栈现状

| 项 | 现状 |
|---|---|
| 框架 | Vue 3.5 + Vue Router 4 + Pinia + Vite 5 |
| 富文本 | wangEditor（发版说明等 HTML 内容）、vditor（文档 Markdown） |
| 请求层 | [request.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/request.js) 统一封装，`USE_MOCK = true` 走内存 mock，`false` 走 `fetch('/api' + path)` |
| API 层 | [api/index.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/index.js) 约 80 个接口函数，URL 路径已全部定义 |
| 数据源 | [mock/data.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/mock/data.js) 内存数据库，即"事实上的数据字典" |
| 认证 | 仅前端标志位（localStorage `clearflow_logged`），**无任何真实登录接口** |
| 分页 | [AppPager.vue](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/components/AppPager.vue) 全站统一分页组件，目前各列表页**拉全量后本地 slice** |

### 1.2 关键约束（决定后端形态）

**约束一：成功响应直接返回业务数据本体。**
`USE_MOCK = false` 时 `request.js` 直接 `return res.json()` 透传给视图，**没有 `{ code, data }` 信封解包**。因此：
- 成功响应直接返回业务数据（数组、对象或分页对象）；
- 错误用 HTTP 状态码表达，body 为 `{ message }`；
- 每个接口的响应形状以 `api/index.js` 中 `mock: () => ...` 的返回值为基准，这是硬契约。

**约束二：URL 路径零改动。**
`api/index.js` 已定义全部路径（如 `/requirements/{id}/status`、`/bulk/update`、`/perm/{memberId}`）。后端按此实现，前端只改 `USE_MOCK = false` 即可切换（分页、附件两处例外，见约束三）。

**约束三：服务端分页与附件上传需要前端同步改造**（已确认采用服务端分页，见 §6.1 分页契约）：
- 列表页从"全量 + 本地 slice"改为"传 page/pageSize/筛选参数 → 渲染返回的 list"，`AppPager` 组件本身**不变**（它本来就是 props 驱动）；
- mock 端需同步模拟分页返回，保证 `USE_MOCK=true` 下前端改造后仍可用；
- 附件从"只传 meta（url 为空）"改为"先上传文件拿 url，再保存 meta"。

### 1.3 前端必须配合改造的点

1. `request.js`：增加 `Authorization: Bearer <token>` 头、401 统一跳登录、统一错误提示；
2. `LoginView.vue` + `stores/auth.js`：从"任意账号直接 `auth.login()`"改为调 `POST /api/auth/login`，存 token 与用户信息；
3. `vite.config.js`：增加 `server.proxy: { '/api': 'http://localhost:8080' }` 开发代理；
4. **列表页分页改造**：需求池、缺陷追踪、发版计划、文档、成员、项目、工时记录等列表改为调用分页接口（筛选参数一并下移服务端），mock 同步返回分页结构；
5. **附件上传改造**：文件选择 → `POST /api/files/upload` → 拿到 url → 调既有 `POST /attachments` 保存 meta；
6. 时间归一化：后端统一返回标准时间，展示格式（`10.10`、`今日` 等）由前端格式化层统一处理（见 §8 待确认 3）。

---

## 2. 业务逻辑理解（请重点评审此章）

### 2.1 领域全景

```
project（项目，state: planned / active / archived）
  ├── members[]（项目成员 ⊆ 系统成员池，唯一入口为「管理成员」弹窗）
  ├── iteration（迭代 S24…，state: planned / active / released）
  │     └── milestones[]、retro（复盘）
  ├── requirement（需求 R-xxx）
  │     └── acceptance[]（验收标准清单）
  ├── task（任务 T-xxx，看板卡片）
  │     ├── subtasks[]（子任务）
  │     └── reqId → 关联需求
  ├── defect（缺陷 B-xxx）
  │     ├── verifyLog[]（验证日志）
  │     └── relates[]（关联：blocks / duplicate …）
  ├── release（发版计划 REL-xxx）
  │     ├── reqIds[]、defectIds[]（关联发布内容）
  │     └── notes（wangEditor HTML 发版说明）
  └── doc（文档 DOC-xxx，Markdown 正文，category 取自分类字典）

系统级：member（成员池，含密码、perm 角色）、settings、permMatrix（权限矩阵）
多态横切：comment / attachment / watcher / activity（统一以 kind + 实体id 挂载）
```

**理解要点：**
- 成员是**系统级资源池**，项目成员是从池中挑选的引用——不得在项目内直接创建新成员并入池（已确认的产品约束）；
- comment/attachment/watcher/activity 四类数据是**多态关联**（kind ∈ task/defect/requirement/release），后端用统一的 `kind + ref_id` 列设计，与前端 `COLLECTION` 映射一致；
- `kind` 存在别名：`req` 与 `requirement` 等价（评论用 `req`，实体用 `requirement`），后端需归一化处理。

### 2.2 状态机（核心业务规则）

**任务 task.status**：`todo 待处理 → doing 进行中 → review 待验收 → done 已完成`
- 任务看板支持拖拽跨列，且有"已完成列折叠 37 条存量"逻辑 → 后端不强制单向流转，**校验值合法 + 全量记录变更动态**即可。

**缺陷 defect.status**：`open 待处理 → fixing 修复中 → fixed 已修复 → verified 已验证 → closed 已关闭`
- 单向流转；`verified` 前应存在至少一条 `verifyLog`（验证结果：复现/不复现/通过等）；
- 关键校验：P0/P1 缺陷有 `deadline` 修复期限，逾期进入通知/统计口径。

**发版计划 release.status**：`planning 筹备中 → testing 测试中 → ready 待发布 → released 已发布`，旁路 `rolled 已回滚`（仅 released 可回滚）。
- 状态变更写入 activity（如「状态 筹备中 → 测试中」），并触发通知（关注者 + 负责人，受 notify 开关 `release` 控制）。

**需求 requirement.status**（中文枚举直存）：`待评审 → 已排期 → 研发中 → 已发布`
- `planRequirements(ids, iter)`：排期进迭代时批量置 `iter = Sxx` 且 `status = 已排期`；
- 置为 `已发布` 时，若 `release === '—'` 则回填目标版本（mock 写死 v2.24.0，后端应取"包含该需求的最近已发布发版计划"的 version）。

**项目 project.state**：`planned → active → archived`（归档为 PUT /projects/{id}/archive）。

### 2.3 活动流与审计（Activity）

后端最重要的横切逻辑，`api/index.js` 中 `logFieldChanges` 已给出规则：

- **关键字段**：`status / assignee / owner / prio / severity / iter / date / risk / channel`；
- 变更时在**覆盖旧值之前**比对 old→new，生成形如「将状态 待处理 → 进行中」「将优先级 P1 → P0」的记录；
- 其余变更记为 `field` 类型；另有 `create / comment / attach / subtask / verify / watch` 类型；
- 每条记录含 `who / type / text / time`，**升序**返回给详情弹窗；
- 全局动态流 `/activity/feed` 汇聚四类实体的 activity + 概览动态，**时间倒序**取 top N。

**设计决策：活动记录必须在后端生成**（Spring 侧用 AOP 环绕通知或统一 Service 基类），以库中旧值与请求 patch 比对，不信任前端传入文本，保证审计可信。

### 2.4 权限模型（两层叠加）

```
第一层：角色 → 默认权限     管理员 / 开发 / 只读
第二层：成员级授权覆盖       grants[memberId][模块] = 查看|编辑|管理（缺省按角色推导）
模块（7个）：需求池、迭代规划、任务看板、缺陷追踪、发版计划、度量报表、成员与权限
```

- 接口形态：`PUT /perm/{memberId}` body `{ moduleKey, level }`；
- 后端实现：Spring Security 自定义 `@RequireModule(level)` 注解 + 拦截器，按「模块 × 成员」解析有效等级，`查看=读、编辑=写、管理=写+管理操作（删除/归档/授权）`；
- `成员与权限` 模块仅「管理」等级可改他人授权与密码。

### 2.5 通知与邮件

**站内通知（notification 表）触发点：**

| 触发 | 条件 | 受控开关（settings.notify） |
|---|---|---|
| 评论 @我 | 评论文本命中 `@姓名`，回写 mentions 并为命中成员生成通知 | mention |
| 任务/缺陷指派 | assignee/owner 变更为 X | assign |
| 发版计划状态变更 | release.status 变更，通知负责人+关注者 | release |
| P0/P1 缺陷新增 | prio ∈ p0/p1 | defect |
| 到期提醒 | 任务 due 当日（`@Scheduled` 定时扫描） | — |

**邮件（已确认：一期只生成内容，不发送）：**
- **不接 SMTP、不设发信定时任务**；
- 每日站报：`GET` 类接口按"我的待办"（mywork 口径）**渲染站报内容**（HTML/文本），前端预览或复制；
- 发版通知邮件：由发版计划的 `reqIds/defectIds/notes` **生成邮件内容**（模板渲染后返回），前端预览/复制；
- 未来要真实发送时，仅需增加 SMTP 配置 + 定时任务调用同一内容生成服务，架构上已预留。

### 2.6 聚合统计类接口（非 CRUD）

以下接口返回**计算结果**，后端需实现聚合查询：

| 接口 | 数据来源与算法 |
|---|---|
| `/overview` | KPI：本周需求吞吐、迭代完成度（done/total）、缺陷存量 P0/P1、按时交付率；teamLoad：成员 estimate/spent 负载；risks 目前为静态录入 |
| `/board` | 按 status 分四列 + doneHidden（已折叠存量数）——**不分页** |
| `/metrics` | 燃尽（迭代每日剩余点数序列）、流速（每迭代 committed/completed）、周期（可由 activity 的 status 变更时间戳计算）、DORA（由发版/回滚数据派生） |
| `/worklog` | 记录列表（**分页**）+ 汇总（总工时、人均、按成员、按类型、按日） |
| `/mywork` | 按当前登录用户过滤：未完成任务、缺陷、负责的发版与需求 |
| `/search` | 全局搜索（⌘K）：五类实体按 id+title 模糊匹配，top 12（不分页） |
| `/defects/stats`、`/releases/stats` | 状态计数 |
| `/iterations` | `{ list, capacity, stats }` 三段聚合 |

**燃尽图需要历史快照**：`actual: [60, 41]` 是"每日剩余"序列，事后无法从当前状态反推 → 需要 `iteration_snapshot` 日表（`@Scheduled` 每日定时记录剩余点数/已完成数）。建议日快照，简单可靠。

### 2.7 ID 生成策略

前端现有 ID 均为「前缀 + 序号」：`T-316 / R-107 / B-241 / REL-006 / DOC-4 / W-132 / S24`，mock 用 max+1 自增。后端建议：
- 表主键用 MySQL `BIGINT AUTO_INCREMENT`；
- **业务编号**（展示用）用序列号表（`sequence` 表，按类型一行，行锁自增）生成 `T-317` 等；
- 按类型全局递增 or 按项目独立递增——**待确认（见 §8 待确认 1）**。

### 2.8 认证与会话

- 登录：`POST /api/auth/login { 账号/邮箱, password }` → 返回 `{ token, user }`（**新增接口**，前端原登录不走网络）；
- 密码存储：**BCrypt 哈希**（mock 中是明文，绝对不能沿用）；"重置密码"走同一逻辑；
- token：JWT（Spring Security + jjwt），有效期 8~24h，前端存 localStorage，随 `Authorization: Bearer` 上送；401 统一跳登录；
- `PUT /projects/current`：当前项目属于**用户级偏好**，存 `user_preference` 表（保持接口不变）。

### 2.9 一致性与边界规则（从 mock 行为反推）

- 删除项目：级联处理其下业务数据，且若删的是当前项目，自动切到第一个项目（mock 已有此逻辑）；
- 删除文档分类：分类下有文档时拒绝，返回 `{ ok:false, used:n }`——**这是业务校验，必须原样实现**；
- 批量操作 `/bulk/update`：按 kind + ids 批量打 patch，**每条都要记 field 动态**；
- 评论删除、附件增删、关注切换均需写 activity；
- watch 切换返回**新数组**（前端依赖引用变更刷新视图）。

---

## 3. 技术选型（已确认）

### 3.1 定稿技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | **Java 21 LTS** | Spring Boot 4 要求 Java 17+，取 21 LTS（虚拟线程可用） |
| 框架 | **Spring Boot 4.x**（Spring Framework 7，Jakarta 命名空间） | 模块化包结构匹配七大业务域 |
| 数据库 | **MySQL 8.0**（InnoDB，`utf8mb4`） | JSON 列用 MySQL 原生 `JSON` 类型 |
| ORM | **MyBatis-Plus** | 内置 `PaginationInnerInterceptor` 分页插件 + 动态条件构造，契合服务端分页；复杂聚合用注解 SQL/XML |
| 数据库迁移 | **不用 Flyway**，纯 SQL 幂等脚本 | `db/schema.sql`（IF NOT EXISTS）+ `db/data.sql`（INSERT IGNORE），启动时 `spring.sql.init` 自动执行，也可手工执行 |
| 安全认证 | **Spring Security 6 + jjwt** | JWT 无状态认证；自定义 `@RequireModule` 权限注解 |
| 参数校验 | **jakarta.validation**（Bean Validation） | `@Valid` + 全局异常处理返回 `{message}` |
| 定时任务 | Spring `@Scheduled` | 到期提醒扫描、迭代每日快照 |
| 对象存储 | **RustFS**（S3 协议兼容），经 **AWS SDK for Java v2 S3Client** 访问 | 详见 §3.2 |
| 邮件 | 只做内容模板渲染（不接 SMTP） | 见 §2.5 |
| 日志 | SLF4J + Logback（结构化，含 traceId） | |
| 测试 | JUnit 5 + Mockito（状态机、权限、活动记录单测） | |
| 部署 | Docker Compose（mysql + rustfs + app） | 开发期 vite proxy 转发 `/api` → 8080 |

**选型理由（相对已评估的 Node/NestJS 方案）：** 团队决策优先，Spring Boot 4 在事务一致性（状态机 + 活动记录 + 通知同事务）、权限体系（Spring Security 成熟度）、企业生态上更适合本系统；MyBatis-Plus 的分页插件让服务端分页改造成本最低。原方案 A 的全部领域设计（§2、§4~§6）与语言无关，直接沿用。

### 3.2 RustFS 附件存储设计

RustFS 是 S3 协议兼容的对象存储，后端按 S3 标准接入：

- **配置**（`application.yml`）：`rustfs.endpoint / access-key / secret-key / bucket=clearflow-attachments / region`；
- **上传流程**（保持既有 `/attachments` 接口形状不变）：
  1. 前端 `POST /api/files/upload`（multipart）→ 后端用 S3Client 上传 RustFS，对象键 `attachments/{projectId}/{yyyyMM}/{uuid}.{ext}`；
  2. 返回 `{ url, key, name, size }`；
  3. 前端再调既有 `POST /attachments?kind=&id=` 保存 meta（含 url）——**实体接口零改动**；
- **访问控制**：一期桶私有，读取走**预签名 URL**（有效期如 1h）；图片预览、文件下载均用预签名地址；
- **删除联动**：`DELETE /attachments` 时同步删除 RustFS 对象（失败仅记日志，不阻断业务）；
- **限制**：单文件大小上限（如 20MB）、白名单扩展名，防滥用。

### 3.3 项目结构（Java 包结构示意）

```
clearflow-server/
├── src/main/java/dev/clearflow/
│   ├── ClearFlowApplication.java
│   ├── common/            # 统一异常→{message}、分页DTO、BaseEntity、权限注解与拦截器、活动记录AOP
│   ├── config/            # SecurityConfig、MybatisPlusConfig(分页插件)、S3Config(RustFS)、CORS
│   ├── auth/              # 登录、JWT 签发/过滤
│   ├── member/  project/  iteration/  requirement/  task/  defect/
│   ├── release/  doc/  worklog/  metric/  overview/  search/
│   ├── activity/  comment/  attachment/  notification/  file/   # 横切多态 + 文件上传
├── src/main/resources/
│   ├── application.yml     # mysql / rustfs / jwt 配置
│   └── db/                   # schema.sql + data.sql（幂等初始化脚本）
└── pom.xml
```

每模块内部分层：`controller（薄） → service（业务规则/状态机） → mapper（MyBatis-Plus）`。

---

## 4. 系统架构与关键流程

### 4.1 分层与横切

```
Vue 前端 ──fetch /api(+JWT)──▶ Spring Boot 4
                                ├── Security 过滤器链：认证(JWT) → 权限(模块×等级)
                                ├── Controller：@Valid 校验，返回 mock 同形 JSON
                                ├── Service：状态机、业务校验、聚合查询（@Transactional）
                                ├── AOP 拦截器：关键字段 old→new 检测 → 写 activity
                                ├── @Scheduled：到期提醒 / 迭代日快照
                                ├── MyBatis-Plus ──▶ MySQL 8
                                └── S3Client ──▶ RustFS（附件）
```

### 4.2 一次"改状态"请求的完整链路（以缺陷为例）

1. `PUT /api/defects/B-241` body `{ status:'fixing' }` → 权限校验（缺陷追踪·编辑）；
2. Service 加载旧记录，**状态机校验** `open → fixing` 合法；
3. 同一事务内：更新记录 + AOP 比对 `status/owner/prio/severity` 等关键字段生成 activity；
4. 若 prio ∈ p0/p1 且为新建 → 按 `settings.notify.defect` 开关生成 notification；
5. 返回 `{ ok:true }`，前端按既有逻辑刷新列表。

### 4.3 数据隔离（已确认：project_id）

- 所有业务表（task/requirement/defect/release/iteration/doc/worklog 及多态表）加 `project_id` 外键；
- 所有列表/详情查询**默认按当前项目过滤**，Service 层统一封装项目上下文；
- 项目上下文来源：一期沿用 `PUT /projects/current`（存 `user_preference`），项目切换后同路径接口返回该项目数据；
- API 响应**不强制新增 projectId 字段**（前端不读），隔离发生在服务端查询层；
- 跨项目访问详情返回 404，防止越权读取。

---

## 5. 数据库设计（MySQL）

> 字段名 snake_case；`JSON` 列标注；字符集 `utf8mb4`；所有业务表含 `project_id` + `created_at/updated_at`。

**系统级**

| 表 | 关键字段 |
|---|---|
| `member` | id, name, email(唯一), password_hash, role(管理员/开发/只读), position(如"前端负责人"), color, joined_at, capacity, status |
| `permission_grant` | member_id, module_key, level — 唯一约束(member, module) |
| `user_preference` | member_id, current_project_id |
| `settings` | 分区：workflow / options / notify / profile（JSON 列或分行） |
| `doc_category` | id, name(唯一), sort |
| `sequence` | biz_type(唯一), next_val —— 业务编号生成 |

**项目域**

| 表 | 关键字段 |
|---|---|
| `project` | id, code, name, desc, owner_id, state, start_at, end_at, color, progress |
| `project_member` | project_id, member_id（联合唯一） |
| `iteration` | id, project_id, code(S24), name, start_at, end_at, state, owner_id, goal, capacity(JSON), retro(JSON), pct |
| `milestone` | project_id, iteration_id, date, name, state |
| `iteration_snapshot` | project_id, iteration_id, snap_date, remaining_points, done_points（燃尽图数据源，唯一约束(iter,date)） |

**业务实体（四张结构相似，均含 project_id）**

| 表 | 特有字段 | 公共字段 |
|---|---|---|
| `requirement` | type, source, owner_id, iter_code, estimate, release_version, priority | id, project_id, code(R-107), title, status, desc, created_at |
| `task` | type(fe/be/qa/de), priority, assignee_id, due_at, hot, iter_code, estimate, spent, req_id, labels(JSON), creator_id | 同上 |
| `defect` | env, priority, severity, owner_id, reporter, iter_code, found_in, deadline_at, req_id, steps | 同上 |
| `release` | version, release_date, owner_id, channel, risk, notes(html) | 同上 |
| `subtask` | task_id, title, done | |
| `acceptance_item` | requirement_id, title, done | |
| `verify_log` | project_id, defect_id, by_id, result, note, created_at | |
| `entity_relation` | project_id, defect_id, type(blocks/duplicate…), target | |

**横切多态（均含 project_id）**

| 表 | 关键字段 |
|---|---|
| `comment` | id, project_id, kind, ref_id, member_id, text, mentions(JSON), created_at |
| `attachment` | id, project_id, kind, ref_id, name, size, file_type(image/file), url, storage_key(RustFS对象键), member_id, created_at |
| `watcher` | project_id, kind, ref_id, member_id（联合唯一） |
| `activity` | id, project_id, kind, ref_id, member_id, type(create/status/field/comment/attach/subtask/verify/watch), text, created_at — 索引(kind, ref_id, created_at) |
| `notification` | id, member_id, type, title, text, ref_kind, ref_id, read, created_at |
| `worklog` | id, project_id, member_id, work_date, ref_text, hours, note |
| `doc` | id, project_id, code, title, category, content(md), owner_id |

**索引要点**：`activity(project_id, kind, ref_id, created_at)`、`task(project_id, status)`、`defect(project_id, status, priority)`、`notification(member_id, read)`、分页列表的常用筛选列 + `created_at` 排序索引。

---

## 6. API 设计规范

### 6.1 全局约定

- Base：`/api`，路径与 [api/index.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/index.js) 逐一对应（附录 A）；
- 认证：`Authorization: Bearer <JWT>`，除 `POST /auth/login` 外全量校验；
- 成功：HTTP 200 + **业务数据本体**（与 mock 返回同形）；
- 失败：HTTP 4xx/5xx + `{ message: string }`（前端 `request.js` 已按 `!res.ok` 抛错）；
- 时间：后端统一 ISO 8601（`2026-10-10T14:32:00`），展示格式由前端归一化；
- **服务端分页契约（已确认）：**
  - 列表接口统一：`GET /api/{资源}?page=1&pageSize=10&{筛选字段}&keyword=&sort=`
  - `page` 从 1 开始，`pageSize` 默认 10，可选 10/20/50（与 `AppPager` 选项一致）；
  - 响应：`{ "list": [...], "total": 132, "page": 1, "pageSize": 10 }`；
  - **筛选与关键字一并传服务端**（否则 total 不准），如 `GET /requirements?status=待评审&prio=p0&keyword=支付&page=2`；
  - 分页范围：需求、缺陷、发版计划、文档、成员、项目、工时记录、评论等大列表；
  - **不分页**：`/board`、`/search`、`/overview`、`/metrics`、`/defects/stats`、`/releases/stats`、`/mywork` 等聚合类；
  - **mock 端同步实现同款分页**，保证 `USE_MOCK=true` 前后端联调均可用；
  - 前端 `AppPager` 组件不变，仅父组件数据流从本地 slice 改为接口返回。

### 6.2 接口域清单（按模块）

| 域 | 代表接口 |
|---|---|
| 认证 | `POST /auth/login`、`GET /auth/me`（新增） |
| 概览 | `GET /overview`、`GET /board` |
| 项目 | `GET/POST /projects`，`PUT/DELETE /projects/:id`，`PUT /projects/:id/archive`，`GET/PUT /projects/current` |
| 设置 | `GET /settings`，`PUT /settings/:section` |
| 任务 | `POST /tasks`，`PUT/DELETE /tasks/:id`，子任务三个端点 |
| 迭代 | `GET/POST /iterations`，`PUT /iterations/:code`，`PUT /iterations/:code/retro`，`POST /iterations/plan` |
| 需求 | `GET/POST /requirements`（分页），`PUT/DELETE /requirements/:id`，`PUT /requirements/:id/status`，`PUT .../acceptance/:acId` |
| 缺陷 | `GET /defects`（分页），`GET /defects/stats`，增删改，`POST /defects/:id/verify`，relates 三端点 |
| 发版 | `GET /releases`（分页），`GET /releases/stats`，增删改（含关联 reqIds/defectIds/notes）；邮件内容生成端点（见 §2.5） |
| 文档 | `GET/POST /docs`（分页），`PUT/DELETE /docs/:id`，分类四端点（含"有文档不可删"校验） |
| 我的工作/度量/工时 | `GET /mywork`、`GET /metrics`、`GET /worklog`（记录分页）、`POST /worklog` |
| 成员/权限 | `GET/POST /members`（分页），`PUT/DELETE /members/:id`，`PUT /members/:id/password`，`PUT /perm/:memberId` |
| 文件/附件 | **`POST /files/upload`（新增，multipart → RustFS）**，attachments 增删（既有） |
| 评论/关注/动态 | comments、watch、activity、activity/feed |
| 通知/搜索/批量 | `GET /notifications`，`PUT /notifications/read-all`，`GET /search?q=`，`PUT /bulk/update` |

### 6.3 权限执行矩阵（Spring Security 实现依据）

| 等级 | 允许 |
|---|---|
| 查看 | 所有 GET |
| 编辑 | GET + 该模块的 POST/PUT（创建、更新、状态流转、评论等） |
| 管理 | 编辑 + DELETE、归档、批量操作、权限矩阵修改、成员密码重置 |

角色默认：管理员=全模块管理；开发=业务模块编辑（成员与权限仅查看）；只读=全模块查看。`permission_grant` 逐模块覆盖角色默认。

---

## 7. 实施阶段划分（供排期参考）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 基座 | Spring Boot 4 工程搭建、SQL 脚本全量建表、认证 + 成员 + 权限 + 项目 + 设置、**分页基建（分页插件 + 统一分页 DTO）**，前端 request.js/Login/分页改造 | 登录 → 切项目 → 成员/权限矩阵读写；列表页服务端分页跑通 |
| M2 核心业务 | 需求/任务/缺陷/迭代 CRUD + 状态机 + 活动记录 + 评论/关注 | `USE_MOCK=false` 下需求池、看板、缺陷页全流程可用 |
| M3 发版与协作 | 发版计划（关联内容、状态流转、**邮件内容生成**）、文档、通知、全局搜索、批量、**RustFS 附件上传** | 发版全流程 + 通知触发 + 附件上传/预览 |
| M4 聚合与运营 | overview/metrics/worklog/mywork 聚合、燃尽快照定时任务、站报内容生成、mock 种子数据导入（data.sql 种子脚本） | 概览/度量/工时三页数据真实可算 |

每阶段验收方式：`USE_MOCK=false` + vite proxy 指向后端，全页面冒烟回归 + 核心规则单测。

---

## 8. 决策记录与待确认问题

### 8.1 已确认决策

| # | 决策 |
|---|---|
| 1 | 数据库 **MySQL 8**，框架 **Spring Boot 4 + Java 21**（ORM 定 MyBatis-Plus，如需改 JPA 提出即可） |
| 2 | 业务表统一加 **`project_id`**，服务端按当前项目隔离查询 |
| 3 | **服务端分页**：`{list, total, page, pageSize}` 契约，mock 与前端列表页同步改造 |
| 4 | 邮件**只生成内容**，不接 SMTP、不设定时发信 |
| 5 | 附件存 **RustFS**（S3 协议），后端代理上传 + 预签名读取 |
| 6 | **不用 Flyway**：`schema.sql`（IF NOT EXISTS）+ `data.sql`（INSERT IGNORE）幂等脚本，启动时 `spring.sql.init` 自动执行 |

### 8.2 剩余待确认问题

1. **ID 规则**：`T-316` 类业务编号按**类型全局递增**还是**按项目独立递增**？（影响 `sequence` 表设计，我的推荐：全局递增，简单且无歧义）
2. **需求状态中文明文**：`requirement.status` 存中文（`待评审`…）与其他实体存 key 不一致，为零改前端建议**维持现状**——是否同意？
3. **时间字段归一化**：mock 中 `due='待排期'`、`'今日'`、`'10.10'` 等自由文本，后端统一存标准时间、空值语义用 `null`（如未排期）——前端需配合归一化展示，是否接受？
4. **种子数据**：登录账号按 mock 成员生成（沿用 `admin@clearflow.dev / 123456` 约定，密码一律 BCrypt 哈希）？业务种子数据（任务/需求/缺陷等）是否从 mock 数据生成初始数据集？
5. **RustFS 部署**：本地 Docker 起一个 RustFS 实例即可？桶访问私有 + 预签名（我的推荐），还是公开读桶简化一期？

---

## 附录 A：前端接口 ↔ mock 返回形状对照

> 后端实现时逐个对照 [api/index.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/index.js) 中 `mock` 函数；数据字段以 [mock/data.js](file:///c:/Users/lyn/Desktop/项目管理/clearflow-web/src/api/mock/data.js) 为数据字典（含四类实体全部字段、种子数据、多态扩展字段的默认值补齐规则）。
> 例外：列表类接口响应形状按 §6.1 分页契约由数组改为 `{list, total, page, pageSize}`，mock 端同步调整。
