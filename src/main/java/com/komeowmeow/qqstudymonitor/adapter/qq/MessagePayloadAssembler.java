package com.komeowmeow.qqstudymonitor.adapter.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.komeowmeow.qqstudymonitor.application.IncomingMessage;
import com.komeowmeow.qqstudymonitor.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MessagePayloadAssembler {
    private static final Logger log = LoggerFactory.getLogger(MessagePayloadAssembler.class);
    private static final Pattern CQ_IMAGE_PATTERN = Pattern.compile("\\[CQ:image,([^\\]]+)]");

    private final BotConfig botConfig;
    private final QqClient qqClient;
    private final OneBotImageResolver imageResolver;

    public MessagePayloadAssembler(
            BotConfig botConfig, QqClient qqClient, OneBotImageResolver imageResolver) {
        this.botConfig = botConfig;
        this.qqClient = qqClient;
        this.imageResolver = imageResolver;
    }

    public record AssembleResult(IncomingMessage message, ImageIssue imageIssue) {
        public enum ImageIssue {
            NONE,
            UNAVAILABLE,
            TOO_LARGE
        }
    }

    public CompletableFuture<AssembleResult> assembleAsync(JsonNode payload) {
        ParsedMessage parsed = parse(payload);
        if (parsed.text().isBlank() && parsed.imageData().isEmpty() && parsed.replyMessageId() == null) {
            return CompletableFuture.completedFuture(new AssembleResult(null, AssembleResult.ImageIssue.NONE));
        }

        CompletableFuture<List<JsonNode>> imageDataFuture = parsed.replyMessageId() == null
                ? CompletableFuture.completedFuture(parsed.imageData())
                : qqClient.getMessage(parsed.replyMessageId())
                .thenApply(data -> mergeImageData(parsed.imageData(), data.path("message")))
                .exceptionally(error -> {
                    log.warn("get_msg 失败，message_id={}", parsed.replyMessageId(), error);
                    return parsed.imageData();
                });

        return imageDataFuture.thenCompose(imageData -> imageResolver.resolveAllAsync(imageData)
                .thenApply(batch -> {
                    boolean hadImageData = !imageData.isEmpty();
                    AssembleResult.ImageIssue issue = AssembleResult.ImageIssue.NONE;
                    if (hadImageData && batch.images().isEmpty()) {
                        issue = batch.rejectedOversized()
                                ? AssembleResult.ImageIssue.TOO_LARGE
                                : AssembleResult.ImageIssue.UNAVAILABLE;
                    }
                    IncomingMessage message = new IncomingMessage(
                            parsed.text(), parsed.senderName(), batch.images(), parsed.mentionsBot());
                    if (message.isBlank()) {
                        return new AssembleResult(null, issue);
                    }
                    return new AssembleResult(message, issue);
                }));
    }

    private static List<JsonNode> mergeImageData(List<JsonNode> current, JsonNode messageNode) {
        List<JsonNode> merged = new ArrayList<>(current);
        collectImageData(messageNode, merged);
        return merged;
    }

    private ParsedMessage parse(JsonNode payload) {
        JsonNode message = payload.path("message");
        String senderName = payload.path("sender").path("card").asText("").trim();
        if (senderName.isBlank()) {
            senderName = payload.path("sender").path("nickname").asText("").trim();
        }
        if (message.isArray()) {
            boolean mentionsBot = false;
            StringBuilder text = new StringBuilder();
            List<JsonNode> imageData = new ArrayList<>();
            Long replyMessageId = null;

            for (JsonNode segment : message) {
                String type = segment.path("type").asText();
                if ("at".equals(type) && String.valueOf(botConfig.getSelfId())
                        .equals(segment.path("data").path("qq").asText())) {
                    mentionsBot = true;
                    continue;
                }
                if ("text".equals(type)) {
                    text.append(segment.path("data").path("text").asText());
                    continue;
                }
                if ("image".equals(type)) {
                    imageData.add(segment.path("data"));
                    continue;
                }
                if ("reply".equals(type)) {
                    replyMessageId = parseMessageId(segment.path("data"));
                }
            }
            return new ParsedMessage(mentionsBot, text.toString().trim(), imageData, replyMessageId, senderName);
        }

        String rawMessage = payload.path("raw_message").asText();
        String atCode = "[CQ:at,qq=" + botConfig.getSelfId() + "]";
        boolean mentionsBot = rawMessage.contains(atCode);
        String remainder = rawMessage.replace(atCode, "").trim();
        List<JsonNode> imageData = new ArrayList<>();
        String text = CQ_IMAGE_PATTERN.matcher(remainder).replaceAll("").trim();
        if (remainder.contains("[CQ:image")) {
            var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            String file = extractFirstCqParam(remainder, "file");
            String url = extractFirstCqParam(remainder, "url");
            if (file != null) {
                node.put("file", file);
            }
            if (url != null) {
                node.put("url", url);
            }
            if (!node.isEmpty()) {
                imageData.add(node);
            }
        }
        return new ParsedMessage(mentionsBot, text, imageData, null, senderName);
    }

    private static void collectImageData(JsonNode messageNode, List<JsonNode> target) {
        if (messageNode.isArray()) {
            for (JsonNode segment : messageNode) {
                if ("image".equals(segment.path("type").asText())) {
                    target.add(segment.path("data"));
                }
            }
        }
    }

    private static Long parseMessageId(JsonNode data) {
        JsonNode idNode = data.path("id");
        if (idNode.isMissingNode() || idNode.isNull()) {
            idNode = data.path("message_id");
        }
        if (idNode.isMissingNode() || idNode.isNull()) {
            return null;
        }
        if (idNode.isNumber()) {
            return idNode.asLong();
        }
        try {
            return Long.parseLong(idNode.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String extractFirstCqParam(String raw, String key) {
        Matcher matcher = CQ_IMAGE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String params = matcher.group(1);
        for (String part : params.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equals(part.substring(0, eq).trim())) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private record ParsedMessage(
            boolean mentionsBot, String text, List<JsonNode> imageData, Long replyMessageId, String senderName) {
    }
}
