package com.smartview.user.dto;

import com.smartview.generated.web.model.UserInfo;
import com.smartview.user.entity.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 用户实体与对外 DTO 的映射器。
 *
 * <p>集中维护可公开字段，防止控制器直接序列化用户实体而泄露密码哈希。</p>
 */
@Component
public class UserDtoMapper {

    public UserInfo toUserInfo(User user) {
        return new UserInfo(
                user.getId().toString(),
                user.getUsername(),
                user.getNickname(),
                UserInfo.StatusEnum.fromValue(user.getStatus())
        )
                .email(user.getEmail())
                .phone(user.getPhone())
                .lastLoginAt(toOffsetDateTime(user.getLastLoginAt()))
                .createdAt(toOffsetDateTime(user.getCreatedAt()));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        // 数据库存储不带时区的本地时间，响应时附加当前服务时区以符合 OpenAPI date-time 格式。
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
