package com.example.aiworkshop.entity;

public enum TaskType {
    TEXT_TO_IMAGE("文字生成图片"),
    TEXT_TO_VIDEO("文字生成视频"),
    IMAGE_TO_VIDEO("图片生成视频"),
    TEXT_TO_VIDEO_AUDIO("文字生成带音频视频"),
    IMAGE_TO_VIDEO_AUDIO("图片生成带音频视频"),
    VIDEO_REMOVE_SUBTITLE("视频去字幕"),
    FACE_CONSISTENCY("人物一致性迁移"),
    TEXT_TO_SPEECH("文字生成语音"),
    LYRICS_TO_SONG("歌词生成歌曲"),
    SCIENCE_VIDEO("科普视频生成");

    private final String description;

    TaskType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public String getWorkflowFilename() {
        return this.name() + ".json";
    }
}