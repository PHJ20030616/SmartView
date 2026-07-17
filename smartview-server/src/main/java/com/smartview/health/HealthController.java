package com.smartview.health;

import java.time.OffsetDateTime;

import com.smartview.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:smartview-server}") String serviceName) {
        this.serviceName = serviceName;
    }

    @Operation(summary = "服务健康状态检查")
    @GetMapping
    public ApiResponse<HealthData> health() {
        return ApiResponse.success(new HealthData("UP", serviceName, OffsetDateTime.now()));
    }

    public record HealthData(String status, String service, OffsetDateTime timestamp) {
    }
}
