package com.imagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.engine.AgentTool;
import com.imagent.agent.engine.AgentToolContext;
import com.imagent.agent.entity.GroupReminderEntity;
import com.imagent.agent.mapper.GroupReminderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 群聊提醒创建工具（写操作工具，requiresApproval = true）
 * 在真正落库前会挂起 Agent 状态机并向会话推送人机协同审批卡片，用户同意后才唤醒执行。
 */
@Component
@RequiredArgsConstructor
public class CreateGroupReminderTool implements AgentTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GroupReminderMapper groupReminderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "create_group_reminder";
    }

    @Override
    public String description() {
        return "在当前会话中创建一条日程/会议提醒记录（属于写操作，执行前需经会话成员审批确认）。";
    }

    @Override
    public String parametersSchemaJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "title": { "type": "string", "description": "提醒标题，如：项目周会提醒" },
                    "content": { "type": "string", "description": "提醒详情内容" },
                    "remindTime": { "type": "string", "description": "计划提醒时间，格式 yyyy-MM-dd HH:mm:ss" }
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
            return String.format("创建群提醒【%s】（时间：%s）", title, remindTime);
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

        return String.format("群提醒创建成功！提醒ID：%d，标题：%s，内容：%s，触发时间：%s",
                entity.getId(), entity.getTitle(), entity.getContent(), entity.getRemindAt().format(FMT));
    }

    private LocalDateTime defaultRemindTime() {
        return LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime parseTimeGracefully(String raw) {
        try {
            return LocalDateTime.parse(raw, FMT);
        } catch (Exception e) {
            return defaultRemindTime();
        }
    }
}
