package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.repository.ArticleRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Locale;

/** 文章通用逻辑（slug、发布、访问计数、机器人通知） */
@Service
public class ArticleService {

    private final ArticleRepository articles;
    private final NotifyService notify;

    public ArticleService(ArticleRepository articles, NotifyService notify) {
        this.articles = articles;
        this.notify = notify;
    }

    public String uniqueSlug(String title) {
        String base = slugify(title);
        if (base.isBlank()) base = "post";
        String slug = base;
        int i = 1;
        while (articles.existsBySlug(slug)) {
            slug = base + "-" + (++i);
        }
        return slug;
    }

    public void publishNotify(Article article, String actor) {
        notify.notifyEvent("article", "**写字台文章发布**\n> [" + article.getTitle() + "](/article/"
                + article.getSlug() + ")\n> 作者: " + actor);
    }

    public void increaseView(Article article) {
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        article.setUpdatedAt(article.getUpdatedAt());
        articles.save(article);
    }

    public static String slugify(String input) {
        if (input == null) return "";
        String ascii = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (!ascii.isBlank()) return ascii;
        // 纯中文标题：用时间戳兜底
        return "post-" + System.currentTimeMillis();
    }

    public static boolean isPublished(Article a) {
        return "PUBLISHED".equals(a.getStatus()) && a.getPublishedAt() != null
                && !a.getPublishedAt().isAfter(LocalDateTime.now());
    }
}
