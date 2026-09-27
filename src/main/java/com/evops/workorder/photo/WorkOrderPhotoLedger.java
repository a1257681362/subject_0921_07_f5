package com.evops.workorder.photo;

import com.evops.common.BizType;
import com.evops.request.RequestRecordService;
import com.evops.workorder.photo.entity.WorkOrderPhoto;
import com.evops.workorder.photo.mapper.WorkOrderPhotoMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 照片台账写入：请求号登记与照片登记同一事务，一起提交或整体回退。
 * 独立组件承载事务边界（调用方 WorkOrderPhotoService 先做文件处理，不进事务）。
 */
@Component
public class WorkOrderPhotoLedger {

    private final RequestRecordService requestRecordService;
    private final WorkOrderPhotoMapper photoMapper;

    public WorkOrderPhotoLedger(RequestRecordService requestRecordService,
                                WorkOrderPhotoMapper photoMapper) {
        this.requestRecordService = requestRecordService;
        this.photoMapper = photoMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerAndInsert(WorkOrderPhoto photo) {
        requestRecordService.register(photo.getRequestNo(), BizType.WORK_ORDER_PHOTO_UPLOAD,
                photo.getOrderId() + ":" + photo.getFileName());
        photoMapper.insert(photo);
    }
}
