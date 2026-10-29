package dev.clearflow.common;

/** 后端生成的 CSV 文件（文件名 + UTF-8 字节，含 BOM） */
public record CsvFile(String filename, byte[] data) {
}
