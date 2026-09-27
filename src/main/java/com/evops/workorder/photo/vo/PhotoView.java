package com.evops.workorder.photo.vo;

import java.time.LocalDateTime;

/** 照片视图：上传结果与列表/报表展示共用。 */
public class PhotoView {
    private Long photoId;
    private Long orderId;
    private String orderNo;
    private String fileName;
    private LocalDateTime shotTime;
    private String operatorCode;
    private Long fileSize;
    private String sha256;
    private Integer thumbWidth;
    private Integer thumbHeight;
    /** 缩略图地址（列表与报表引用）。 */
    private String thumbUrl;
    /** 原图地址（留档查看）。 */
    private String originalUrl;
    /** true = 本次为重复上传，系统未重复处理，返回的是已登记记录。 */
    private boolean duplicate;
    private LocalDateTime createTime;

    public Long getPhotoId() { return photoId; }
    public void setPhotoId(Long photoId) { this.photoId = photoId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public LocalDateTime getShotTime() { return shotTime; }
    public void setShotTime(LocalDateTime shotTime) { this.shotTime = shotTime; }
    public String getOperatorCode() { return operatorCode; }
    public void setOperatorCode(String operatorCode) { this.operatorCode = operatorCode; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Integer getThumbWidth() { return thumbWidth; }
    public void setThumbWidth(Integer thumbWidth) { this.thumbWidth = thumbWidth; }
    public Integer getThumbHeight() { return thumbHeight; }
    public void setThumbHeight(Integer thumbHeight) { this.thumbHeight = thumbHeight; }
    public String getThumbUrl() { return thumbUrl; }
    public void setThumbUrl(String thumbUrl) { this.thumbUrl = thumbUrl; }
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
