package com.smartview.health;

import java.time.OffsetDateTime;

import com.smartview.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器
 * <p>
 * 提供服务健康状态检查接口，用于监控系统、负载均衡器和容器编排平台检查服务可用性。
 * </p>
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /** 服务名称（从配置文件读取） */
    private final String serviceName;

    /**
     * 构造函数
     *
     * @param serviceName 服务名称，默认为 smartview-server
     */
    public HealthController(@Value("${spring.application.name:smartview-server}") String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 健康检查接口
     * <p>
     * 返回服务运行状态、服务名称和当前时间戳。
     * </p>
     *
     * @return 健康状态数据
     */
    @Operation(summary = "服务健康状态检查")
    @GetMapping
    public ApiResponse<HealthData> health() {
        return ApiResponse.success(new HealthData("UP", serviceName, OffsetDateTime.now()));
    }

    /**
     * 健康状态数据记录
     *
     * @param status    服务状态（UP 表示正常运行）
     * @param service   服务名称
     * @param timestamp 检查时间戳
     */
    public record HealthData(String status, String service, OffsetDateTime timestamp) {
    }
}
