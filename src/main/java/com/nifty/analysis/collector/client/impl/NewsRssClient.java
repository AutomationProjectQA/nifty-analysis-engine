package com.nifty.analysis.collector.client.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches REAL top-level Indian market headlines from public financial RSS feeds (no API key
 * required). Resilient by design: it tries several feeds, parses what it can, dedupes by title,
 * and returns the most recent headlines. A failure of any single feed never breaks the others.
 *
 * <p>This replaces the previous "metric-to-prose" news, which fabricated a static FII number and
 * derived GIFT Nifty from Dow — those produced the same boilerplate every time.</p>
 */
@Component
@Slf4j
public class NewsRssClient {

    /** A single parsed news headline. */
    public record Headline(String title, String link, String source, String description, LocalDateTime publishedAt) {}

    // Comma-separated list of RSS feed URLs. Defaults to widely-available Indian markets feeds.
    @Value("${nifty.news.rss-feeds:"
            + "https://www.moneycontrol.com/rss/marketreports.xml,"
            + "https://www.moneycontrol.com/rss/business.xml,"
            + "https://www.business-standard.com/rss/markets-106.rss}")
    private String rssFeeds;

    private final WebClient.Builder webClientBuilder;

    public NewsRssClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /** Returns up to {@code limit} of the most recent unique headlines across all configured feeds. */
    public List<Headline> fetchTopHeadlines(int limit) {
        Map<String, Headline> byTitle = new LinkedHashMap<>(); // dedupe by normalized title, keep order
        for (String feed : rssFeeds.split(",")) {
            String url = feed.trim();
            if (url.isEmpty()) continue;
            try {
                for (Headline h : fetchFeed(url)) {
                    String key = h.title().toLowerCase(Locale.ROOT).trim();
                    byTitle.putIfAbsent(key, h);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch/parse RSS feed {}: {}", url, e.getMessage());
            }
        }

        List<Headline> all = new ArrayList<>(byTitle.values());
        all.sort((a, b) -> b.publishedAt().compareTo(a.publishedAt())); // newest first
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private List<Headline> fetchFeed(String url) throws Exception {
        String source = hostOf(url);
        String xml = webClientBuilder.build().get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(8))
                .block();

        if (xml == null || xml.isBlank()) return List.of();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Harden against XXE — we only ever read public RSS, but never trust external entities.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        NodeList items = doc.getElementsByTagName("item");
        List<Headline> out = new ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (!(node instanceof Element item)) continue;
            String title = text(item, "title");
            if (title == null || title.isBlank()) continue;
            out.add(new Headline(
                    title.trim(),
                    text(item, "link"),
                    source,
                    stripHtml(text(item, "description")),
                    parsePubDate(text(item, "pubDate"))));
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        String content = nl.item(0).getTextContent();
        return content == null ? null : content.trim();
    }

    /** RSS pubDate is RFC-1123 (e.g. "Mon, 29 Jun 2026 09:15:00 +0530"); fall back to now on parse failure. */
    private static LocalDateTime parsePubDate(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static String stripHtml(String s) {
        if (s == null) return null;
        String cleaned = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 400 ? cleaned.substring(0, 400) + "…" : cleaned;
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return "RSS";
            host = host.replaceFirst("^www\\.", "");
            return host;
        } catch (Exception e) {
            return "RSS";
        }
    }
}
