package com.evops.workorder.image.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 作业单图片元数据。原图与缩略图分目录落盘，路径登记在本表；
 * (order_id, file_name) 唯一约束兜底"同名图片只处理一次"，原图永不覆盖。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_work_order_image")
public class WorkOrderImage extends BaseEntity {
    private Long orderId;
    private String orderNo;

    /** 上传方原始文件名（仅作元数据展示，不参与落盘路径，防路径穿越）。 */
    private String fileName;
    /** 落盘存储名：内容寻址 <sha256>.<ext>，天然防覆盖。 */
    private String storedName;
    private String fileSha256;
    private Long fileSize;
    private String mediaType;

    private Integer width;
    private Integer height;
    private Integer thumbWidth;
    private Integer thumbHeight;

    /** 拍摄时间（水印要素），未上传时取服务端接收时间。 */
    private LocalDateTime capturedAt;
    /** 机手工号（水印要素）。 */
    private String operatorCode;

    /** 原图相对路径（rootDir 下 original/ 子目录）。 */
    private String originalPath;
    /** 缩略图相对路径（rootDir 下 thumbs/ 独立子目录）。 */
    private String thumbPath;

    private String requestNo;
}
