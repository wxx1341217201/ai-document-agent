package com.wxx.aidocumentagent.document.parser;

import java.time.Duration;

import org.apache.pdfbox.pdmodel.PDDocument;

/** Recognizes one original PDF page; pageIndex is zero-based. */
@FunctionalInterface
public interface PdfPageOcr {
    String recognize(PDDocument document, int pageIndex, Duration remainingTime);
}
