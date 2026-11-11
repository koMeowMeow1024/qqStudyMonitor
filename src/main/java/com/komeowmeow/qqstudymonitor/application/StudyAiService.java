package com.komeowmeow.qqstudymonitor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.PrivateMessageRepository;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class StudyAiService {
    private final QwenChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final String classAffairsPrompt;
    private final String tutoringPrompt;
    private final long replyTimeoutSeconds;
    private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public StudyAiService(
            QwenChatModel chatModel,
            ObjectMapper objectMapper,
            @Value("${ai.dashscope.reply-timeout-seconds:90}") long replyTimeoutSeconds) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.replyTimeoutSeconds = replyTimeoutSeconds;
        this.classAffairsPrompt = loadPrompt("prompts/class-affairs-system.txt");
        this.tutoringPrompt = loadPrompt("prompts/tutoring-system.txt");
    }

    @PreDestroy
    public void close() {
        aiExecutor.shutdownNow();
    }

    public AssignmentExtraction extractAssignment(IncomingMessage message) {
        String userText = "老师通知内容：\n" + message.effectiveText();
        String raw = chat(classAffairsPrompt, userText, message.images());
        try {
            String json = raw.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            JsonNode node = objectMapper.readTree(json);
            return new AssignmentExtraction(
                    node.path("title").asText("未命名作业"),
                    node.path("description").asText(""),
                    node.path("due_at").asText(""),
                    readStringList(node.path("missing_fields")));
        } catch (Exception error) {
            throw new AiReplyException("没能从通知里整理出作业信息，请补充文字说明或更清晰的截图。", error);
        }
    }

    public String tutorReply(IncomingMessage message, String materialsText, List<PrivateMessageRepository.HistoryRow> history) {
        StringBuilder context = new StringBuilder();
        if (!materialsText.isBlank()) {
            context.append("本课资料节选：\n").append(materialsText).append("\n\n");
        }
        for (PrivateMessageRepository.HistoryRow row : history) {
            String label = "user".equals(row.role()) ? "同学" : "助教";
            context.append(label).append("：").append(row.content()).append("\n");
        }
        context.append("同学当前问题：\n").append(message.effectiveText());
        return chat(tutoringPrompt, context.toString(), message.images());
    }

    private String chat(String systemPrompt, String userText, List<ImageAttachment> images) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));

        if (images != null && !images.isEmpty()) {
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(userText));
            for (ImageAttachment image : images) {
                contents.add(ImageContent.from(image.base64Data(), image.mimeType()));
            }
            messages.add(UserMessage.from(contents));
        } else {
            messages.add(UserMessage.from(userText));
        }

        try {
            ChatResponse response = CompletableFuture
                    .supplyAsync(() -> chatModel.chat(messages), aiExecutor)
                    .orTimeout(replyTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
            String answer = response.aiMessage().text();
            if (answer == null || answer.isBlank()) {
                throw new AiReplyException("模型没有返回内容，请稍后再试。", null);
            }
            return answer.trim();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof TimeoutException) {
                throw new AiReplyException(ReplyErrorMessages.toUserMessage(cause), cause);
            }
            throw new AiReplyException("模型调用失败，请稍后再试。", cause);
        }
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private static String loadPrompt(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            throw new IllegalStateException("无法加载 " + path, error);
        }
    }

    public record AssignmentExtraction(String title, String description, String dueAt, List<String> missingFields) {
    }
}
