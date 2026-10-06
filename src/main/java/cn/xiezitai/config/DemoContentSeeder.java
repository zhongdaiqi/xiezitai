package cn.xiezitai.config;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.Comment;
import cn.xiezitai.entity.PageEntity;
import cn.xiezitai.entity.SysConfig;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.PageRepository;
import cn.xiezitai.repository.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 示例内容：第一次启动、且库是空的时候写入一批"能看出效果"的内容，
 * 避免部署完打开首页只有一句「还没有发布的文章」。
 *
 * <p>写入范围：4 篇已发布文章（覆盖表格 / 任务列表 / 代码块 / 引用等排版）、
 * 2 个自定义页面（关于、友链）、5 条评论（含两级回复与 1 条待审核）。</p>
 *
 * <p>三个安全阀：</p>
 * <ol>
 *   <li>开关 {@code xiezitai.seed-demo}（环境变量 {@code XIEZITAI_SEED_DEMO=false} 可关闭）；</li>
 *   <li>只在文章表和页面表**都为空**时写入，已有数据一律不动；</li>
 *   <li>写完往 sys_configs 写 {@code demo.seeded=true} 做标记 —— 用户把示例删光后重启，
 *       不会又被塞回来（想重新生成：删掉这条配置 + 清空文章/页面）。</li>
 * </ol>
 */
@Component
public class DemoContentSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoContentSeeder.class);

    /** 标记位：写过示例内容就不再写第二次（避免用户删光后被"复活"） */
    public static final String SEEDED_FLAG = "demo.seeded";

    private static final String AUTHOR = "xiezitai";

    private final ArticleRepository articles;
    private final PageRepository pages;
    private final CommentRepository comments;
    private final SysConfigRepository configs;

    public DemoContentSeeder(ArticleRepository articles, PageRepository pages,
                             CommentRepository comments, SysConfigRepository configs) {
        this.articles = articles;
        this.pages = pages;
        this.comments = comments;
        this.configs = configs;
    }

    /**
     * 需要示例内容时写入。
     *
     * @return true 表示本次真的写入了示例内容
     */
    @Transactional
    public boolean seedIfEmpty() {
        if (configs.findById(SEEDED_FLAG).isPresent()) return false;

        if (articles.count() > 0 || pages.count() > 0) {
            // 老库升级上来的场景：已经有自己的内容了，只打标记，什么都不写
            markSeeded();
            return false;
        }

        Article welcome = seedArticles();
        seedPages();
        int commentCount = seedComments(welcome);
        markSeeded();

        log.info("已写入示例内容：4 篇文章 + 2 个页面 + {} 条评论（后台可删改；"
                + "不想再生成请设 XIEZITAI_SEED_DEMO=false）", commentCount);
        return true;
    }

    private void markSeeded() {
        SysConfig flag = new SysConfig();
        flag.setConfigKey(SEEDED_FLAG);
        flag.setConfigValue("true");
        configs.save(flag);
    }

    // ------------------------------------------------------------------ 文章

    private Article seedArticles() {
        Article a = article("欢迎来到写字台", "welcome",
                "首页不该是一句「还没有发布的文章」。这篇介绍写字台能做什么、怎么开始写第一篇。",
                WELCOME_MD, "自托管博客,写字台,新手入门", 6, 128);
        article("Markdown 写作速查", "markdown-guide",
                "标题、列表、任务清单、表格、代码块、引用——这一篇本身就是排版样例，前台和编辑器看到的是同一套语法。",
                MARKDOWN_MD, "Markdown,语法,写作,排版", 4, 96);
        article("自托管博客：把写作重新交回自己手里", "why-self-host",
                "平台会关站、图床会跑路、规则会变。写了十多年之后，我把内容搬回了自己的服务器——这是为什么，以及成本有多少。",
                SELF_HOST_MD, "自托管,博客,数据主权,服务器", 2, 57);
        article("部署与维护清单", "deploy-checklist",
                "上线前、备份、日常维护各一张清单，照着过一遍能避开大部分坑。附 lite 单容器模式的整卷备份命令。",
                CHECKLIST_MD, "部署,Docker,备份,运维", 0, 23);
        return articles.findBySlug("welcome").orElse(a);
    }

    private Article article(String title, String slug, String summary, String content,
                            String keywords, int daysAgo, long views) {
        Article a = new Article();
        a.setTitle(title);
        a.setSlug(slug);
        a.setSummary(summary);
        a.setContent(content);
        a.setStatus("PUBLISHED");
        a.setAuthor(AUTHOR);
        a.setSeoKeywords(keywords);
        a.setSeoDescription(summary);
        a.setViewCount(views);
        LocalDateTime t = LocalDateTime.now().minusDays(daysAgo).withHour(10).withMinute(30)
                .withSecond(0).withNano(0);
        a.setCreatedAt(t);
        a.setPublishedAt(t);
        a.setUpdatedAt(t);
        return articles.save(a);
    }

    // ------------------------------------------------------------------ 页面

    private void seedPages() {
        page("关于", "about", ABOUT_MD, 5);
        page("友链", "links", LINKS_MD, 5);
    }

    private void page(String title, String slug, String content, int daysAgo) {
        PageEntity p = new PageEntity();
        p.setTitle(title);
        p.setSlug(slug);
        p.setContent(content);
        p.setPublished(true);
        LocalDateTime t = LocalDateTime.now().minusDays(daysAgo);
        p.setCreatedAt(t);
        p.setUpdatedAt(t);
        pages.save(p);
    }

    // ------------------------------------------------------------------ 评论

    private int seedComments(Article welcome) {
        if (welcome == null) return 0;
        Comment c1 = comment(welcome, "夜航船",
                "任务清单那个复选框是真的能勾吗？还是只是渲染出来的样子。", "APPROVED", null, null, 30);
        comment(welcome, AUTHOR,
                "是渲染出来的只读复选框，勾选状态由 Markdown 里的 [x] / [ ] 决定。后台编辑器里看到的效果和前台一致。",
                "APPROVED", c1.getId(), "夜航船", 28);

        Comment c2 = comment(welcome, "阿澜",
                "成本那段挺实用的，正打算把博客从平台搬出来。搬的时候有什么要注意的吗？", "APPROVED", null, null, 20);
        comment(welcome, "老周",
                "先把图片原图一起下下来，别继续挂在图床上。正文迁移很快，图片丢了才是真的麻烦。",
                "APPROVED", c2.getId(), "阿澜", 18);

        // 故意留一条待审核的，方便在后台「评论」里看到审核流程
        comment(welcome, "小敏", "请问支持画流程图吗？", "PENDING", null, null, 2);
        return 5;
    }

    private Comment comment(Article article, String author, String text, String status,
                            Long parentId, String replyTo, int hoursAgo) {
        Comment c = new Comment();
        c.setArticle(article);
        c.setAuthorName(author);
        c.setContent(text);
        c.setStatus(status);
        c.setParentId(parentId);
        c.setReplyToName(replyTo);
        c.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        return comments.save(c);
    }

    // ------------------------------------------------------------------ 内容

    private static final String WELCOME_MD = """
            这是「写字台」的第一篇文章。如果你正在看这句话，说明站点已经跑起来了。

            ## 这是什么

            写字台是一个**自托管**的写作与发布系统：一台自己的服务器、一个自己的域名，
            内容存在自己的数据库里，不依赖任何第三方平台。前端资源（Markdown 编辑器、
            代码样式、图标）全部本地内置，断网、内网也能正常使用。

            ## 已经能做什么

            - [x] Markdown 写作，支持表格、任务清单、代码块
            - [x] 图片与视频上传，视频支持拖进度条播放
            - [x] 登录后才能评论，两级回复，管理员审核后展示
            - [x] SEO：服务端渲染、sitemap.xml、robots.txt、OG 标签
            - [x] 企业微信机器人通知：有人访问、注册、评论时推一条消息
            - [x] AI 助手：润色纠错、自动摘要、生成封面图
            - [ ] 全文检索（还没做）
            - [ ] 多人协作写作（还没做）

            ## 怎么开始写

            1. 打开 `/admin.html`，用默认账号登录
            2. **第一件事：改密码、开两步验证（TOTP）**
            3. 在「文章」里新建，写 Markdown，保存草稿或直接发布
            4. 在「设置」里把站点地址改成自己的真实域名

            > 默认管理员账号是 `xiezitai / xiexiexie`，只适合第一次登录。公开到公网之前请务必改掉。

            ## 这个站点里有什么

            | 位置 | 地址 | 说明 |
            | --- | --- | --- |
            | 首页 | `/` | 文章列表，每页 10 篇 |
            | 后台 | `/admin.html` | 写作、媒体库、评论审核、系统设置 |
            | 站点地图 | `/sitemap.xml` | 给搜索引擎看的 |
            | 开放接口 | `/api/v1/publish` | 用 API Token 直接发文 |
            | MCP | `/api/v1/mcp` | 让 AI 助手帮你发文章 |

            这一页里的文章、页面、评论都是首次启动时生成的示例内容，在后台可以随便改、随便删。
            删光之后重启也不会再冒出来。

            写下去吧。
            """;

    private static final String MARKDOWN_MD = """
            这一篇本身就是排版样例。你在后台用同一套语法写，前台就是这个效果。

            ## 文本

            **加粗**、*斜体*、`行内代码`，以及[一个链接](https://xiezitai.cn)。

            ## 列表

            无序列表：

            - 第一项
            - 第二项
              - 嵌套的一项
            - 第三项

            有序列表：

            1. 先写标题
            2. 再写正文
            3. 最后发布

            ## 任务清单

            - [x] 搭好站点
            - [x] 写完第一篇文章
            - [ ] 配好自定义域名
            - [ ] 提交到搜索引擎

            ## 表格

            | 语法 | 效果 | 备注 |
            | --- | --- | --- |
            | `## 标题` | 二级标题 | 一级标题留给文章标题 |
            | `**粗体**` | 加粗 | 强调用，别整段都加 |
            | `> 引用` | 引用块 | 适合放结论 |
            | 三个反引号 | 代码块 | 见下方 |

            ## 代码

            ```java
            public String greet(String name) {
                return "你好，" + name;
            }
            ```

            ```bash
            docker compose -f docker-compose.lite.yml up -d
            ```

            ## 引用

            > 写作的难点从来不是打字，而是想清楚。

            ## 分割线

            ---

            以上语法在编辑器里都有工具栏和实时预览，不用背。图片可以直接粘贴进编辑器，
            视频拖进去会自动上传并用 `<video>` 标签插入。
            """;

    private static final String SELF_HOST_MD = """
            在别人的平台上写了十多年，最后我把内容搬回了自己的服务器。

            ## 为什么

            不是平台不好用，而是有三件事越来越让人不安：

            1. **图片会消失**。早期用过的图床陆续关站，几百篇里的配图变成一个个红叉。
            2. **规则会变**。今天能发的内容，明天可能因为「涉嫌营销」被限流。
            3. **数据不完全是我的**。导出按钮给的是一堆 HTML 片段，图片、评论、阅读量都不在里面。

            ## 自托管意味着什么

            - 数据在自己的数据库里，可以一条 SQL 导出全部
            - 域名是自己的，链接十年后还在
            - 排版、样式、功能自己说了算
            - 代价是：服务器要自己维护，备份要自己记得做

            最后一条才是真正的门槛。所以我把「备份」写进了[部署与维护清单](/article/deploy-checklist)，
            每次上线前都要过一遍。

            ## 成本

            一台最低配的云服务器（60~100 元/年）加一个域名（约 30 元/年），够跑很久。
            用 Docker 部署的话，升级就是两条命令：

            ```bash
            docker compose -f docker-compose.hub.yml pull
            docker compose -f docker-compose.hub.yml up -d
            ```

            如果连数据库都不想维护，还有一个单容器模式，数据用内置数据库落在本地卷里：

            ```bash
            docker compose -f docker-compose.lite.yml up -d
            ```

            ## 一点建议

            如果写作是长期行为，早点把内容放到自己手里。平台可以继续当分发渠道，
            但**原件**应该留在自己的硬盘上。
            """;

    private static final String CHECKLIST_MD = """
            上线前后按这几张清单过一遍，能避开大部分坑。

            ## 上线前

            - [x] 用 `docker compose` 起服务，数据卷挂到宿主机
            - [ ] 改掉默认管理员密码
            - [ ] 开启两步验证（TOTP）
            - [ ] 配好 HTTPS 证书（Caddy 或 Nginx + Let's Encrypt）
            - [ ] 在后台「设置」里把站点地址改成真实域名
            - [ ] 确认 `/robots.txt` 与 `/sitemap.xml` 能正常访问

            ## 备份

            - [ ] 加一条定时任务，每天导出一次数据
            - [ ] **同时**备份上传目录（图片和视频不在数据库里）
            - [ ] 至少留一份异地副本（对象存储或另一台机器）

            单容器精简模式下数据都在这一个卷里，备份最省事：

            ```bash
            docker compose -f docker-compose.lite.yml stop
            docker run --rm -v xiezitai_data:/data -v "$PWD":/backup alpine \\
              tar czf /backup/xiezitai-backup.tgz -C /data .
            docker compose -f docker-compose.lite.yml start
            ```

            ## 日常维护

            - [ ] 每月看一次磁盘占用（视频最占地方）
            - [ ] 每季度更新一次镜像
            - [ ] 升级前先备份
            - [ ] 定期看「孤立文件」列表，清掉没人引用的媒体

            ## 出问题先看哪里

            | 现象 | 先查什么 |
            | --- | --- |
            | 页面打不开 | 容器是否在运行：`docker ps` |
            | 登录后立刻掉线 | JWT 密钥是否变过、浏览器是否禁用了本地存储 |
            | 图片 404 | 上传目录是否挂了卷、路径是否有权限 |
            | 评论不显示 | 默认是待审核状态，去后台「评论」里通过一下 |
            | 升级后样式错乱 | 强制刷新，或检查反向代理是否缓存了旧静态资源 |
            """;

    private static final String ABOUT_MD = """
            ## 关于这个站点

            这里是一个用「写字台」搭起来的自托管博客，用来长期存放和发布自己的文字。

            - 技术栈：Java 17 · Spring Boot 3 · MySQL · Thymeleaf · Markdown
            - 前端资源全部本地内置，不依赖任何外部 CDN，内网也能用
            - 支持 Docker 一键部署，也有单容器 + 内置数据库的精简模式

            ## 关于我

            这里可以写自我介绍：正在做什么、以前做过什么、想找什么样的人聊天。
            在后台「页面」里点开这一篇就能直接改。

            ## 联系方式

            - 邮箱：`info@example.com`
            - GitHub：<https://github.com/zhongdaiqi/xiezitai>

            > 这个页面是首次启动时生成的示例内容，随你处置。
            """;

    private static final String LINKS_MD = """
            ## 友情链接

            | 站点 | 一句话说明 |
            | --- | --- |
            | [写字台](https://xiezitai.cn) | 本站，自托管博客系统 |
            | [项目源码](https://github.com/zhongdaiqi/xiezitai) | GitHub 仓库，MIT 协议 |

            ## 交换友链

            欢迎交换友链，把下面这些信息发到邮箱即可：

            1. 站点名称
            2. 站点地址
            3. 一句话介绍

            我会在确认内容可正常访问之后加上去。

            > 这一页的内容在后台「页面」里可以随时修改。
            """;
}
