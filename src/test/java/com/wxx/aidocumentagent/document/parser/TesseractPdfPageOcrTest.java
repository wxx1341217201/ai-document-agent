package com.wxx.aidocumentagent.document.parser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TesseractPdfPageOcrTest {
    @Test
    void rendersBoundedPageUsesLiteralArgumentsReadsUtf8AndDeletesTemporaryFiles() throws Exception {
        var properties = new PdfOcrProperties();
        properties.setCommand("engine path with spaces");
        var process = completedProcess(0);
        var temporary = new AtomicReference<Path>();
        var ocr = new TesseractPdfPageOcr(properties, command -> {
            assertThat(command.command().getFirst()).isEqualTo("engine path with spaces");
            assertThat(command.command()).containsSubsequence("-l", "chi_sim+eng", "--dpi", "300", "--oem", "1");
            assertThat(command.environment()).containsEntry("OMP_THREAD_LIMIT", "1");
            Path image = Path.of(command.command().get(1));
            temporary.set(image.getParent());
            assertThat(ImageIO.read(image.toFile()).getWidth()).isPositive();
            Files.writeString(Path.of(command.command().get(2) + ".txt"), "贾宝玉是贾政的儿子。", StandardCharsets.UTF_8);
            return process;
        });
        try (var document = document()) {
            assertThat(ocr.recognize(document, 0, Duration.ofSeconds(30))).isEqualTo("贾宝玉是贾政的儿子。");
        }
        assertThat(temporary.get()).doesNotExist();
    }

    @Test
    void missingEngineReturnsPublicErrorAndCleansUp() throws Exception {
        var temporary = new AtomicReference<Path>();
        var ocr = new TesseractPdfPageOcr(new PdfOcrProperties(), command -> {
            temporary.set(Path.of(command.command().get(1)).getParent());
            throw new IOException("private executable path");
        });
        try (var document = document()) {
            assertFailure(() -> ocr.recognize(document, 0, Duration.ofSeconds(30)), DocumentErrorCode.OCR_UNAVAILABLE);
        }
        assertThat(temporary.get()).doesNotExist();
    }

    @Test
    void timeoutKillsChildAndReleasesCapacityForNextPage() throws Exception {
        var timedOut = completedProcess(0);
        when(timedOut.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(timedOut.isAlive()).thenReturn(true);
        when(timedOut.destroyForcibly()).thenReturn(timedOut);
        var ocr = new TesseractPdfPageOcr(new PdfOcrProperties(), command -> timedOut);
        try (var document = document()) {
            assertFailure(() -> ocr.recognize(document, 0, Duration.ofSeconds(30)), DocumentErrorCode.OCR_TIMEOUT);
            assertFailure(() -> ocr.recognize(document, 0, Duration.ZERO), DocumentErrorCode.OCR_TIMEOUT);
        }
        verify(timedOut).destroyForcibly();
    }

    @Test
    void nonzeroExitAndOversizedOutputFailInsteadOfReturningPartialText() throws Exception {
        try (var document = document()) {
            var failed = new TesseractPdfPageOcr(new PdfOcrProperties(), command -> completedProcess(1));
            assertFailure(() -> failed.recognize(document, 0, Duration.ofSeconds(30)), DocumentErrorCode.OCR_FAILED);
            var oversized = new TesseractPdfPageOcr(new PdfOcrProperties(), command -> {
                Files.writeString(Path.of(command.command().get(2) + ".txt"), "x".repeat(1_048_577));
                return completedProcess(0);
            });
            assertFailure(() -> oversized.recognize(document, 0, Duration.ofSeconds(30)), DocumentErrorCode.OCR_LIMIT_EXCEEDED);
        }
    }

    @Test
    void oversizedPageIsRejectedBeforeRenderingOrStartingEngine() throws Exception {
        var properties = new PdfOcrProperties();
        properties.setMaxRenderedPixels(1_000_000);
        var ocr = new TesseractPdfPageOcr(properties, command -> { throw new AssertionError("must not start"); });
        try (var document = document()) {
            assertFailure(() -> ocr.recognize(document, 0, Duration.ofSeconds(30)), DocumentErrorCode.OCR_LIMIT_EXCEEDED);
        }
    }

    private Process completedProcess(int exitCode) {
        var process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        try { when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true); }
        catch (InterruptedException impossible) { throw new AssertionError(impossible); }
        when(process.exitValue()).thenReturn(exitCode);
        return process;
    }

    private PDDocument document() {
        var document = new PDDocument();
        document.addPage(new PDPage());
        return document;
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, DocumentErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
