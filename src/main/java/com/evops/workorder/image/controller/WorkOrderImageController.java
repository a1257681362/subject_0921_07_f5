package com.evops.workorder.image.controller;

import com.evops.common.ApiResponse;
import com.evops.workorder.image.WorkOrderImageService;
import com.evops.workorder.image.vo.ImageUploadResult;
import com.evops.workorder.image.vo.WorkOrderImageView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 作业单图片：上传（自动生成 300px 水印缩略图）、列表（供列表与报表取缩略图）、原图/缩略图下载。
 * 重复上传同名图只处理一次；原图永不覆盖。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderImageController {

    private final WorkOrderImageService imageService;

    public WorkOrderImageController(WorkOrderImageService imageService) {
        this.imageService = imageService;
    }

    /**
     * 上传图片：multipart 表单，file 为图片文件；operatorCode 机手工号（必填，水印要素）；
     * capturedAt 拍摄时间（可选，yyyy-MM-dd HH:mm:ss，缺省取接收时间）；requestNo 请求号（必填）。
     */
    @PostMapping("/{id}/images")
    public ApiResponse<ImageUploadResult> upload(@PathVariable Long id,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam String operatorCode,
                                                 @RequestParam(required = false) String capturedAt,
                                                 @RequestParam String requestNo) {
        return ApiResponse.ok(imageService.upload(id, file, operatorCode, capturedAt, requestNo));
    }

    /** 作业单图片列表：每条带缩略图地址，列表与报表一律使用缩略图。 */
    @GetMapping("/{id}/images")
    public ApiResponse<List<WorkOrderImageView>> list(@PathVariable Long id) {
        return ApiResponse.ok(imageService.listForOrder(id));
    }

    /** 缩略图下载（列表与报表引用此地址）。 */
    @GetMapping("/images/{imageId}/thumb")
    public ResponseEntity<byte[]> thumb(@PathVariable Long imageId) {
        return inline(imageService.loadThumb(imageId), "thumb-");
    }

    /** 原图下载（仅备查；原图自落盘起从未被改写）。 */
    @GetMapping("/images/{imageId}/original")
    public ResponseEntity<byte[]> original(@PathVariable Long imageId) {
        return inline(imageService.loadOriginal(imageId), "");
    }

    private ResponseEntity<byte[]> inline(WorkOrderImageService.ImageContent content, String namePrefix) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.getMediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=" + namePrefix + content.getStoredName())
                .body(content.getBytes());
    }
}
