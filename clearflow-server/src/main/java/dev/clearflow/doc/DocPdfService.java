package dev.clearflow.doc;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import dev.clearflow.file.StorageService;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档导出 PDF：Markdown → HTML → openhtmltopdf/PDFBox。
 * 标题层级写入 PDF 书签（阅读器侧边大纲），不额外插入目录页。
 */
@Service
public class DocPdfService {

    private static final String FONT_FAMILY = "ClearFlowSans";
    private static final Pattern RAW_IMAGE = Pattern.compile("^(?:https?://[^/]+)?/api/files/raw/(.+)$");

    private static final List<String> REGULAR_FONTS = List.of(
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf");
    private static final List<String> BOLD_FONTS = List.of(
            "C:/Windows/Fonts/msyhbd.ttf",
            "C:/Windows/Fonts/Dengb.ttf",
            "C:/Windows/Fonts/simhei.ttf");

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create());

    private static final String CSS = """
            @page {
              size: A4;
              margin: 18mm 16mm 16mm 16mm;
              @bottom-center {
                content: counter(page) " / " counter(pages);
                font-family: "ClearFlowSans";
                font-size: 8pt;
                color: #9aa1ab;
              }
            }
            body { font-family: "ClearFlowSans", sans-serif; font-size: 10.5pt; line-height: 1.65; color: #1f2328; }
            h1 { font-size: 18pt; margin: 18pt 0 9pt; }
            h2 { font-size: 15pt; margin: 16pt 0 8pt; }
            h3 { font-size: 12.5pt; margin: 14pt 0 6pt; }
            h4, h5, h6 { font-size: 11pt; margin: 12pt 0 5pt; }
            h1, h2, h3, h4 { page-break-after: avoid; break-after: avoid; }
            p { margin: 5pt 0; }
            ul, ol { margin: 5pt 0; padding-left: 18pt; }
            li { margin: 2pt 0; }
            blockquote { margin: 7pt 0; padding: 2pt 10pt; border-left: 3pt solid #d0d7de; color: #57606a; }
            pre { margin: 7pt 0; padding: 8pt 10pt; background: #f6f8fa; font-size: 9pt; line-height: 1.55;
              white-space: pre-wrap; word-wrap: break-word; page-break-inside: avoid; }
            code { font-family: "ClearFlowSans", monospace; font-size: 9.5pt; background: #f6f8fa; padding: 1pt 3pt; }
            pre code { background: none; padding: 0; font-size: 9pt; }
            a { color: #2563eb; text-decoration: none; }
            img { max-width: 100%; -fs-fit-images-to-width: 100%; page-break-inside: avoid; }
            hr { border: none; border-top: 0.5pt solid #d8dee4; margin: 12pt 0; }
            table { width: 100%; border-collapse: collapse; margin: 7pt 0; page-break-inside: avoid; }
            th, td { border: 0.5pt solid #d8dee4; padding: 4pt 6pt; font-size: 9.5pt; text-align: left; }
            th { background: #f2f4f7; font-weight: bold; }
            input[type=checkbox] { width: 9pt; height: 9pt; }
            .doc-header { padding-bottom: 12pt; margin-bottom: 14pt; border-bottom: 1pt solid #d8dee4; }
            .doc-title { font-size: 22pt; font-weight: bold; margin-bottom: 6pt; }
            .doc-meta { font-size: 9.5pt; color: #6b7280; }
            """;

    private final DocService docService;
    private final StorageService storageService;

    public DocPdfService(DocService docService, StorageService storageService) {
        this.docService = docService;
        this.storageService = storageService;
    }

    public record PdfFile(byte[] bytes, String title) {
    }

    private record TocEntry(int level, String text, String id) {
    }

    public PdfFile render(String docId) {
        Doc.View doc = docService.view(docId);
        String markdown = doc.content() == null ? "" : doc.content();

        Document html = markdownToDom(markdown);
        List<TocEntry> toc = assignHeadingIds(html);
        inlineImages(html);

        String finalHtml = document(doc.title(), bookmarksXml(toc),
                headerHtml(doc) + html.body().html());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfBoxRenderer renderer = newRenderer(finalHtml, out)) {
            renderer.layout();
            renderer.createPDF();
        } catch (IOException e) {
            throw new IllegalStateException("PDF 生成失败", e);
        }
        return new PdfFile(out.toByteArray(), doc.title());
    }

    // ===== Markdown → XHTML DOM =====

    private Document markdownToDom(String markdown) {
        Parser parser = Parser.builder().extensions(EXTENSIONS).build();
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).build();
        Node ast = parser.parse(markdown);
        Document dom = Jsoup.parseBodyFragment(renderer.render(ast));
        dom.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8);
        return dom;
    }

    /** 给所有标题补稳定锚点（含 Markdown 里混写的原生 HTML 标题），书签跳转用 */
    private List<TocEntry> assignHeadingIds(Document dom) {
        List<TocEntry> entries = new ArrayList<>();
        int i = 0;
        for (Element h : dom.select("h1, h2, h3, h4, h5, h6")) {
            String id = "doc-h-" + i++;
            h.attr("id", id);
            entries.add(new TocEntry(Integer.parseInt(h.tagName().substring(1)), h.text(), id));
        }
        return entries;
    }

    /** 正文图片一律内联为 data URL：本地图片走对象存储，外链不抓取（避免 SSRF） */
    private void inlineImages(Document dom) {
        for (Element img : dom.select("img[src]")) {
            String src = img.attr("src").trim();
            if (src.startsWith("data:")) {
                continue;
            }
            Matcher m = RAW_IMAGE.matcher(src);
            if (!m.matches()) {
                img.removeAttr("src");
                continue;
            }
            String key = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            StorageService.StoredObject object = storageService.read(key);
            if (object == null) {
                img.removeAttr("src");
                continue;
            }
            String type = object.contentType() == null ? "application/octet-stream" : object.contentType();
            img.attr("src", "data:" + type + ";base64,"
                    + Base64.getEncoder().encodeToString(object.bytes()));
        }
    }

    // ===== HTML 组装 =====

    private String document(String title, String headExtra, String body) {
        return "<html><head><title>" + esc(title) + "</title><style>" + CSS + "</style>"
                + headExtra + "</head><body>" + body + "</body></html>";
    }

    private String headerHtml(Doc.View doc) {
        return "<div class=\"doc-header\"><div class=\"doc-title\">" + esc(doc.title()) + "</div>"
                + "<div class=\"doc-meta\">" + esc(meta(doc)) + "</div></div>";
    }

    private String meta(Doc.View doc) {
        String category = doc.category() == null || doc.category().isBlank() ? "" : doc.category() + " · ";
        return category + "更新于 " + (doc.updated() == null ? "—" : doc.updated());
    }

    /** PDF 阅读器侧边大纲（书签），按标题级别嵌套 */
    private String bookmarksXml(List<TocEntry> toc) {
        if (toc.isEmpty()) {
            return "";
        }
        List<BookmarkNode> roots = new ArrayList<>();
        Deque<BookmarkNode> stack = new ArrayDeque<>();
        for (TocEntry e : toc) {
            BookmarkNode node = new BookmarkNode(e.text(), e.id(), e.level());
            while (!stack.isEmpty() && stack.peek().level >= e.level()) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        StringBuilder sb = new StringBuilder("<bookmarks>");
        for (BookmarkNode n : roots) {
            appendBookmark(sb, n);
        }
        return sb.append("</bookmarks>").toString();
    }

    private void appendBookmark(StringBuilder sb, BookmarkNode n) {
        sb.append("<bookmark name=\"").append(escAttr(n.title)).append("\" href=\"#").append(n.id).append("\">");
        for (BookmarkNode c : n.children) {
            appendBookmark(sb, c);
        }
        sb.append("</bookmark>");
    }

    private static final class BookmarkNode {
        final String title;
        final String id;
        final int level;
        final List<BookmarkNode> children = new ArrayList<>();

        BookmarkNode(String title, String id, int level) {
            this.title = title;
            this.id = id;
            this.level = level;
        }
    }

    // ===== 渲染 =====

    private PdfBoxRenderer newRenderer(String html, ByteArrayOutputStream out) throws IOException {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        registerFonts(builder);
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        return builder.buildPdfRenderer();
    }

    /** 中文字体优先用系统字体（Windows 微软雅黑），子集内嵌避免 PDF 过大 */
    private void registerFonts(PdfRendererBuilder builder) {
        File regular = firstExisting(REGULAR_FONTS);
        if (regular != null) {
            builder.useFont(regular, FONT_FAMILY, 400, FontStyle.NORMAL, true);
        }
        File bold = firstExisting(BOLD_FONTS);
        if (bold != null) {
            builder.useFont(bold, FONT_FAMILY, 700, FontStyle.NORMAL, true);
        }
    }

    private File firstExisting(List<String> paths) {
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static String esc(String s) {
        return String.valueOf(s == null ? "" : s)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }
}
