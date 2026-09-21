package com.wxx.aidocumentagent.document.parser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.springframework.stereotype.Component;

/**
 * 流式读取 TXT，识别 BOM、UTF-8 和常见 UTF-16/UTF-32 编码，并拒绝二进制内容。
 */
@Component
public final class TxtDocumentParser implements DocumentParser {

    private static final int SAMPLE_SIZE = 8_192;
    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");
    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.TXT;
    }

    @Override
    public ParsedDocument parse(DocumentSource source) {
        requireSupported(source);
        try (InputStream sourceStream = source.openStream();
             BufferedInputStream input = new BufferedInputStream(sourceStream)) {
            input.mark(SAMPLE_SIZE + 4);
            byte[] sample = input.readNBytes(SAMPLE_SIZE);
            input.reset();

            Encoding encoding = detectEncoding(sample);
            input.skipNBytes(encoding.bomLength());
            return parseDecodedText(input, encoding);
        }
        catch (BusinessException exception) {
            throw exception;
        }
        catch (MalformedInputException | UnmappableCharacterException exception) {
            throw parseError(DocumentErrorCode.PARSE_INVALID_TEXT_ENCODING, exception);
        }
        catch (CharacterCodingException exception) {
            throw parseError(DocumentErrorCode.PARSE_INVALID_TEXT_ENCODING, exception);
        }
        catch (IOException exception) {
            throw parseError(DocumentErrorCode.PARSE_SOURCE_FAILURE, exception);
        }
    }

    private ParsedDocument parseDecodedText(InputStream input, Encoding encoding) throws IOException {
        TextNormalizer.NormalizingAppender normalizer = new TextNormalizer.NormalizingAppender(SAMPLE_SIZE);
        TextCharacteristics characteristics = new TextCharacteristics();
        try (Reader reader = new InputStreamReader(input,
                encoding.charset().newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[4_096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                characteristics.accept(buffer, read);
                normalizer.append(buffer, 0, read);
            }
        }

        if (characteristics.isBinary()) {
            throw new BusinessException(DocumentErrorCode.PARSE_BINARY_TEXT);
        }

        String text = normalizer.finish();
        if (text.isBlank()) {
            throw new BusinessException(DocumentErrorCode.PARSE_EMPTY_TEXT);
        }

        List<ParseWarning> warnings = TextNormalizer.containsReplacementCharacter(text)
                ? List.of(new ParseWarning(ParseWarningCode.SUSPECTED_GARBLED_TEXT,
                "文本包含替换字符，可能存在乱码", null))
                : List.of();
        List<ParsedSection> sections = toParagraphSections(text);
        return new ParsedDocument(text, sections, Map.of("encoding", encoding.label()), List.of(), warnings);
    }

    private List<ParsedSection> toParagraphSections(String text) {
        List<ParsedSection> sections = new ArrayList<>();
        int startOffset = 0;
        while (startOffset < text.length()) {
            int separatorOffset = text.indexOf("\n\n", startOffset);
            int endOffset = separatorOffset >= 0 ? separatorOffset : text.length();
            if (endOffset > startOffset) {
                sections.add(new ParsedSection(sections.size() + 1, ParsedSection.Kind.PARAGRAPH, null,
                        startOffset, endOffset, Map.of()));
            }
            startOffset = separatorOffset >= 0 ? separatorOffset + 2 : text.length();
        }
        return sections;
    }

    private Encoding detectEncoding(byte[] sample) {
        if (startsWith(sample, 0x00, 0x00, 0xFE, 0xFF)) {
            return new Encoding(UTF_32BE, 4, "UTF-32BE");
        }
        if (startsWith(sample, 0xFF, 0xFE, 0x00, 0x00)) {
            return new Encoding(UTF_32LE, 4, "UTF-32LE");
        }
        if (startsWith(sample, 0xEF, 0xBB, 0xBF)) {
            return new Encoding(StandardCharsets.UTF_8, 3, "UTF-8");
        }
        if (startsWith(sample, 0xFE, 0xFF)) {
            return new Encoding(StandardCharsets.UTF_16BE, 2, "UTF-16BE");
        }
        if (startsWith(sample, 0xFF, 0xFE)) {
            return new Encoding(StandardCharsets.UTF_16LE, 2, "UTF-16LE");
        }
        if (looksLikeUtf16(sample, true)) {
            return new Encoding(StandardCharsets.UTF_16LE, 0, "UTF-16LE");
        }
        if (looksLikeUtf16(sample, false)) {
            return new Encoding(StandardCharsets.UTF_16BE, 0, "UTF-16BE");
        }
        return new Encoding(StandardCharsets.UTF_8, 0, "UTF-8");
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeUtf16(byte[] bytes, boolean littleEndian) {
        int pairs = bytes.length / 2;
        if (pairs < 4) {
            return false;
        }
        int nullsInExpectedPosition = 0;
        int nullsInOtherPosition = 0;
        int expectedPosition = littleEndian ? 1 : 0;
        for (int index = 0; index < pairs * 2; index += 2) {
            if (bytes[index + expectedPosition] == 0) {
                nullsInExpectedPosition++;
            }
            if (bytes[index + (expectedPosition == 0 ? 1 : 0)] == 0) {
                nullsInOtherPosition++;
            }
        }
        return nullsInExpectedPosition >= 4 && nullsInExpectedPosition * 2 >= pairs
                && nullsInExpectedPosition > nullsInOtherPosition * 2;
    }

    private void requireSupported(DocumentSource source) {
        if (source == null || !supports(source.documentType())) {
            throw new BusinessException(DocumentErrorCode.PARSE_UNSUPPORTED_TYPE);
        }
    }

    private BusinessException parseError(DocumentErrorCode errorCode, Exception cause) {
        return new BusinessException(errorCode, errorCode.defaultMessage(), cause);
    }

    private record Encoding(Charset charset, int bomLength, String label) {
    }

    private static final class TextCharacteristics {

        private int characterCount;
        private int controlCharacterCount;
        private boolean containsNull;

        void accept(char[] characters, int length) {
            for (int index = 0; index < length; index++) {
                char character = characters[index];
                characterCount++;
                if (character == '\u0000') {
                    containsNull = true;
                }
                else if (Character.isISOControl(character)
                        && character != '\n' && character != '\r' && character != '\t' && character != '\f') {
                    controlCharacterCount++;
                }
            }
        }

        boolean isBinary() {
            return containsNull || (controlCharacterCount >= 8 && controlCharacterCount * 10 > characterCount);
        }
    }
}
