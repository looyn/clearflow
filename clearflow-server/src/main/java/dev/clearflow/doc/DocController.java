package dev.clearflow.doc;

import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireProject;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/docs")
public class DocController {

    private final DocService docService;
    private final DocPdfService pdfService;

    public DocController(DocService docService, DocPdfService pdfService) {
        this.docService = docService;
        this.pdfService = pdfService;
    }

    /** 文档列表（服务端筛选 + 分页；行内不含正文） */
    @GetMapping
    @RequireProject(module = "文档管理", level = "查看")
    public PageResult<Doc.View> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int pageSize,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String owner,
                                     @RequestParam(required = false) String q) {
        return docService.page(page, pageSize, category, owner, q);
    }

    /** 分类文档数统计（一次 GROUP BY，供分类下拉与管理弹窗计数） */
    @GetMapping("/stats")
    @RequireProject(module = "文档管理", level = "查看")
    public Map<String, Object> stats() {
        return docService.stats();
    }

    /** 单篇文档（含正文，供阅读、编辑与下载） */
    @GetMapping("/{id}")
    @RequireProject(module = "文档管理", level = "查看")
    public Doc.View detail(@PathVariable String id) {
        return docService.view(id);
    }

    /** 分类字典 */
    @GetMapping("/categories")
    @RequireProject(module = "文档管理", level = "查看")
    public List<String> categories() {
        return docService.categories();
    }

    /** 新增分类（分类字典为系统级，入口按当前项目鉴权） */
    @PostMapping("/categories")
    @RequireProject(module = "文档管理", level = "编辑")
    public Map<String, Object> addCategory(@RequestBody Map<String, String> body) {
        return docService.addCategory(body.get("name"));
    }

    /** 重命名分类（重复时合并） */
    @PutMapping("/categories")
    @RequireProject(module = "文档管理", level = "编辑")
    public Map<String, Object> updateCategory(@RequestBody Map<String, String> body) {
        return docService.updateCategory(body.get("oldName"), body.get("newName"));
    }

    /** 删除分类（有文档时拒绝） */
    @DeleteMapping("/categories")
    @RequireProject(module = "文档管理", level = "编辑")
    public Map<String, Object> deleteCategory(@RequestBody Map<String, String> body) {
        return docService.deleteCategory(body.get("name"));
    }

    /** 新建文档 */
    @PostMapping
    @RequireProject(module = "文档管理", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return docService.create(body);
    }

    /** 编辑文档 */
    @PutMapping("/{id}")
    @RequireProject(module = "文档管理", level = "编辑")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return docService.update(id, patch);
    }

    /** 导出 PDF：真正的 PDF 文件，首页目录带页码、可点击跳转，阅读器侧边栏有书签 */
    @GetMapping("/{id}/pdf")
    @RequireProject(module = "文档管理", level = "查看")
    public void exportPdf(@PathVariable String id, HttpServletResponse response) throws IOException {
        DocPdfService.PdfFile pdf = pdfService.render(id);
        String filename = URLEncoder.encode(pdf.title() + ".pdf", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/pdf");
        response.setContentLengthLong(pdf.bytes().length);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"document.pdf\"; filename*=UTF-8''" + filename);
        response.getOutputStream().write(pdf.bytes());
    }

    /** 删除文档 */
    @DeleteMapping("/{id}")
    @RequireProject(module = "文档管理", level = "管理")
    public Map<String, Object> remove(@PathVariable String id) {
        return docService.remove(id);
    }
}
