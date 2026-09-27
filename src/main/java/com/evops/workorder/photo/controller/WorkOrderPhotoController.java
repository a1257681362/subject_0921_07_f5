package com.evops.workorder.photo.controller;

import com.evops.common.ApiResponse;
import com.evops.workorder.photo.WorkOrderPhotoService;
import com.evops.workorder.photo.vo.PhotoView;
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * 作业单现场照片：上传（自动生成 300px 水印缩略图，原图保留）、
 * 照片列表（缩略图供列表与报表引用）、缩略图/原图读取。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderPhotoController {

    private final WorkOrderPhotoService photoService;

    public WorkOrderPhotoController(WorkOrderPhotoService photoService) {
        this.photoService = photoService;
    }

    /**
     * 上传照片：multipart 表单，字段 file（图片）、shotTime（拍摄时间 yyyy-MM-dd HH:mm:ss）、
     * operatorCode（机手工号）、requestNo（请求号）。
     * 同一作业单同名图片只处理一次，重复上传返回已登记记录（duplicate=true）。
     */
    @PostMapping("/{id}/photos")
    public ApiResponse<PhotoView> upload(@PathVariable Long id,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam String shotTime,
                                         @RequestParam String operatorCode,
                                         @RequestParam String requestNo) {
        return ApiResponse.ok(photoService.upload(id, file, shotTime, operatorCode, requestNo));
    }

    /** 作业单照片列表（含缩略图地址，供列表与报表引用）。 */
    @GetMapping("/{id}/photos")
    public ApiResponse<List<PhotoView>> list(@PathVariable Long id) {
        return ApiResponse.ok(photoService.listByOrder(id));
    }

    /** 缩略图（300px，带拍摄时间与机手工号水印）。 */
    @GetMapping("/photos/{photoId}/thumb")
    public ResponseEntity<byte[]> thumb(@PathVariable Long photoId) {
        WorkOrderPhotoService.PhotoFile file = photoService.loadThumb(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType))
                .body(file.bytes);
    }

    /** 原图下载（保留原始内容，永不覆盖）。 */
    @GetMapping("/photos/{photoId}/original")
    public ResponseEntity<byte[]> original(@PathVariable Long photoId) {
        WorkOrderPhotoService.PhotoFile file = photoService.loadOriginal(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodeFileName(file.fileName))
                .body(file.bytes);
    }

    private String encodeFileName(String fileName) {
        try {
            return URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }
}
