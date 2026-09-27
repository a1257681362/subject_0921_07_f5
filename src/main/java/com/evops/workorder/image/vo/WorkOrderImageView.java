package com.evops.workorder.image.vo;

import com.evops.workorder.image.entity.WorkOrderImage;
import lombok.Data;

import java.time.LocalDateTime;

/** 图片对外视图：列表与报表使用缩略图地址，原图地址仅备查。 */
@Data
public class WorkOrderImageView {
    private Long id;
    private String fileName;
    private LocalDateTime capturedAt;
    private String operatorCode;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private Integer thumbWidth;
    private Integer thumbHeight;
    /** 缩略图地址——列表与报表一律用它，不直接碰原图。 */
    private String thumbUrl;
    /** 原图地址——仅详情备查。 */
    private String originalUrl;
    private LocalDateTime createTime;

    public static WorkOrderImageView of(WorkOrderImage image) {
        WorkOrderImageView view = new WorkOrderImageView();
        view.setId(image.getId());
        view.setFileName(image.getFileName());
        view.setCapturedAt(image.getCapturedAt());
        view.setOperatorCode(image.getOperatorCode());
        view.setFileSize(image.getFileSize());
        view.setWidth(image.getWidth());
        view.setHeight(image.getHeight());
        view.setThumbWidth(image.getThumbWidth());
        view.setThumbHeight(image.getThumbHeight());
        view.setThumbUrl("/api/work-orders/images/" + image.getId() + "/thumb");
        view.setOriginalUrl("/api/work-orders/images/" + image.getId() + "/original");
        view.setCreateTime(image.getCreateTime());
        return view;
    }
}
