package dev.clearflow.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.common.Times;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 文档 CRUD + 分类字典管理（分类重命名的合并语义与 mock 一致） */
@Service
public class DocService {

    private final DocMapper docMapper;
    private final DocCategoryMapper categoryMapper;
    private final SequenceService sequence;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;

    public DocService(DocMapper docMapper, DocCategoryMapper categoryMapper, SequenceService sequence,
                      ProjectContext projectContext, ActivityLog activityLog) {
        this.docMapper = docMapper;
        this.categoryMapper = categoryMapper;
        this.sequence = sequence;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
    }

    /** 列表行不取正文（content 是 MEDIUMTEXT，只在详情/阅读/编辑时下发） */
    private static final String[] LIST_COLUMNS = {
            "id", "project_id", "title", "category", "owner", "created", "updated"
    };

    /** 文档列表（服务端筛选 + 分页，新在前） */
    public PageResult<Doc.View> page(int page, int pageSize, String category, String owner, String q) {
        Long projectId = projectContext.currentProjectId();
        QueryWrapper<Doc> w = new QueryWrapper<Doc>().eq("project_id", projectId).select(LIST_COLUMNS);
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            w.eq("category", category.trim());
        }
        if (owner != null && !owner.isBlank()) {
            w.eq("owner", parseId(owner, "更新人"));
        }
        String like = likePattern(q);
        if (like != null) {
            w.and(x -> x.like("title", like).or().like("content", like));
        }
        Page<Doc> result = docMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 200)),
                w.orderByDesc("created").orderByDesc("id"));
        return PageResult.of(result.getRecords().stream().map(Doc.View::of).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /** 分类文档数（一次 GROUP BY，供分类下拉与分类管理弹窗计数） */
    public Map<String, Object> stats() {
        Long projectId = projectContext.currentProjectId();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : docMapper.selectMaps(new QueryWrapper<Doc>()
                .select("category", "COUNT(*) AS cnt").eq("project_id", projectId).groupBy("category"))) {
            Object v = row.get("cnt");
            long cnt = v == null ? 0 : ((Number) v).longValue();
            byCategory.put(String.valueOf(row.get("category") == null ? "" : row.get("category")), cnt);
            total += cnt;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byCategory", byCategory);
        return out;
    }

    /** LIKE 模式：% 与 _ 转义，避免用户输入的通配符扩大匹配范围 */
    private static String likePattern(String q) {
        String v = q == null || q.isBlank() ? null : q.trim();
        return v == null ? null : '%' + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + '%';
    }

    private static Long parseId(String raw, String label) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.bad(label + "参数不合法：" + raw);
        }
    }

    private Doc get(String id) {
        Doc d = docMapper.selectById(id);
        if (d == null || !d.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("文档不存在或不属于当前项目");
        }
        return d;
    }

    /** 单篇文档（导出 PDF 等只读场景） */
    public Doc.View view(String id) {
        return Doc.View.of(get(id));
    }

    // ===== 分类字典 =====

    public List<String> categories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<DocCategory>()
                        .orderByAsc(DocCategory::getId))
                .stream().map(DocCategory::getName).toList();
    }

    @Transactional
    public Map<String, Object> addCategory(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!name.isEmpty() && findByName(name) == null) {
            DocCategory c = new DocCategory();
            c.setName(name);
            categoryMapper.insert(c);
        }
        return Map.of("ok", true, "categories", categories());
    }

    /** 重命名分类：字典与该分类下所有文档同步更新；新名与已有分类重复时做合并 */
    @Transactional
    public Map<String, Object> updateCategory(String oldName, String rawNewName) {
        String name = rawNewName == null ? "" : rawNewName.trim();
        if (!name.isEmpty() && !name.equals(oldName)) {
            DocCategory old = findByName(oldName);
            if (old != null) {
                if (findByName(name) != null) {
                    categoryMapper.deleteById(old.getId());
                } else {
                    old.setName(name);
                    categoryMapper.updateById(old);
                }
            } else if (findByName(name) == null) {
                DocCategory c = new DocCategory();
                c.setName(name);
                categoryMapper.insert(c);
            }
            docMapper.update(null, new LambdaUpdateWrapper<Doc>()
                    .eq(Doc::getCategory, oldName)
                    .set(Doc::getCategory, name));
        }
        return Map.of("ok", true, "categories", categories());
    }

    /** 删除分类：分类下仍有文档时拒绝，返回 used 数量 */
    @Transactional
    public Map<String, Object> deleteCategory(String name) {
        long used = docMapper.selectCount(new LambdaQueryWrapper<Doc>()
                .eq(Doc::getProjectId, projectContext.currentProjectId())
                .eq(Doc::getCategory, name));
        if (used > 0) {
            Map<String, Object> out = new HashMap<>();
            out.put("ok", false);
            out.put("used", used);
            return out;
        }
        DocCategory c = findByName(name);
        if (c != null) {
            categoryMapper.deleteById(c.getId());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("used", 0);
        out.put("categories", categories());
        return out;
    }

    private DocCategory findByName(String name) {
        return categoryMapper.selectOne(new LambdaQueryWrapper<DocCategory>()
                .eq(DocCategory::getName, name));
    }

    // ===== 文档 CRUD =====

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String title = Patch.str(body, "title");
        if (title == null || title.isBlank()) {
            throw BizException.bad("标题不能为空");
        }
        MemberPrincipal actor = AuthContext.require();
        String id = sequence.next("doc");

        Doc d = new Doc();
        d.setId(id);
        d.setProjectId(projectContext.currentProjectId());
        d.setTitle(title.trim());
        d.setCategory(Patch.str(body, "category"));
        d.setContent(Patch.str(body, "content"));
        d.setOwner(actor.id());
        d.setCreated(Times.now());
        d.setUpdated(Times.now());
        docMapper.insert(d);

        activityLog.write("<b>" + actor.name() + "</b> 创建文档 <span class=\"mr\">"
                + id + "</span>「" + d.getTitle() + "」");
        return Map.of("ok", true, "id", id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Doc d = get(id);
        if (patch.containsKey("title")) d.setTitle(Patch.str(patch, "title"));
        if (patch.containsKey("category")) d.setCategory(Patch.str(patch, "category"));
        if (patch.containsKey("content")) d.setContent(Patch.str(patch, "content"));
        if (patch.containsKey("owner")) d.setOwner(Patch.lng(patch, "owner"));
        d.setUpdated(Times.now());
        docMapper.updateById(d);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> remove(String id) {
        Doc d = get(id);
        docMapper.deleteById(id);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除文档 <span class=\"mr\">"
                + id + "</span>「" + (d.getTitle() == null ? "" : d.getTitle()) + "」");
        return Map.of("ok", true);
    }
}
