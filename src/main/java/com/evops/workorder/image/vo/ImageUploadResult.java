package com.evops.workorder.image.vo;

import lombok.Data;

/** 上传结果：duplicated=true 表示同名同内容重复上传，幂等返回首次记录，未重复处理。 */
@Data
public class ImageUploadResult {
    private boolean duplicated;
    private WorkOrderImageView image;

    public static ImageUploadResult of(WorkOrderImageView view, boolean duplicated) {
        ImageUploadResult result = new ImageUploadResult();
        result.setDuplicated(duplicated);
        result.setImage(view);
        return result;
    }
}
