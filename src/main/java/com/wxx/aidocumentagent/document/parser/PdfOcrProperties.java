package com.wxx.aidocumentagent.document.parser;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Local OCR is optional outside the packaged Docker deployment. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.document.ocr")
public class PdfOcrProperties {

    private boolean enabled;
    @NotBlank
    private String command = "tesseract";
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_]+(?:\\+[A-Za-z0-9_]+)*")
    private String language = "chi_sim+eng";
    @Min(72) @Max(600)
    private int dpi = 300;
    @Min(1) @Max(4)
    private int maxConcurrentPages = 1;
    @Min(1) @Max(10_000)
    private int maxPages = 2_000;
    @Min(1_000_000) @Max(40_000_000)
    private long maxRenderedPixels = 16_000_000;
    private Duration pageTimeout = Duration.ofSeconds(60);
    private Duration documentTimeout = Duration.ofMinutes(25);

    @AssertTrue(message = "OCR超时必须为正数，单页不超过5分钟，整篇不超过6小时")
    public boolean isTimeoutsValid() {
        return pageTimeout != null && !pageTimeout.isNegative() && !pageTimeout.isZero()
                && pageTimeout.compareTo(Duration.ofMinutes(5)) <= 0
                && documentTimeout != null && !documentTimeout.isNegative() && !documentTimeout.isZero()
                && documentTimeout.compareTo(Duration.ofHours(6)) <= 0;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }
    public int getMaxConcurrentPages() { return maxConcurrentPages; }
    public void setMaxConcurrentPages(int maxConcurrentPages) { this.maxConcurrentPages = maxConcurrentPages; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public long getMaxRenderedPixels() { return maxRenderedPixels; }
    public void setMaxRenderedPixels(long maxRenderedPixels) { this.maxRenderedPixels = maxRenderedPixels; }
    public Duration getPageTimeout() { return pageTimeout; }
    public void setPageTimeout(Duration pageTimeout) { this.pageTimeout = pageTimeout; }
    public Duration getDocumentTimeout() { return documentTimeout; }
    public void setDocumentTimeout(Duration documentTimeout) { this.documentTimeout = documentTimeout; }
}
