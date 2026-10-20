package com.wxx.aidocumentagent.document.parser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Renders one bounded page at a time and invokes Tesseract without a shell or remote service. */
@Component
public final class TesseractPdfPageOcr implements PdfPageOcr {
    private static final long MAX_OUTPUT_BYTES = 1_048_576;
    private final PdfOcrProperties properties;
    private final Semaphore permits;
    private final ProcessLauncher launcher;

    @Autowired
    public TesseractPdfPageOcr(PdfOcrProperties properties) {
        this(properties, ProcessBuilder::start);
    }

    TesseractPdfPageOcr(PdfOcrProperties properties, ProcessLauncher launcher) {
        this.properties = properties;
        this.permits = new Semaphore(properties.getMaxConcurrentPages(), true);
        this.launcher = launcher;
    }

    @Override
    public String recognize(PDDocument document, int pageIndex, Duration remainingTime) {
        long budget = Math.min(properties.getPageTimeout().toNanos(), remainingTime.toNanos());
        long deadline = System.nanoTime() + Math.max(0, budget);
        boolean acquired = false;
        Path directory = null;
        Process process = null;
        BufferedImage image = null;
        try {
            if (budget <= 0 || !permits.tryAcquire(budget, TimeUnit.NANOSECONDS)) {
                throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
            }
            acquired = true;
            var box = document.getPage(pageIndex).getCropBox();
            double scale = properties.getDpi() / 72.0;
            double pixels = Math.ceil(box.getWidth() * scale) * Math.ceil(box.getHeight() * scale);
            if (!Double.isFinite(pixels) || pixels <= 0 || pixels > properties.getMaxRenderedPixels()) {
                throw new BusinessException(DocumentErrorCode.OCR_LIMIT_EXCEEDED);
            }
            directory = Files.createTempDirectory("document-page-ocr-");
            Path input = directory.resolve("page.png");
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            image = renderer.renderImageWithDPI(pageIndex, properties.getDpi(), ImageType.RGB);
            if (!ImageIO.write(image, "png", input.toFile())) {
                throw new BusinessException(DocumentErrorCode.OCR_FAILED);
            }
            image.flush();
            image = null;
            if (System.nanoTime() >= deadline) throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);

            var command = new ProcessBuilder(properties.getCommand(), input.toString(),
                    directory.resolve("result").toString(), "-l", properties.getLanguage(),
                    "--dpi", Integer.toString(properties.getDpi()), "--oem", "1", "--psm", "3")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            command.environment().put("OMP_THREAD_LIMIT", "1");
            try {
                process = launcher.start(command);
            }
            catch (IOException unavailable) {
                throw new BusinessException(DocumentErrorCode.OCR_UNAVAILABLE,
                        DocumentErrorCode.OCR_UNAVAILABLE.defaultMessage(), unavailable);
            }
            process.getOutputStream().close();
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
            }
            Path output = directory.resolve("result.txt");
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                throw new BusinessException(DocumentErrorCode.OCR_FAILED);
            }
            if (Files.size(output) > MAX_OUTPUT_BYTES) {
                throw new BusinessException(DocumentErrorCode.OCR_LIMIT_EXCEEDED);
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(DocumentErrorCode.OCR_TIMEOUT);
        }
        catch (IOException failure) {
            throw new BusinessException(DocumentErrorCode.OCR_FAILED,
                    DocumentErrorCode.OCR_FAILED.defaultMessage(), failure);
        }
        finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (image != null) image.flush();
            if (directory != null) {
                deleteTemporary(directory.resolve("page.png"));
                deleteTemporary(directory.resolve("result.txt"));
                deleteTemporary(directory);
            }
            if (acquired) permits.release();
        }
    }

    private void deleteTemporary(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) { /* Do not replace the original OCR outcome with a cleanup failure. */ }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(ProcessBuilder command) throws IOException;
    }
}
