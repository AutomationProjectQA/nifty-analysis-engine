package com.nifty.analysis.controller;

import com.nifty.analysis.entity.AiReport;
import com.nifty.analysis.entity.LearningArticle;
import com.nifty.analysis.entity.MarketNews;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.AiReportRepository;
import com.nifty.analysis.repository.LearningArticleRepository;
import com.nifty.analysis.repository.MarketNewsRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.service.ContentGenerationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ReportController.class, NewsController.class, LearningArticleController.class, SignalController.class})
public class PortalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiReportRepository aiReportRepository;

    @MockBean
    private MarketNewsRepository marketNewsRepository;

    @MockBean
    private LearningArticleRepository learningArticleRepository;

    @MockBean
    private TradeSignalRepository tradeSignalRepository;

    @MockBean
    private com.nifty.analysis.repository.DecisionTraceRepository decisionTraceRepository;

    @MockBean
    private ContentGenerationService contentGenerationService;

    @Test
    public void testGetLatestReport() throws Exception {
        AiReport report = new AiReport();
        report.setId(1L);
        report.setType("PRE_MARKET");
        report.setPublishDate(LocalDate.now());
        report.setReportText("Pre-market vlog outline text");
        report.setGeneratedAt(LocalDateTime.now());

        Mockito.when(aiReportRepository.findLatestByType("PRE_MARKET"))
                .thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/v1/reports/latest?type=PRE_MARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PRE_MARKET"))
                .andExpect(jsonPath("$.reportText").value("Pre-market vlog outline text"));
    }

    @Test
    public void testGetTodayNews() throws Exception {
        MarketNews news = new MarketNews();
        news.setId(1L);
        news.setTitle("Top 5 Events");
        news.setSummary("Summary info");
        news.setImportance("HIGH");
        news.setPublishedAt(LocalDateTime.now());

        Mockito.when(marketNewsRepository.findTop5ByOrderByPublishedAtDesc())
                .thenReturn(List.of(news));

        mockMvc.perform(get("/api/v1/news/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Top 5 Events"))
                .andExpect(jsonPath("$[0].importance").value("HIGH"));
    }

    @Test
    public void testGetLearningArticles() throws Exception {
        LearningArticle article = new LearningArticle();
        article.setId(1L);
        article.setSlug("understanding-oi");
        article.setTitle("OI Article");
        article.setSummary("Summary");
        article.setContent("Content body");
        article.setCategory("Basics");
        article.setPublishedAt(LocalDateTime.now());

        Mockito.when(learningArticleRepository.findAll())
                .thenReturn(List.of(article));

        mockMvc.perform(get("/api/v1/learning/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("understanding-oi"))
                .andExpect(jsonPath("$[0].title").value("OI Article"));
    }

    @Test
    public void testGetSignals() throws Exception {
        TradeSignal signal = new TradeSignal();
        signal.setId(10L);
        signal.setSignalType("BUY_CE");
        signal.setStrike(23500);
        signal.setEntry(150.0);
        signal.setStopLoss(135.0);
        signal.setTarget1(165.0);
        signal.setTarget2(180.0);
        signal.setConfidence(85.0);
        signal.setStatus("ACTIVE");
        signal.setSignalTime(LocalDateTime.now());

        Mockito.when(tradeSignalRepository.findAllByOrderBySignalTimeDesc())
                .thenReturn(List.of(signal));

        mockMvc.perform(get("/api/v1/signals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strike").value(23500))
                .andExpect(jsonPath("$[0].signalType").value("BUY_CE"))
                .andExpect(jsonPath("$[0].entry").value(150.0))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }
}
