package com.komeowmeow.qqstudymonitor.adapter.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.komeowmeow.qqstudymonitor.application.GroupClassAffairsService;
import com.komeowmeow.qqstudymonitor.application.PrivateChatService;
import com.komeowmeow.qqstudymonitor.application.ReplyErrorMessages;
import com.komeowmeow.qqstudymonitor.config.BotConfig;
import com.komeowmeow.qqstudymonitor.config.StudyMonitorProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class QqEventListener {
    private static final Logger log = LoggerFactory.getLogger(QqEventListener.class);

    private final QqClient qqClient;
    private final BotConfig botConfig;
    private final StudyMonitorProperties studyProperties;
    private final MessagePayloadAssembler assembler;
    private final GroupClassAffairsService groupClassAffairsService;
    private final PrivateChatService privateChatService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QqEventListener(
            QqClient qqClient,
            BotConfig botConfig,
            StudyMonitorProperties studyProperties,
            MessagePayloadAssembler assembler,
            GroupClassAffairsService groupClassAffairsService,
            PrivateChatService privateChatService) {
        this.qqClient = qqClient;
        this.botConfig = botConfig;
        this.studyProperties = studyProperties;
        this.assembler = assembler;
        this.groupClassAffairsService = groupClassAffairsService;
        this.privateChatService = privateChatService;
    }

    @EventListener
    public void onOneBotEvent(OneBotEvent event) {
        JsonNode payload = event.payload();
        if (!"message".equals(payload.path("post_type").asText())) {
            return;
        }

        long senderId = payload.path("user_id").asLong();
        if (senderId == botConfig.getSelfId()) {
            return;
        }

        String messageType = payload.path("message_type").asText();
        if ("group".equals(messageType)) {
            handleGroupMessage(payload, senderId);
            return;
        }
        if ("private".equals(messageType)) {
            handlePrivateMessage(payload, senderId);
        }
    }

    private void handleGroupMessage(JsonNode payload, long senderId) {
        long groupId = payload.path("group_id").asLong();
        if (!studyProperties.isMonitoredGroup(groupId)) {
            return;
        }

        assembler.assembleAsync(payload)
                .thenCompose(result -> {
                    if (result.imageIssue() == MessagePayloadAssembler.AssembleResult.ImageIssue.TOO_LARGE) {
                        return qqClient.sendGroupMessage(groupId, "图片过大，请压缩后重发。");
                    }
                    if (result.imageIssue() == MessagePayloadAssembler.AssembleResult.ImageIssue.UNAVAILABLE) {
                        return qqClient.sendGroupMessage(groupId, "图片无法读取，请换一张或补充文字说明。");
                    }
                    if (result.message() == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CompletableFuture
                            .supplyAsync(() -> groupClassAffairsService.handle(
                                    groupId, senderId, result.message()), executor)
                            .thenCompose(answer -> answer == null || answer.isBlank()
                                    ? CompletableFuture.completedFuture(null)
                                    : qqClient.sendGroupMessage(groupId, answer));
                })
                .exceptionally(error -> {
                    log.error("群 {} 用户 {} 班务处理失败", groupId, senderId, error);
                    return qqClient.sendGroupMessage(groupId, ReplyErrorMessages.toUserMessage(error)).join();
                });
    }

    private void handlePrivateMessage(JsonNode payload, long userId) {
        assembler.assembleAsync(payload)
                .thenCompose(result -> {
                    if (result.imageIssue() == MessagePayloadAssembler.AssembleResult.ImageIssue.TOO_LARGE) {
                        return qqClient.sendPrivateMessage(userId, "图片过大，请压缩后重发。");
                    }
                    if (result.imageIssue() == MessagePayloadAssembler.AssembleResult.ImageIssue.UNAVAILABLE) {
                        return qqClient.sendPrivateMessage(userId, "图片无法读取，请换一张或补充文字。");
                    }
                    if (result.message() == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CompletableFuture
                            .supplyAsync(() -> privateChatService.handle(userId, result.message()), executor)
                            .thenCompose(answer -> qqClient.sendPrivateMessage(userId, answer));
                })
                .exceptionally(error -> {
                    log.error("私聊用户 {} 处理失败", userId, error);
                    return qqClient.sendPrivateMessage(userId, ReplyErrorMessages.toUserMessage(error)).join();
                });
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
