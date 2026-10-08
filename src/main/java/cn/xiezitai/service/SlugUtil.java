package cn.xiezitai.service;

import java.nio.charset.StandardCharsets;

/**
 * slug → 站内公开路径的**唯一出处**。
 *
 * <p>文章与页面都直接挂在根级（{@code /why-self-host}、{@code /links}），凡是需要拼站内链接的地方
 * （前台模板由 controller 传值、分发正文尾注、开放 API 的 url 字段、sitemap、通知消息）都走这里，
 * 免得各处各编一套、把老数据编坏。
 *
 * <p>编码规则（三个分支都有实际数据在跑，不能简化）：
 * <ul>
 *   <li>普通 ASCII slug（{@code why-self-host}）→ 原样；</li>
 *   <li>真中文（后台手输，或导入时已归一）→ 逐字节百分号编码，空格编成 {@code %20} 而不是 {@code +}
 *       （{@code +} 在路径里是字面量，只有 query 里才代表空格）；</li>
 *   <li>老数据里 {@code %e4%bd%a0%e5%a5%bd} 这种**已经是编码形态**的字面 slug → 原样保留。
 *       再编一层会得到 {@code %25e4...}，会被 Spring 的 StrictHttpFirewall 直接拦成 400。</li>
 * </ul>
 */
public final class SlugUtil {

    private SlugUtil() {
    }

    /** slug → 站内路径（带前导 {@code /}）；空 slug 退化成站点根 {@code /} */
    public static String publicPath(String slug) {
        String s = slug == null ? "" : slug.trim();
        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean preEncoded = c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2));
            if (preEncoded) {
                sb.append(s, i, i + 3);
                i += 2;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    // b & 0xFF：byte 是有符号的，直接喂 %02X 会把 0xE4 打成 "FFFFFFE4"
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /** 站内绝对地址：{@code https://site/why-self-host}（siteRoot 结尾的斜杠会被吃掉） */
    public static String publicUrl(String siteRoot, String slug) {
        String root = siteRoot == null ? "" : siteRoot.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + publicPath(slug);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
