package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false)
    private String type; // e.g. "PRE_MARKET", "POST_MARKET"

    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;

    @Column(name = "report_text", nullable = false, columnDefinition = "TEXT")
    private String reportText;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
