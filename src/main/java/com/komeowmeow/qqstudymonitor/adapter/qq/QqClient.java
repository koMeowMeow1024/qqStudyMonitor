package com.komeowmeow.qqstudymonitor.adapter.qq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.komeowmeow.qqstudymonitor.config.BotConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class QqClient {
    private static final Logger log = LoggerFactory.getLogger(QqClient.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final long API_TIMEOUT_SECONDS = 15;
    private static final long MAX_RECONNECT_DELAY_SECONDS = 30;

    private final BotConfig botConfig;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "onebot-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final Map<String, CompletableFuture<JsonNode>> pendingApiCalls = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile boolean shuttingDown;

    public QqClient(BotConfig botConfig, ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.botConfig = botConfig;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        if (!botConfig.isEnabled()) {
            log.info("QQ Bot 暂未启用");
            return;
        }
        openWebSocket();
    }

    private void openWebSocket() {
        if (shuttingDown || !botConfig.isEnabled() || webSocket != null) {
            return;
        }

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        String accessToken = botConfig.getAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        builder.buildAsync(botConfig.getOneBotWebSocketUrl(), new OneBotListener())
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.error("无法连接 NapCat OneBot WebSocket", error);
                        scheduleReconnect();
                        return;
                    }
                    if (shuttingDown) {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "application is stopping");
                        return;
                    }
                    this.webSocket = socket;
                    reconnectAttempts.set(0);
                    log.info("已连接 NapCat：{}", botConfig.getOneBotWebSocketUrl());
                });
    }

    private void scheduleReconnect() {
        if (shuttingDown || !botConfig.isEnabled()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delaySeconds = Math.min(MAX_RECONNECT_DELAY_SECONDS, 1L << Math.min(attempt - 1, 5));
        log.info("{} 秒后进行第 {} 次重连", delaySeconds, attempt);
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            openWebSocket();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public CompletableFuture<JsonNode> callAction(String action, JsonNode params) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("未连接到 NapCat"));
        }

        String echo = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingApiCalls.put(echo, responseFuture);
        responseFuture.orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            pendingApiCalls.remove(echo);
            if (error != null && !responseFuture.isDone()) {
                responseFuture.completeExceptionally(error);
            }
        });

        ObjectNode request = objectMapper.createObjectNode();
        request.put("action", action);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        request.put("echo", echo);

        try {
            String payload = objectMapper.writeValueAsString(request);
            return socket.sendText(payload, true)
                    .thenCompose(ignored -> responseFuture)
                    .thenApply(response -> {
                        if (response.path("retcode").asInt(-1) != 0) {
                            throw new IllegalStateException("OneBot " + action + " 失败：" + response);
                        }
                        return response.path("data");
                    });
        } catch (JsonProcessingException exception) {
            pendingApiCalls.remove(echo);
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<JsonNode> getMessage(long messageId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("message_id", messageId);
        return callAction("get_msg", params);
    }

    public CompletableFuture<JsonNode> getImage(String file) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("file", file);
        return callAction("get_image", params);
    }

    public CompletableFuture<JsonNode> getGroupMemberList(long groupId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("group_id", groupId);
        return callAction("get_group_member_list", params);
    }

    public CompletableFuture<Void> sendGroupMessage(long groupId, String text) {
        return sendMessage("send_group_msg", params -> params.put("group_id", groupId), text);
    }

    public CompletableFuture<Void> sendPrivateMessage(long userId, String text) {
        return sendMessage("send_private_msg", params -> params.put("user_id", userId), text);
    }

    private CompletableFuture<Void> sendMessage(
            String action,
            java.util.function.Consumer<ObjectNode> scope,
            String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("回复内容不能为空"));
        }
        WebSocket socket = webSocket;
        if (socket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("未连接到 NapCat"));
        }

        ObjectNode params = objectMapper.createObjectNode();
        scope.accept(params);
        params.put("message", text);
        params.put("auto_escape", true);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("action", action);
        request.set("params", params);
        request.put("echo", UUID.randomUUID().toString());

        try {
            String payload = objectMapper.writeValueAsString(request);
            return socket.sendText(payload, true)
                    .thenAccept(ignored -> {
                    })
                    .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @PreDestroy
    public void close() {
        shuttingDown = true;
        reconnectExecutor.shutdownNow();
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "application is stopping");
        }
    }

    private void handleInComingText(String text) {
        try {
            JsonNode payload = objectMapper.readTree(text);
            if (payload.has("post_type")) {
                eventPublisher.publishEvent(new OneBotEvent(payload));
                return;
            }
            if (payload.has("echo")) {
                String echo = payload.get("echo").asText();
                CompletableFuture<JsonNode> pending = pendingApiCalls.remove(echo);
                if (pending != null) {
                    pending.complete(payload);
                    return;
                }
            }
            if ("failed".equals(payload.path("status").asText())) {
                log.warn("OneBot 请求失败：{}", payload);
            }
        } catch (JsonProcessingException exception) {
            log.warn("OneBot 事件解析失败：{}", text, exception);
        }
    }

    private class OneBotListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            String completeText = null;
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    completeText = textBuffer.toString();
                    textBuffer.setLength(0);
                }
            }
            if (completeText != null) {
                handleInComingText(completeText);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.error("NapCat WebSocket 发生错误", error);
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            webSocket = null;
            log.warn("NapCat WebSocket 已断开：{}，{}", statusCode, reason);
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }
    }
}
