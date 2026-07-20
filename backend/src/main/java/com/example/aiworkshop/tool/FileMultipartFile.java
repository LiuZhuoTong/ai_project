package com.example.aiworkshop.tool;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

public class FileMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public FileMultipartFile(File file) throws IOException {
        this("file", file.getName(), Files.probeContentType(file.toPath()), Files.readAllBytes(file.toPath()));
    }

    public FileMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.content = content != null ? content : new byte[0];
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }
}