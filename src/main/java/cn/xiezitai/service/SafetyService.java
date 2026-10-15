package cn.xiezitai.service;

import cn.xiezitai.entity.AbuseReport;
import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.Comment;
import cn.xiezitai.entity.User;
import cn.xiezitai.entity.UserBlock;
import cn.xiezitai.repository.AbuseReportRepository;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.UserBlockRepository;
import cn.xiezitai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容安全：屏蔽用户 + 举报工单（App Store 准则 1.2 User-Generated Content）。
 *
 * <h3>审查员的三条硬要求与对应落点</h3>
 * <ol>
 *   <li><b>用户能举报不良内容</b> → {@link #report}：文章 / 评论 / 用户三类举报，落成工单并通知管理员。</li>
 *   <li><b>用户能屏蔽施虐者，且屏蔽时通知开发者、内容立刻从信息流移除</b> → {@link #block}：
 *       屏蔽落库 + 企微通知管理员（这就是"notify the developer"）；
 *       {@link #blockedUsernamesFor} 供文章流与评论列表做过滤，屏蔽后下一次拉取即不可见。</li>
 *   <li><b>开发者 24 小时内删内容 + 踢人</b> → {@link #resolve}：管理员一键处置，
 *       支持「删内容」「封禁账号」「删内容并封禁」「驳回」四种动作，全程留痕、企微留通知。</li>
 * </ol>
 */
@Service
public class SafetyService {

    private static final Logger log = LoggerFactory.getLogger(SafetyService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 举报理由白名单，与 AbuseReport.REASON_* 一致 */
    private static final Set<String> REASONS = Set.of(
            AbuseReport.REASON_SPAM, AbuseReport.REASON_ABUSE, AbuseReport.REASON_PORN,
            AbuseReport.REASON_VIOLENCE, AbuseReport.REASON_ILLEGAL, AbuseReport.REASON_OTHER);

    private final UserBlockRepository blocks;
    private final AbuseReportRepository reports;
    private final UserRepository users;
    private final ArticleRepository articles;
    private final CommentRepository comments;
    private final NotifyService notify;

    public SafetyService(UserBlockRepository blocks, AbuseReportRepository reports, UserRepository users,
                         ArticleRepository articles, CommentRepository comments, NotifyService notify) {
        this.blocks = blocks;
        this.reports = reports;
        this.users = users;
        this.articles = articles;
        this.comments = comments;
        this.notify = notify;
    }

    /* ==================== 屏蔽 ==================== */

    /**
     * 当前登录用户屏蔽了哪些人。
     *
     * <p>未登录（auth 为 null 或账号已失效）返回空集 —— 匿名访客没有屏蔽关系，
     * 看到的仍是完整公开内容。这个集合被文章流与评论列表直接拿去做过滤，调用频率高，
     * 但只有一条 indexed 单表查询，成本可接受。
     */
    public Set<String> blockedUsernamesFor(Authentication auth) {
        User me = currentUser(auth);
        if (me == null) return Set.of();
        return blocks.findBlockedUsernames(me.getId());
    }

    /** 我的屏蔽名单（设置页展示，按屏蔽时间倒序） */
    public List<UserBlock> myBlocks(User me) {
        return blocks.findByBlockerIdOrderByCreatedAtDesc(me.getId());
    }

    /**
     * 屏蔽某个用户。
     *
     * <p>三条约束：不能屏蔽自己（否则会把自己的文章全过滤掉，看起来像 App 坏了）；
     * 目标账号必须存在（避免屏蔽一个不存在的名字，UI 上显示一条永远无效的屏蔽）；
     * 幂等 —— 重复屏蔽同一人直接返回，不产生重复记录。
     *
     * <p>屏蔽动作会**通知开发者**：这是审查员明确要求的，走企微机器人推给管理员。
     */
    @Transactional
    public Map<String, Object> block(User me, String targetUsername) {
        String target = targetUsername == null ? "" : targetUsername.trim();
        if (target.isEmpty()) return result(false, "请输入要屏蔽的用户名");
        if (target.equals(me.getUsername())) return result(false, "不能屏蔽自己");
        User targetUser = users.findByUsername(target).orElse(null);
        if (targetUser == null) return result(false, "用户不存在：" + target);

        if (blocks.existsByBlockerIdAndBlockedUsername(me.getId(), target)) {
            return result(true, "已屏蔽 " + target);
        }
        UserBlock b = new UserBlock();
        b.setBlockerId(me.getId());
        b.setBlockerUsername(me.getUsername());
        b.setBlockedUsername(target);
        blocks.save(b);

        // 通知开发者 —— 准则 1.2 要求屏蔽行为要触达开发者，便于发现反复被屏蔽的施虐账号
        long total = blocks.countByBlockedUsername(target);
        notify.notifyEvent("comment", "**写字台：用户被屏蔽**\n> 屏蔽人: " + me.getUsername()
                + "\n> 被屏蔽: " + target
                + "\n> 该账号累计被 " + total + " 人屏蔽"
                + (total >= 3 ? "\n> <font color=\"warning\">⚠ 多次被屏蔽，建议核查该账号内容</font>" : "")
                + "\n> 时间: " + LocalDateTime.now().format(FMT)
                + "\n> 已自动从屏蔽者的文章流与评论列表移除其内容");
        log.info("用户 {} 屏蔽了 {}（累计被 {} 人屏蔽）", me.getUsername(), target, total);
        return result(true, "已屏蔽 " + target + "，其内容已不再显示");
    }

    @Transactional
    public Map<String, Object> unblock(User me, String targetUsername) {
        String target = targetUsername == null ? "" : targetUsername.trim();
        if (target.isEmpty()) return result(false, "请输入要解除屏蔽的用户名");
        blocks.deleteByBlockerIdAndBlockedUsername(me.getId(), target);
        return result(true, "已解除对 " + target + " 的屏蔽");
    }

    /* ==================== 举报 ==================== */

    /**
     * 提交举报。
     *
     * <p>三类对象都走同一张工单表，管理员在一个列表里处理，不用在文章后台和评论后台之间来回跳。
     * 同一人对同一对象重复举报只保留一条 OPEN 工单（刷单防护，也避免后台被灌满）。
     *
     * @param type    ARTICLE / COMMENT / USER
     * @param reason  SPAM / ABUSE / PORN / VIOLENCE / ILLEGAL / OTHER
     */
    @Transactional
    public Map<String, Object> report(User reporter, String type, Long targetId, String reason, String detail) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!Set.of(AbuseReport.TYPE_ARTICLE, AbuseReport.TYPE_COMMENT, AbuseReport.TYPE_USER).contains(t)) {
            return result(false, "举报对象类型不合法");
        }
        if (targetId == null) return result(false, "缺少举报对象");
        String r = reason == null ? "" : reason.trim().toUpperCase();
        if (!REASONS.contains(r)) r = AbuseReport.REASON_OTHER;

        // 解析被举报对象，顺便把快照写进工单（内容删了之后工单还得能看懂）
        String summary;
        String author;
        switch (t) {
            case AbuseReport.TYPE_ARTICLE -> {
                Article a = articles.findById(targetId).orElse(null);
                if (a == null) return result(false, "被举报的文章不存在");
                if (!"PUBLISHED".equals(a.getStatus())) return result(false, "只能举报已发布的文章");
                summary = clip(a.getTitle(), 200);
                author = a.getAuthor();
            }
            case AbuseReport.TYPE_COMMENT -> {
                Comment c = comments.findByIdWithArticle(targetId).orElse(null);
                if (c == null) return result(false, "被举报的评论不存在");
                summary = clip(c.getContent(), 200);
                author = c.getAuthorName();
            }
            default -> {
                User u = users.findById(targetId).orElse(null);
                if (u == null) return result(false, "被举报的用户不存在");
                if (u.getId().equals(reporter.getId())) return result(false, "不能举报自己");
                summary = u.getUsername();
                author = u.getUsername();
            }
        }

        // 重复举报去重
        if (reports.countByReporterUsernameAndTypeAndTargetIdAndStatus(
                reporter.getUsername(), t, targetId, AbuseReport.STATUS_OPEN) > 0) {
            return result(true, "你已举报过该内容，管理员会尽快处理");
        }

        AbuseReport rep = new AbuseReport();
        rep.setType(t);
        rep.setTargetId(targetId);
        rep.setTargetSummary(summary);
        rep.setTargetAuthor(author);
        rep.setReporterUsername(reporter.getUsername());
        rep.setReason(r);
        rep.setDetail(clip(detail, 500));
        rep.setStatus(AbuseReport.STATUS_OPEN);
        reports.save(rep);

        long open = reports.countByStatus(AbuseReport.STATUS_OPEN);
        notify.notifyEvent("comment", "**写字台：收到内容举报（待处理）**\n> 类型: " + typeLabel(t)
                + "\n> 理由: " + rep.getReasonLabel()
                + "\n> 对象: " + summary
                + "\n> 作者: " + (author == null ? "?" : author)
                + "\n> 举报人: " + reporter.getUsername()
                + "\n> 工单号: #" + rep.getId()
                + "\n> <font color=\"warning\">请在 24 小时内到后台「举报处理」处置</font>"
                + "\n> 当前待处理: " + open + " 单");
        log.info("举报工单 #{} 已创建：{} {} by {}", rep.getId(), t, targetId, reporter.getUsername());

        Map<String, Object> out = result(true, "举报已提交，我们会在 24 小时内处理");
        out.put("reportId", rep.getId());
        return out;
    }

    /* ==================== 管理员处置 ==================== */

    /**
     * 处置举报工单 —— 准则 1.2 的「24 小时内删内容 + 踢人」在这里一键完成。
     *
     * <p>四种动作：
     * <ul>
     *   <li>{@code remove_content}：删掉被举报内容（用户类型的举报则删其全部文章与评论）</li>
     *   <li>{@code ban_user}：封禁内容作者（踢下线，无法再登录）</li>
     *   <li>{@code remove_and_ban}：两者都做 —— 审查员要求的标准处置</li>
     *   <li>{@code dismiss}：举报不成立，结单不处置</li>
     * </ul>
     *
     * <p>整个方法在一个事务里：删内容与封禁要么都成功，要么一起回滚，
     * 不会出现「内容删了但人还在继续发」这种半吊子状态。
     */
    @Transactional
    public Map<String, Object> resolve(Long reportId, String action, String note, User admin) {
        AbuseReport rep = reports.findById(reportId).orElse(null);
        if (rep == null) return result(false, "工单不存在：" + reportId);
        if (!AbuseReport.STATUS_OPEN.equals(rep.getStatus())) {
            return result(false, "工单已处理（" + rep.getStatus() + "），不可重复处置");
        }
        String act = action == null ? "" : action.trim();
        if (!Set.of(AbuseReport.ACTION_REMOVE, AbuseReport.ACTION_BAN,
                AbuseReport.ACTION_REMOVE_AND_BAN, AbuseReport.ACTION_DISMISS).contains(act)) {
            return result(false, "处置动作不合法：" + act);
        }

        List<String> done = new ArrayList<>();
        if (AbuseReport.ACTION_REMOVE.equals(act) || AbuseReport.ACTION_REMOVE_AND_BAN.equals(act)) {
            done.addAll(removeContent(rep));
        }
        if (AbuseReport.ACTION_BAN.equals(act) || AbuseReport.ACTION_REMOVE_AND_BAN.equals(act)) {
            done.add(banAuthor(rep, note));
        }

        rep.setActionTaken(act);
        rep.setHandledBy(admin.getUsername());
        rep.setHandledAt(LocalDateTime.now());
        rep.setHandleNote(clip(note, 500));
        rep.setStatus(AbuseReport.ACTION_DISMISS.equals(act)
                ? AbuseReport.STATUS_DISMISSED : AbuseReport.STATUS_RESOLVED);
        reports.save(rep);

        long hours = java.time.Duration.between(rep.getCreatedAt(), LocalDateTime.now()).toHours();
        notify.notifyEvent("comment", "**写字台：举报已处置**\n> 工单号: #" + rep.getId()
                + "\n> 处置: " + actionLabel(act)
                + "\n> 对象: " + rep.getTargetSummary()
                + "\n> 作者: " + (rep.getTargetAuthor() == null ? "?" : rep.getTargetAuthor())
                + "\n> 结果: " + String.join("；", done)
                + "\n> 处理人: " + admin.getUsername()
                + "\n> 用时: " + hours + " 小时" + (hours > 24 ? " <font color=\"warning\">（已超 24h）</font>" : ""));
        log.info("举报工单 #{} 由 {} 处置为 {}，结果 {}", rep.getId(), admin.getUsername(), act, done);

        Map<String, Object> out = result(true, "已处置：" + String.join("；", done));
        out.put("actions", done);
        out.put("status", rep.getStatus());
        return out;
    }

    /** 删除被举报内容，返回可读的处置结果列表 */
    private List<String> removeContent(AbuseReport rep) {
        List<String> done = new ArrayList<>();
        switch (rep.getType()) {
            case AbuseReport.TYPE_ARTICLE -> {
                Article a = articles.findById(rep.getTargetId()).orElse(null);
                if (a == null) { done.add("文章已被删除"); break; }
                articles.delete(a);
                done.add("已删除文章《" + clip(a.getTitle(), 30) + "》");
            }
            case AbuseReport.TYPE_COMMENT -> {
                Comment c = comments.findById(rep.getTargetId()).orElse(null);
                if (c == null) { done.add("评论已被删除"); break; }
                List<Comment> children = comments.findByParentId(c.getId());
                if (!children.isEmpty()) comments.deleteAll(children);
                comments.delete(c);
                done.add("已删除评论，同时移除 " + children.size() + " 条回复");
            }
            default -> {
                User u = users.findById(rep.getTargetId()).orElse(null);
                if (u == null) { done.add("用户已不存在"); break; }
                List<Comment> cs = comments.findByAuthorName(u.getUsername());
                int orphans = 0;
                for (Comment c : cs) {
                    List<Comment> children = comments.findByParentId(c.getId());
                    comments.deleteAll(children);
                    orphans += children.size();
                }
                comments.deleteAll(cs);
                List<Article> as = articles.findByAuthor(u.getUsername());
                articles.deleteAll(as);
                done.add("已删除该用户 " + as.size() + " 篇文章、" + (cs.size() + orphans) + " 条评论");
            }
        }
        return done;
    }

    /**
     * 封禁被举报内容的作者：踢下线 + 禁止再登录。
     *
     * <p>直接置 {@code enabled=false} 而不是另起一套状态，是为了**复用已有的鉴权链路** ——
     * JwtAuthFilter 与 AuthController 本来就检查 enabled，封禁后已签发的令牌立刻失效，
     * 不需要等它自然过期（默认 72 小时，等不起）。
     */
    private String banAuthor(AbuseReport rep, String note) {
        String name = rep.getTargetAuthor();
        if (name == null || name.isBlank()) return "无法封禁：内容作者信息缺失";
        User u = users.findByUsername(name).orElse(null);
        if (u == null) return "无法封禁：账号 " + name + " 已不存在";
        if ("ADMIN".equals(u.getRole())) return "已跳过封禁：目标为管理员账号";
        u.setEnabled(false);
        u.setBanned(true);
        u.setBannedReason(clip(note == null || note.isBlank()
                ? "因发布违规内容被封禁（举报工单 #" + rep.getId() + "）" : note, 200));
        u.setBannedAt(LocalDateTime.now());
        users.save(u);
        return "已封禁账号 " + name + " 并强制下线";
    }

    /** 管理员解封（误封时给恢复入口） */
    @Transactional
    public Map<String, Object> unban(String username, User admin) {
        User u = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (u == null) return result(false, "用户不存在");
        u.setEnabled(true);
        u.setBanned(false);
        u.setBannedReason(null);
        u.setBannedAt(null);
        users.save(u);
        log.info("管理员 {} 解封了账号 {}", admin.getUsername(), u.getUsername());
        return result(true, "已解封 " + u.getUsername());
    }

    /* ==================== 辅助 ==================== */

    /** 从 Authentication 取当前用户；未登录或账号已失效返回 null */
    public User currentUser(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        return users.findByUsername(auth.getName()).orElse(null);
    }

    private static String typeLabel(String t) {
        return switch (t) {
            case AbuseReport.TYPE_ARTICLE -> "文章";
            case AbuseReport.TYPE_COMMENT -> "评论";
            default -> "用户";
        };
    }

    private static String actionLabel(String a) {
        return switch (a) {
            case AbuseReport.ACTION_REMOVE -> "删除内容";
            case AbuseReport.ACTION_BAN -> "封禁账号";
            case AbuseReport.ACTION_REMOVE_AND_BAN -> "删除内容并封禁账号";
            default -> "举报不成立（驳回）";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static Map<String, Object> result(boolean ok, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok);
        m.put("message", msg);
        return m;
    }
}
