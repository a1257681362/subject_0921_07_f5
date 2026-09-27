package com.evops.workorder.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 作业单现场照片配置（photo.*）。 */
@Component
@ConfigurationProperties(prefix = "photo")
public class PhotoProperties {

    /** 存储根目录：original/ 原图、thumb/ 缩略图，两个子目录相互独立。 */
    private String rootDir = "./data/photos";

    /** 缩略图最长边（px），等比缩放，不放大小图。 */
    private int thumbMaxSide = 300;

    /** 允许的图片格式（按内容识别，不只信扩展名）。 */
    private List<String> allowedTypes = Arrays.asList("jpg", "jpeg", "png");

    /** 单张大小上限（字节）。 */
    private long maxFileSize = 20L * 1024 * 1024;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public int getThumbMaxSide() { return thumbMaxSide; }
    public void setThumbMaxSide(int thumbMaxSide) { this.thumbMaxSide = thumbMaxSide; }
    public List<String> getAllowedTypes() { return Collections.unmodifiableList(allowedTypes); }
    public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
}
