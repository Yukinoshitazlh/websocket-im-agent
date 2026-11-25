package com.imagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.engine.AgentTool;
import com.imagent.agent.engine.AgentToolContext;
import com.imagent.agent.entity.GroupReminderEntity;
import com.imagent.agent.mapper.GroupReminderMapper;
import com.imagent.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群聊提醒创建工具（写操作工具，requiresApproval = true）
 * 审批同意后写入 MySQL im_group_reminder，并同步加入 Redis ZSet 延迟队列（score = 触发时间戳 ms），
 * 到点后由 im-server 调度器自动向全群推送日程提醒卡片。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateGroupReminderTool implements AgentTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern RELATIVE_SEC = Pattern.compile("(\\d+)\\s*秒后");
    private static final Pattern RELATIVE_MIN = Pattern.compile("(\\d+)\\s*分钟后");

    private final GroupReminderMapper groupReminderMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "create_group_reminder";
    }

    @Override
    public String description() {
        return "在当前会话中创建一条日程/会议提醒记录（属于写操作，执行前需经会话成员审批确认，到点自动推群通知）。";
    }

    @Override
    public String parametersSchemaJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "title": { "type": "string", "description": "提醒标题，如：项目周会提醒" },
                    "content": { "type": "string", "description": "提醒详情内容" },
                    "remindTime": { "type": "string", "description": "计划提醒时间，支持 yyyy-MM-dd HH:mm:ss 或相对时间如 15秒后 / 1分钟后" }
                  },
                  "required": ["title", "content"]
                }
                """;
    }

    @Override
    public boolean requiresApproval() {
        return true;
    }

    public String buildApprovalSummary(String argumentsJson) {
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            String title = node.path("title").asText("群聊日程提醒");
            String remindTime = node.path("remindTime").asText(defaultRemindTime().format(FMT));
            return String.format("创建群提醒【%s】（触发时间：%s）", title, remindTime);
        } catch (Exception e) {
            return "创建群聊日程提醒";
        }
    }

    @Override
    public String execute(AgentToolContext context, String argumentsJson) {
        String title = "群聊日程提醒";
        String content = "按约定时间准时参加";
        LocalDateTime remindAt = defaultRemindTime();
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node.hasNonNull("title") && !node.get("title").asText().isBlank()) {
                title = node.get("title").asText().trim();
            }
            if (node.hasNonNull("content") && !node.get("content").asText().isBlank()) {
                content = node.get("content").asText().trim();
            }
            if (node.hasNonNull("remindTime") && !node.get("remindTime").asText().isBlank()) {
                remindAt = parseTimeGracefully(node.get("remindTime").asText().trim());
            }
        } catch (Exception ignored) {
        }

        GroupReminderEntity entity = new GroupReminderEntity();
        entity.setConversationId(context.getConversationId());
        entity.setCreatorId(context.getTriggerUserId());
        entity.setTitle(title);
        entity.setContent(content);
        entity.setRemindAt(remindAt);
        entity.setStatus("PENDING");
        groupReminderMapper.insert(entity);

        // 写入 Redis ZSet 延迟队列（score = 计划触发毫秒时间戳），到点由 im-server 自动弹出广播
        long triggerEpochMs = remindAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        redisTemplate.opsForZSet().add(RedisKeys.reminderDelayZSet(), String.valueOf(entity.getId()), triggerEpochMs);
        log.info("群提醒已落库并加入 Redis ZSet 延迟队列: reminderId={}, remindAt={}", entity.getId(), remindAt.format(FMT));

        return String.format("群提醒创建成功并已加入延迟队列！\n- **提醒 ID**：`%d`\n- **标题**：%s\n- **触发时间**：`%s`\n到点后系统会自动向本会话推送提醒卡片。",
                entity.getId(), entity.getTitle(), entity.getRemindAt().format(FMT));
    }

    public static LocalDateTime resolveRelativeOrDefault(String text) {
        if (text != null) {
            Matcher sm = RELATIVE_SEC.matcher(text);
            if (sm.find()) {
                long sec = Math.max(5L, Math.min(3600L, Long.parseLong(sm.group(1))));
                return LocalDateTime.now().plusSeconds(sec).withNano(0);
            }
            Matcher mm = RELATIVE_MIN.matcher(text);
            if (mm.find()) {
                long min = Math.max(1L, Math.min(1440L, Long.parseLong(mm.group(1))));
                return LocalDateTime.now().plusMinutes(min).withNano(0);
            }
        }
        return LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime defaultRemindTime() {
        return LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime parseTimeGracefully(String raw) {
        try {
            return LocalDateTime.parse(raw, FMT);
        } catch (Exception e) {
            return resolveRelativeOrDefault(raw);
        }
    }
}
