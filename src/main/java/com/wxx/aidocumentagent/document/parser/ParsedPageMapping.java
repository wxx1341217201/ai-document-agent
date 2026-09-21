package com.wxx.aidocumentagent.document.parser;

/**
 * 完整文本中的字符区间与 PDF 页码的映射；endOffset 为开区间。
 */
public record ParsedPageMapping(int pageNumber, int startOffset, int endOffset) {

    public ParsedPageMapping {
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber必须大于0");
        }
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("页码映射的文本区间不合法");
        }
    }
}
