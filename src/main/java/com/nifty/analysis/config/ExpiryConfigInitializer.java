package com.nifty.analysis.config;

import com.nifty.analysis.util.ExpiryInstrumentSource;
import com.nifty.analysis.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Wires the 3-layer expiry calendar into {@link TimeUtil} at startup:
 * <ul>
 *   <li>Layer 3 — the configured expiry weekday ({@code nifty.expiry.day}).</li>
 *   <li>Layer 2 — the NSE holiday set loaded from {@code nifty.expiry.holidays-resource}.</li>
 *   <li>Layer 1 — the live {@link ExpiryInstrumentSource} (broker scrip master) if one is on the
 *       classpath (absent in simulation/backtest profiles, which then use Layers 2–3).</li>
 * </ul>
 * Resolved via {@link ObjectProvider} so there is no construction-time dependency cycle with the
 * data client (which itself never depends on this initializer).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiryConfigInitializer {

    @Value("${nifty.expiry.day:TUESDAY}")
    private DayOfWeek expiryDay;

    @Value("${nifty.expiry.holidays-resource:nse-holidays.txt}")
    private String holidaysResource;

    private final ObjectProvider<ExpiryInstrumentSource> instrumentSourceProvider;

    @PostConstruct
    public void init() {
        Set<LocalDate> holidays = loadHolidays();
        ExpiryInstrumentSource source = instrumentSourceProvider.getIfAvailable();
        TimeUtil.configureExpiry(expiryDay, holidays, source);
        log.info("Expiry calendar configured: day={}, holidays={} loaded from '{}', liveSource={}",
                expiryDay, holidays.size(), holidaysResource, source != null);
    }

    private Set<LocalDate> loadHolidays() {
        Set<LocalDate> holidays = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(holidaysResource);
        if (!resource.exists()) {
            log.warn("Holiday resource '{}' not found — Layer-2 expiry will ignore holidays.", holidaysResource);
            return holidays;
        }
        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String date = line.split("#", 2)[0].trim(); // strip inline comments
                if (date.isEmpty()) {
                    continue;
                }
                try {
                    holidays.add(LocalDate.parse(date));
                } catch (Exception e) {
                    log.warn("Skipping unparseable holiday line: '{}'", line);
                }
            }
        } catch (Exception e) {
            log.error("Failed to read holiday resource '{}'.", holidaysResource, e);
        }
        return holidays;
    }
}
