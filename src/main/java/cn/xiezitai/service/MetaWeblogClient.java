package cn.xiezitai.service;

import cn.xiezitai.entity.CnBlogSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博客园 MetaWeblog 客户端（XML-RPC，零第三方依赖）。
 *
 * <p>博客园的 MetaWeblog 兼容接口：
 * <ul>
 *   <li>{@code blogger.getUsersBlogs} —— 校验凭据，返回 blogid；</li>
 *   <li>{@code metaWeblog.getRecentPosts} —— 拉最近的 N 篇（无分页游标，整站导入用「递增数量」策略取全）；</li>
 *   <li>{@code metaWeblog.getPost} —— 单篇详情。</li>
 * </ul>
 *
 * <p>密钥只作为 RPC 的 password 参数经 HTTPS 发给博客园，不落日志。
 */
@Service
public class MetaWeblogClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWeblogClient.class);

    private static final DateTimeFormatter CN_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm:ss");

    /** MetaWeblog 一篇文章（description 是正文 HTML，mt_text_more 是「更多」段） */
    public record CnPost(long postId, String title, String description, String textMore,
                         String excerpt, List<String> categories,
                         LocalDateTime dateCreated, String link) {

        /** 完整正文： description + mt_text_more（博客园「更多」段，两者拼接） */
        public String fullContent() {
            String more = textMore == null ? "" : textMore;
            if (more.isBlank()) return description == null ? "" : description;
            return (description == null ? "" : description) + "\n" + more;
        }
    }

    /* ================= 高层接口 ================= */

    /** 校验凭据：返回用户的博客列表（含 blogid / blogName）。密钥错误会抛异常 */
    public List<Map<String, Object>> verifyCredentials(CnBlogSite site) throws Exception {
        Object r = call(site, "blogger.getUsersBlogs", "", nz(site.getUsername()), nz(site.getAppKey()));
        List<Map<String, Object>> out = new ArrayList<>();
        if (r instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    m.forEach((k, v) -> b.put(String.valueOf(k), v));
                    out.add(b);
                }
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("接口未返回博客信息，请检查账号与对接密钥");
        return out;
    }

    /** 拉最近 count 篇文章 */
    @SuppressWarnings("unchecked")
    public List<CnPost> getRecentPosts(CnBlogSite site, int count) throws Exception {
        String blogid = blogIdOf(site);
        Object r = call(site, "metaWeblog.getRecentPosts", blogid, nz(site.getUsername()),
                nz(site.getAppKey()), count);
        List<CnPost> out = new ArrayList<>();
        if (r instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    raw.forEach((k, v) -> m.put(String.valueOf(k), v));
                    out.add(toPost(m));
                }
            }
        }
        return out;
    }

    /** 拉单篇详情 */
    public CnPost getPost(CnBlogSite site, long postId) throws Exception {
        Object r = call(site, "metaWeblog.getPost", String.valueOf(postId),
                nz(site.getUsername()), nz(site.getAppKey()));
        if (!(r instanceof Map<?, ?> raw)) throw new IllegalStateException("文章不存在或接口返回为空");
        Map<String, Object> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v));
        return toPost(m);
    }

    /**
     * 新建一篇博客园随笔，返回新的 postid（博客园返回的是字符串形态的数字）。
     *
     * @param struct 正文结构体：title / description / mt_keywords / categories 等
     * @param publish true=直接发布，false=存草稿
     */
    public String newPost(CnBlogSite site, Map<String, Object> struct, boolean publish) throws Exception {
        String blogid = blogIdOf(site);
        Object r = call(site, "metaWeblog.newPost", blogid, nz(site.getUsername()),
                nz(site.getAppKey()), struct, publish);
        return r == null ? "" : String.valueOf(r).trim();
    }

    /**
     * 更新一篇已有随笔。
     *
     * @return 博客园返回 true 表示更新成功；有些实现回空，按「没抛异常即成功」处理
     */
    public boolean editPost(CnBlogSite site, long postId, Map<String, Object> struct, boolean publish) throws Exception {
        Object r = call(site, "metaWeblog.editPost", String.valueOf(postId), nz(site.getUsername()),
                nz(site.getAppKey()), struct, publish);
        return !(r instanceof Boolean b) || b;
    }

    /** 整站导入：MetaWeblog 无分页游标，用「数量翻倍直到取不满」的策略拿全量文章 */
    public List<CnPost> fetchAllPosts(CnBlogSite site) throws Exception {
        List<CnPost> last = List.of();
        for (int n : new int[]{100, 200, 400, 800, 1600, 3200}) {
            List<CnPost> posts = getRecentPosts(site, n);
            last = posts;
            if (posts.size() < n) break;         // 已取完
        }
        return last;
    }

    /** 匿名下载一个媒体文件（博客园图片不需要鉴权）。失败返回 null */
    public byte[] downloadQuietly(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; xiezitai-import)");
            if (conn.getResponseCode() != 200) return null;
            try (var in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("博客园媒体下载失败 {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ================= 解析 ================= */

    private CnPost toPost(Map<String, Object> m) {
        long postId = m.get("postid") instanceof Number n ? n.longValue()
                : parseLong(String.valueOf(m.getOrDefault("postid", "0")));
        String title = str(m.get("title"));
        String desc = str(m.get("description"));
        String more = str(m.get("mt_text_more"));
        String excerpt = str(m.get("mt_excerpt"));
        List<String> cats = new ArrayList<>();
        if (m.get("categories") instanceof List<?> cl) {
            for (Object c : cl) {
                String s = str(c);
                if (!s.isBlank()) cats.add(s);
            }
        }
        LocalDateTime date;
        Object dc = m.get("dateCreated");
        if (dc instanceof LocalDateTime ldt) date = ldt;      // XML-RPC 解析时已转好
        else date = parseDate(str(dc));
        String link = str(m.get("link"));
        return new CnPost(postId, title, desc, more, excerpt, cats, date, link);
    }

    private String blogIdOf(CnBlogSite site) throws Exception {
        List<Map<String, Object>> blogs = verifyCredentials(site);
        String id = str(blogs.get(0).get("blogid"));
        if (id.isBlank()) throw new IllegalStateException("未取到 blogid");
        return id;
    }

    private static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // 博客园格式：20261007T22:15:00（本地时区）
            return LocalDateTime.parse(raw.trim().substring(0, 17), CN_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String nz(String s) { return s == null ? "" : s; }

    /* ================= XML-RPC 传输 ================= */

    /** 发一次 XML-RPC 调用并解析响应（params 支持 string/int/boolean/struct/array） */
    Object call(CnBlogSite site, String method, Object... params) throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<methodCall><methodName>").append(escape(method)).append("</methodName><params>");
        for (Object p : params) {
            xml.append("<param><value>");
            appendTyped(xml, p);
            xml.append("</value></param>");
        }
        xml.append("</params></methodCall>");

        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(site.getUrl()).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn.setRequestProperty("User-Agent", "xiezitai-metaweblog/1.0");
            conn.getOutputStream().write(body);

            byte[] resp;
            try (var in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                if (in == null) throw new IllegalStateException("HTTP " + conn.getResponseCode());
                resp = in.readAllBytes();
            }

            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(new ByteArrayInputStream(resp));
            Element root = doc.getDocumentElement();
            Element fault = firstChild(root, "fault");
            if (fault != null) {
                Map<String, Object> f = (Map<String, Object>) parseValue(firstChild(fault, "value"));
                throw new IllegalStateException("接口错误：" + str(f.getOrDefault("faultString", f.get("faultCode"))));
            }
            Element paramsEl = firstChild(root, "params");
            if (paramsEl == null) throw new IllegalStateException("响应缺少 params");
            Element param = firstChild(paramsEl, "param");
            if (param == null) return null;
            return parseValue(firstChild(param, "value"));
        } finally {
            conn.disconnect();
        }
    }

    /** 递归解析一个 <value> 节点 → Java 对象（String/Long/Boolean/Double/LocalDateTime/List/Map） */
    private Object parseValue(Element value) {
        for (Node n = value.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el)) continue;
            return switch (el.getTagName()) {
                case "struct" -> parseStruct(el);
                case "array" -> parseArray(el);
                case "int", "i4" -> Long.parseLong(el.getTextContent().trim());
                case "boolean" -> "1".equals(el.getTextContent().trim());
                case "double" -> Double.parseDouble(el.getTextContent().trim());
                case "dateTime.iso8601" -> parseDate(el.getTextContent());
                default -> el.getTextContent();      // string 及未标类型的值
            };
        }
        return value.getTextContent();               // <value>无类型</value>
    }

    private Map<String, Object> parseStruct(Element struct) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Node n = struct.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element member && "member".equals(member.getTagName())) {
                String name = "";
                Element valueEl = null;
                for (Node c = member.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element el) {
                        if ("name".equals(el.getTagName())) name = el.getTextContent();
                        else if ("value".equals(el.getTagName())) valueEl = el;
                    }
                }
                if (valueEl != null) out.put(name, parseValue(valueEl));
            }
        }
        return out;
    }

    private List<Object> parseArray(Element array) {
        List<Object> out = new ArrayList<>();
        Element data = firstChild(array, "data");
        if (data == null) return out;
        for (Node n = data.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && "value".equals(el.getTagName())) out.add(parseValue(el));
        }
        return out;
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && tag.equals(el.getTagName())) return el;
        }
        return null;
    }

    /**
     * 把 Java 值序列化成 XML-RPC 的「带类型元素」（不含外层 {@code <value>} 标签）。
     *
     * <p>发文要用 struct：{@code newPost(blogid, user, key, struct, publish)}，
     * 而 struct 的每个 member 里还要再嵌一层 {@code <value>}，所以这里统一由本方法负责嵌套。
     */
    private static void appendTyped(StringBuilder sb, Object v) {
        if (v instanceof Boolean b) {
            sb.append("<boolean>").append(b ? "1" : "0").append("</boolean>");
        } else if (v instanceof Number n) {
            sb.append("<int>").append(n).append("</int>");
        } else if (v instanceof Map<?, ?> m) {
            sb.append("<struct>");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() == null) continue;          // 空值不发，博客园对未知空字段更敏感
                sb.append("<member><name>").append(escape(String.valueOf(e.getKey()))).append("</name><value>");
                appendTyped(sb, e.getValue());
                sb.append("</value></member>");
            }
            sb.append("</struct>");
        } else if (v instanceof Iterable<?> it) {
            sb.append("<array><data>");
            for (Object o : it) {
                sb.append("<value>");
                appendTyped(sb, o);
                sb.append("</value>");
            }
            sb.append("</data></array>");
        } else {
            sb.append("<string>").append(escape(v == null ? "" : String.valueOf(v))).append("</string>");
        }
    }

    /**
     * XML 转义。除了 {@code & < >}，还要**剔除 XML 1.0 不允许的控制字符** ——
     * Markdown 正文里偶尔混进 {@code \u0000}~{@code \u001F} 之类（比如从别处粘贴带进来的），
     * 直接发过去会让博客园那边解析失败，报一句莫名其妙的接口错误。
     */
    private static String escape(String s) {
        String cleaned = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return cleaned.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
