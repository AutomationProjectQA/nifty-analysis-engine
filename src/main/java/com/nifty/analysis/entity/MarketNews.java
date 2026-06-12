package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_news")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MarketNews {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "importance", nullable = false)
    private String importance; // HIGH, MEDIUM, LOW

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}
