package dev.clearflow.file;

import dev.clearflow.collab.CollabService;
import dev.clearflow.infra.ProjectContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/files")
public class FileController {

    private final StorageService storageService;
    private final CollabService collabService;
    private final ProjectContext projectContext;

    public FileController(StorageService storageService, CollabService collabService,
                          ProjectContext projectContext) {
        this.storageService = storageService;
        this.collabService = collabService;
        this.projectContext = projectContext;
    }

    /**
     * 上传附件到 RustFS：POST /files/upload?kind=&id=（multipart file）
     * 返回 { ok, key, name, size, kind, url }，随后由前端调 /attachments 登记实体附件
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam String kind,
                                      @RequestParam String id,
                                      @RequestParam("file") MultipartFile file) {
        collabService.requireModule(kind, "编辑");
        collabService.ensure(kind, id, projectContext.currentProjectId());

        StorageService.StoredFile stored = storageService.upload(file);
        String url = storageService.presign(stored.key());
        String fileType = stored.name().matches("(?i).+\\.(png|jpe?g|gif|webp|svg|bmp)$")
                ? "image" : "file";
        return Map.of(
                "ok", true,
                "key", stored.key(),
                "name", stored.name(),
                "size", stored.size(),
                "kind", fileType,
                "url", url == null ? "" : url);
    }

    /**
     * 富文本编辑器图片上传：POST /files/editor-image（multipart file）
     * 不绑定具体实体（新建中的实体尚无 id），仅要求登录；返回正文可长期引用的稳定读取 URL
     */
    @PostMapping("/editor-image")
    public Map<String, Object> editorImage(@RequestParam("file") MultipartFile file) {
        StorageService.StoredFile stored = storageService.upload(file);
        return Map.of(
                "ok", true,
                "key", stored.key(),
                "name", stored.name(),
                "url", "/api/files/raw/" + stored.key());
    }

    /**
     * 富文本图片稳定读取端点：直接回源对象存储字节
     * &lt;img&gt; 请求无法携带 Authorization 头，故在 SecurityConfig 放行鉴权；
     * 用直出替代 302 预签名跳转：前端导出文档时需同源 fetch 图片并内联，跨域跳转会因 CORS 读不到字节
     */
    @GetMapping("/raw/{key}")
    public void raw(@PathVariable String key, HttpServletResponse response) throws IOException {
        StorageService.StoredObject object = storageService.read(key);
        if (object == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(object.contentType() == null ? "application/octet-stream" : object.contentType());
        response.setContentLengthLong(object.bytes().length);
        response.setHeader("Cache-Control", "max-age=86400");
        response.getOutputStream().write(object.bytes());
    }
}
