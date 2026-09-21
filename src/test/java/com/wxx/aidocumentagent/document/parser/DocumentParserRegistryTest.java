package com.wxx.aidocumentagent.document.parser;

import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserRegistryTest {

    private final DocumentParserRegistry registry = new DocumentParserRegistry(List.of(
            new TxtDocumentParser(),
            new PdfDocumentParser(),
            new DocxDocumentParser()));

    @Test
    void 按标准化类型选择唯一解析器() {
        assertThat(DocumentType.fromExtension(".PDF")).contains(DocumentType.PDF);
        assertThat(registry.parserFor(DocumentType.TXT)).isInstanceOf(TxtDocumentParser.class);
        assertThat(registry.parserFor(DocumentType.PDF)).isInstanceOf(PdfDocumentParser.class);
        assertThat(registry.parserFor(DocumentType.DOCX)).isInstanceOf(DocxDocumentParser.class);
    }

    @Test
    void 缺少解析器返回稳定错误码() {
        DocumentParserRegistry emptyRegistry = new DocumentParserRegistry(List.of());

        assertThatThrownBy(() -> emptyRegistry.parserFor(DocumentType.TXT))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.PARSE_UNSUPPORTED_TYPE));
    }
}
