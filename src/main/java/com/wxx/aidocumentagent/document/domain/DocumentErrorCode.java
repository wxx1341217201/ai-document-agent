package com.wxx.aidocumentagent.document.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 文档上传和元数据操作使用的稳定错误码。
 */
public enum DocumentErrorCode implements ErrorCode {

    DOCUMENT_NOT_FOUND("DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "文档不存在"),
    FILE_NOT_FOUND("DOCUMENT_FILE_NOT_FOUND", HttpStatus.NOT_FOUND, "原文件不存在"),
    READ_FAILURE("DOCUMENT_READ_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, "原文件读取失败，请稍后重试"),
    DUPLICATE_DOCUMENT("DUPLICATE_DOCUMENT", HttpStatus.CONFLICT, "该知识库已存在相同内容的文档"),
    INVALID_FILE_NAME("INVALID_FILE_NAME", HttpStatus.BAD_REQUEST, "文件名不合法"),
    UNSUPPORTED_FILE_TYPE("UNSUPPORTED_FILE_TYPE", HttpStatus.BAD_REQUEST, "仅支持PDF、DOCX和TXT文件"),
    EMPTY_FILE("EMPTY_FILE", HttpStatus.BAD_REQUEST, "不允许上传空文件"),
    INVALID_FILE_CONTENT("INVALID_FILE_CONTENT", HttpStatus.BAD_REQUEST, "文件内容与声明类型不匹配或已损坏"),
    PARSE_UNSUPPORTED_TYPE("DOCUMENT_PARSE_UNSUPPORTED_TYPE", HttpStatus.BAD_REQUEST, "不支持的文档解析类型"),
    PARSE_ENCRYPTED_PDF("DOCUMENT_PARSE_ENCRYPTED_PDF", HttpStatus.BAD_REQUEST, "PDF已加密，无法解析"),
    PARSE_CORRUPTED_DOCUMENT("DOCUMENT_PARSE_CORRUPTED_DOCUMENT", HttpStatus.BAD_REQUEST, "文档已损坏或格式不正确"),
    PARSE_EMPTY_TEXT("DOCUMENT_PARSE_EMPTY_TEXT", HttpStatus.BAD_REQUEST, "文档不包含可解析文本"),
    PARSE_UNREADABLE_TEXT("DOCUMENT_PARSE_UNREADABLE_TEXT", HttpStatus.BAD_REQUEST,
            "PDF文字层无法正确解析，请先进行OCR识别或上传文字正常的TXT/DOCX文件"),
    OCR_UNAVAILABLE("DOCUMENT_OCR_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
            "本地OCR引擎不可用，请安装Tesseract及中文语言包，或使用项目Docker部署"),
    OCR_FAILED("DOCUMENT_OCR_FAILED", HttpStatus.UNPROCESSABLE_ENTITY,
            "PDF文字识别失败，请检查OCR语言包，或上传更清晰的文档"),
    OCR_TIMEOUT("DOCUMENT_OCR_TIMEOUT", HttpStatus.REQUEST_TIMEOUT,
            "PDF文字识别超时，请将长文档拆分后上传，或调整OCR处理时限"),
    OCR_LIMIT_EXCEEDED("DOCUMENT_OCR_LIMIT_EXCEEDED", HttpStatus.BAD_REQUEST,
            "PDF超出OCR页面或图像处理上限，请拆分文档或降低OCR分辨率"),
    PARSE_INVALID_TEXT_ENCODING("DOCUMENT_PARSE_INVALID_TEXT_ENCODING", HttpStatus.BAD_REQUEST, "TXT文档编码无效或不受支持"),
    PARSE_BINARY_TEXT("DOCUMENT_PARSE_BINARY_TEXT", HttpStatus.BAD_REQUEST, "TXT文档包含二进制内容"),
    PARSE_SOURCE_FAILURE("DOCUMENT_PARSE_SOURCE_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, "读取文档内容失败"),
    STORAGE_FAILURE("DOCUMENT_STORAGE_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败，请稍后重试");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    DocumentErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
