package com.evops.workorder.photo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 作业单现场照片登记。原图与缩略图各存一份路径；
 * (order_id, file_name) 唯一——同一作业单同名图片只处理一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_work_order_photo")
public class WorkOrderPhoto extends BaseEntity {
    private Long orderId;
    private String orderNo;
    /** 上传方给的原始文件名（展示用）。 */
    private String fileName;
    /** 落盘文件名（内容哈希命名，全局唯一，永不复用）。 */
    private String storedName;
    /** 原图相对存储根目录路径。 */
    private String originalPath;
    /** 缩略图相对存储根目录路径（独立 thumb 目录）。 */
    private String thumbPath;
    /** 缩略图实际尺寸，列表/报表排版用。 */
    private Integer thumbWidth;
    private Integer thumbHeight;
    /** 拍摄时间（水印内容之一）。 */
    private LocalDateTime shotTime;
    /** 机手工号（水印内容之一）。 */
    private String operatorCode;
    private Long fileSize;
    /** 原图内容 SHA-256，留痕防篡改核对。 */
    private String sha256;
    private String status;
    private String requestNo;
}
