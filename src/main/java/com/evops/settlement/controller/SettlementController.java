package com.evops.settlement.controller;

import com.evops.common.ApiResponse;
import com.evops.settlement.SettlementService;
import com.evops.settlement.dto.SettlementGenerateRequest;
import com.evops.settlement.dto.SettlementTransitRequest;
import com.evops.settlement.dto.TransitionToggleRequest;
import com.evops.settlement.entity.Settlement;
import com.evops.settlement.entity.SettlementTransition;
import com.evops.settlement.vo.SettlementDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 生成结算单：已完工未结算作业单按期间一次汇总成单（period、unitPrice、operator、requestNo）。 */
    @PostMapping("/generate")
    public ApiResponse<Settlement> generate(@Valid @RequestBody SettlementGenerateRequest request) {
        return ApiResponse.ok(settlementService.generate(request));
    }

    /** 状态流转统一入口：目标状态 + 当前版本 + 操作人 + 请求号；非法迁移即拒绝。 */
    @PostMapping("/{id}/transit")
    public ApiResponse<Settlement> transit(@PathVariable Long id,
                                           @Valid @RequestBody SettlementTransitRequest request) {
        return ApiResponse.ok(settlementService.transit(id, request));
    }

    /** 结算单详情：单头 + 明细快照 + 完整流转历史。 */
    @GetMapping("/{id}")
    public ApiResponse<SettlementDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(settlementService.detail(id));
    }

    /** 结算单列表（可按 status / period 过滤）。 */
    @GetMapping
    public ApiResponse<List<Settlement>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(settlementService.list(status, period, limit));
    }

    /** 状态机迁移规则配置（配置化全集）。 */
    @GetMapping("/transitions")
    public ApiResponse<List<SettlementTransition>> transitions() {
        return ApiResponse.ok(settlementService.listTransitions());
    }

    /** 迁移规则启停：带规则版本与操作人，即时生效、重启不丢。 */
    @PostMapping("/transitions/{id}/toggle")
    public ApiResponse<SettlementTransition> toggleTransition(@PathVariable Long id,
                                                              @Valid @RequestBody TransitionToggleRequest request) {
        return ApiResponse.ok(settlementService.toggleTransition(id, request));
    }
}
