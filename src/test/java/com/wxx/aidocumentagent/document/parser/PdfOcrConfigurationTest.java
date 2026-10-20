package com.wxx.aidocumentagent.document.parser;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PdfOcrConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OcrConfiguration.class);

    @Test
    void bindsOcrOptionsAndReadablePdfWorksWithoutStartingMissingEngine() {
        runner.withPropertyValues("app.document.ocr.enabled=true", "app.document.ocr.dpi=200",
                "app.document.ocr.language=chi_sim+eng", "app.document.ocr.command=missing-local-ocr-engine")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PdfDocumentParser.class).hasSingleBean(PdfPageOcr.class);
                    assertThat(context.getBean(PdfOcrProperties.class).getDpi()).isEqualTo(200);
                    var parsed = context.getBean(PdfDocumentParser.class).parse(new DocumentSource(DocumentType.PDF,
                            "normal.pdf", new ClassPathResource("document-parser/sample.pdf")));
                    assertThat(parsed.text()).contains("Page one text", "Page three text");
                    assertThat(parsed.metadata()).containsEntry("ocrPageCount", "0");
                });
    }

    @Test
    void rejectsInvalidResourceAndTimeoutConfiguration() {
        for (String setting : new String[]{"app.document.ocr.dpi=0", "app.document.ocr.page-timeout=0s",
                "app.document.ocr.max-concurrent-pages=0", "app.document.ocr.language=chi_sim --invalid"}) {
            runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PdfOcrProperties.class)
    @Import({PdfDocumentParser.class, TesseractPdfPageOcr.class})
    static class OcrConfiguration { }
}
