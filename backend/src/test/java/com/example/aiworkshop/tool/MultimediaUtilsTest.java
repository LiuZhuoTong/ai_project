package com.example.aiworkshop.tool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class MultimediaUtilsTest {

    private static final Logger log = LoggerFactory.getLogger(MultimediaUtilsTest.class);

    private static final String WEBM_PATH = "src/test/resources/ComfyUI_00123_.webm";

    @Test
    public void testConvertWebmToMp4() {
        log.info("========== 测试 MultimediaUtils.convertWebmToMp4 ==========");

        File webmFile = new File(WEBM_PATH);
        log.info("WebM文件路径: {}", webmFile.getAbsolutePath());
        Assertions.assertTrue(webmFile.exists(), "WebM测试文件应存在");
        log.info("WebM文件大小: {} bytes", webmFile.length());

        String mp4Path = MultimediaUtils.convertWebmToMp4(WEBM_PATH);
        log.info("转换后的MP4路径: {}", mp4Path);

        Assertions.assertNotNull(mp4Path, "转换结果不应为空");
        Assertions.assertTrue(mp4Path.toLowerCase().endsWith(".mp4"), "转换结果应为MP4格式");

        File mp4File = new File(mp4Path);
        Assertions.assertTrue(mp4File.exists(), "转换后的MP4文件应存在");
        Assertions.assertTrue(mp4File.length() > 0, "转换后的MP4文件不应为空");
        log.info("MP4文件大小: {} bytes", mp4File.length());

        log.info("========== testConvertWebmToMp4 测试通过 ==========");
    }

    @Test
    public void testConvertWebmToMp4_nullPath() {
        log.info("========== 测试 MultimediaUtils.convertWebmToMp4 空路径 ==========");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            MultimediaUtils.convertWebmToMp4(null);
        }, "空路径应抛出IllegalArgumentException");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            MultimediaUtils.convertWebmToMp4("");
        }, "空字符串路径应抛出IllegalArgumentException");

        log.info("========== testConvertWebmToMp4_nullPath 测试通过 ==========");
    }

    @Test
    public void testConvertWebmToMp4_fileNotExist() {
        log.info("========== 测试 MultimediaUtils.convertWebmToMp4 文件不存在 ==========");

        String nonExistentPath = "src/test/resources/nonexistent.webm";
        Assertions.assertThrows(RuntimeException.class, () -> {
            MultimediaUtils.convertWebmToMp4(nonExistentPath);
        }, "不存在的文件应抛出RuntimeException");

        log.info("========== testConvertWebmToMp4_fileNotExist 测试通过 ==========");
    }
}
