package com.komeowmeow.qqstudymonitor.adapter.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.komeowmeow.qqstudymonitor.application.ImageAttachment;
import com.komeowmeow.qqstudymonitor.application.ImageResolveBatch;
import com.komeowmeow.qqstudymonitor.config.BotLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Component
public class OneBotImageResolver {
    private static final Logger log = LoggerFactory.getLogger(OneBotImageResolver.class);
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    private final QqClient qqClient;
    private final BotLimitsProperties limits;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OneBotImageResolver(QqClient qqClient, BotLimitsProperties limits) {
        this.qqClient = qqClient;
        this.limits = limits;
    }

    public CompletableFuture<ImageResolveBatch> resolveAllAsync(List<JsonNode> imageDataList) {
        if (imageDataList.isEmpty()) {
            return CompletableFuture.completedFuture(new ImageResolveBatch(List.of(), false));
        }
        List<CompletableFuture<ResolveOutcome>> tasks = imageDataList.stream()
                .limit(3)
                .map(data -> CompletableFuture.supplyAsync(() -> resolveOutcome(data)))
                .toList();
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<ImageAttachment> candidates = new ArrayList<>();
                    boolean rejectedOversized = false;
                    for (CompletableFuture<ResolveOutcome> task : tasks) {
                        ResolveOutcome outcome = task.join();
                        if (outcome.rejectedOversized()) {
                            rejectedOversized = true;
                        }
                        outcome.image().ifPresent(candidates::add);
                    }
                    return applyTotalByteBudget(candidates, rejectedOversized);
                });
    }

    private ImageResolveBatch applyTotalByteBudget(List<ImageAttachment> candidates, boolean alreadyRejectedOversized) {
        List<ImageAttachment> accepted = new ArrayList<>();
        boolean rejectedOversized = alreadyRejectedOversized;
        long totalBytes = 0;
        long maxTotal = limits.getImageMaxTotalBytes();

        for (ImageAttachment image : candidates) {
            long bytes = image.estimatedDecodedBytes();
            if (exceedsPerImage(bytes)) {
                rejectedOversized = true;
                continue;
            }
            if (maxTotal > 0 && totalBytes + bytes > maxTotal) {
                rejectedOversized = true;
                continue;
            }
            accepted.add(image);
            totalBytes += bytes;
            if (accepted.size() >= 3) {
                break;
            }
        }
        return new ImageResolveBatch(accepted, rejectedOversized);
    }

    private ResolveOutcome resolveOutcome(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return ResolveOutcome.empty();
        }

        String url = textValue(data, "url");
        String file = textValue(data, "file");

        if (file != null && file.startsWith("base64://")) {
            return attachIfWithinLimit(guessMime(file, url), file.substring("base64://".length()));
        }

        if (url != null && isHttp(url)) {
            DownloadedImage downloaded = downloadUrl(url);
            if (downloaded.image().isPresent()) {
                return new ResolveOutcome(downloaded.image(), downloaded.rejectedOversized());
            }
        }
        if (file != null && isHttp(file)) {
            DownloadedImage downloaded = downloadUrl(file);
            if (downloaded.image().isPresent()) {
                return new ResolveOutcome(downloaded.image(), downloaded.rejectedOversized());
            }
        }

        String fileKey = file != null ? file : url;
        if (fileKey != null && !fileKey.isBlank()) {
            try {
                JsonNode imagePayload = qqClient.getImage(fileKey).join();
                String base64 = textValue(imagePayload, "base64");
                if (base64 == null) {
                    base64 = textValue(imagePayload, "file");
                }
                if (base64 != null && !base64.isBlank()) {
                    if (base64.startsWith("base64://")) {
                        base64 = base64.substring("base64://".length());
                    }
                    return attachIfWithinLimit(guessMime(file, url), base64);
                }
            } catch (Exception error) {
                log.warn("get_image 失败，file={}", fileKey, error);
            }
        }

        if (url != null && !url.isBlank()) {
            DownloadedImage downloaded = downloadUrl(url);
            return new ResolveOutcome(downloaded.image(), downloaded.rejectedOversized());
        }
        return ResolveOutcome.empty();
    }

    private ResolveOutcome attachIfWithinLimit(String mime, String base64) {
        ImageAttachment attachment = new ImageAttachment(mime, base64);
        if (exceedsPerImage(attachment.estimatedDecodedBytes())) {
            return new ResolveOutcome(Optional.empty(), true);
        }
        return new ResolveOutcome(Optional.of(attachment), false);
    }

    private record ResolveOutcome(Optional<ImageAttachment> image, boolean rejectedOversized) {
        static ResolveOutcome empty() {
            return new ResolveOutcome(Optional.empty(), false);
        }
    }

    private record DownloadedImage(Optional<ImageAttachment> image, boolean rejectedOversized) {
        static DownloadedImage empty() {
            return new DownloadedImage(Optional.empty(), false);
        }
    }

    private boolean exceedsPerImage(long bytes) {
        long max = limits.getImageMaxBytesPerImage();
        return max > 0 && bytes > max;
    }

    private DownloadedImage downloadUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", "https://qun.qq.com/")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return DownloadedImage.empty();
            }
            byte[] body = response.body();
            if (body.length == 0) {
                return DownloadedImage.empty();
            }
            if (exceedsPerImage(body.length)) {
                return new DownloadedImage(Optional.empty(), true);
            }
            String mime = response.headers().firstValue("Content-Type")
                    .map(type -> type.split(";")[0].trim())
                    .filter(type -> !type.isBlank())
                    .orElse(guessMimeFromUrl(url));
            String base64 = Base64.getEncoder().encodeToString(body);
            return new DownloadedImage(Optional.of(new ImageAttachment(mime, base64)), false);
        } catch (Exception error) {
            log.warn("下载图片失败：{}", url, error);
            return DownloadedImage.empty();
        }
    }

    private static boolean isHttp(String value) {
        return HTTP_URL_PATTERN.matcher(value.trim()).find();
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String guessMime(String file, String url) {
        String hint = file != null ? file : url;
        return guessMimeFromUrl(hint == null ? "" : hint);
    }

    private static String guessMimeFromUrl(String hint) {
        String lower = hint.toLowerCase();
        if (lower.contains(".png")) {
            return "image/png";
        }
        if (lower.contains(".gif")) {
            return "image/gif";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
