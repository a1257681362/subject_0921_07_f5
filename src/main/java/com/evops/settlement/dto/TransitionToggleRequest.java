package com.evops.settlement.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 迁移规则启停：带规则版本与操作人，变更走请求号台账。 */
@Data
public class TransitionToggleRequest {
    @NotNull(message = "启用标记不能为空")
    private Boolean enabled;
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
