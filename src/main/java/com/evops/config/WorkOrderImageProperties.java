package com.evops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 作业单图片处理配置（application.yml 中 workorder.image 前缀）。 */
@Component
@ConfigurationProperties(prefix = "workorder.image")
public class WorkOrderImageProperties {

    /** 图片存储根目录：原图落 original/ 子目录，缩略图落 thumbs/ 子目录，相互独立。 */
    private String rootDir = "./data/work-order-images";
    /** 缩略图最长边像素：等比缩放，小图不放大。 */
    private int thumbSize = 300;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public int getThumbSize() { return thumbSize; }
    public void setThumbSize(int thumbSize) { this.thumbSize = thumbSize; }
}
