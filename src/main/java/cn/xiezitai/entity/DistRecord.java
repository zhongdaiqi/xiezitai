package cn.xiezitai.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 一条「文章 → 外部站点/账号」的分发记录。
 *
 * <p>粒度是 <b>文章 × 目标</b>（{@code articleId + channel + targetId} 唯一）：
 * 一篇本地文章分发到某个 WordPress 站点后只有一行记录，这行就是「这篇文章在那个站点上的对应关系」。
 * 于是「已分发过」只需要看这一行在不在；「更新之前分发的文章」用本行的 {@link #remotePostId}，
 * 「分发一个新文章」则用新拿到的远端 id 覆盖本行。
 *
 * <p>{@link #targetName} / {@link #targetUrl} 是**快照**：站点被删或改名后，
 * 历史记录仍然能显示成人看得懂的样子，不至于变成一条光秃秃的 id。
 */
@Entity
@Table(name = "dist_records", indexes = {
        @Index(name = "idx_dist_article", columnList = "articleId"),
        @Index(name = "idx_dist_target", columnList = "channel,targetId"),
        @Index(name = "uk_dist_key", columnList = "articleId,channel,targetId", unique = true)
})
public class DistRecord {

    /** 目标类型：WordPress 站点 */
    public static final String CHANNEL_WP = "wp";
    /** 目标类型：博客园账号 */
    public static final String CHANNEL_CNBLOG = "cnblog";

    /** 原文分发：正文原样发出 */
    public static final String MODE_ORIGINAL = "original";
    /** 转载分发：正文尾部追加「首发于本站 + 原文链接」 */
    public static final String MODE_REPOST = "repost";

    /** 正文按 Markdown 原文发（本站正文本来就是 Markdown） */
    public static final String FORMAT_MARKDOWN = "markdown";
    /** 正文先渲染成 HTML 再发（目标站点没有 Markdown 插件时用） */
    public static final String FORMAT_HTML = "html";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本地文章 id */
    @Column(nullable = false)
    private Long articleId;

    /** 目标类型：wp / cnblog */
    @Column(nullable = false, length = 20)
    private String channel;

    /** 目标 id：wp_sites.id 或 cn_sites.id */
    @Column(nullable = false)
    private Long targetId;

    /** 目标展示名快照 */
    @Column(length = 200)
    private String targetName;

    /** 目标网址快照（站点根地址 / MetaWeblog 接口地址） */
    @Column(length = 500)
    private String targetUrl;

    /** 远端文章 id（WP post id / 博客园 postid）；「更新」时拿它调对方接口 */
    private Long remotePostId;

    /** 远端文章链接（分发成功后回填，前端可直接点开核对） */
    @Column(length = 800)
    private String remoteUrl;

    /** 最近一次分发的方式：original / repost */
    @Column(length = 20)
    private String mode = MODE_ORIGINAL;

    /** 最近一次的正文格式：markdown / html */
    @Column(length = 20)
    private String format = FORMAT_MARKDOWN;

    /** 这篇一共往该目标发过几次（含「更新」与「新文章」） */
    private Integer distCount = 1;

    private LocalDateTime firstDistAt = LocalDateTime.now();
    private LocalDateTime lastDistAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public Long getRemotePostId() { return remotePostId; }
    public void setRemotePostId(Long remotePostId) { this.remotePostId = remotePostId; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public Integer getDistCount() { return distCount; }
    public void setDistCount(Integer distCount) { this.distCount = distCount; }
    public LocalDateTime getFirstDistAt() { return firstDistAt; }
    public void setFirstDistAt(LocalDateTime firstDistAt) { this.firstDistAt = firstDistAt; }
    public LocalDateTime getLastDistAt() { return lastDistAt; }
    public void setLastDistAt(LocalDateTime lastDistAt) { this.lastDistAt = lastDistAt; }
}
