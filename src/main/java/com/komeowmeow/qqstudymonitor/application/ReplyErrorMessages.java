package com.komeowmeow.qqstudymonitor.application;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

public final class ReplyErrorMessages {
    private static final String GENERIC = "刚才出错了，过一会儿再试一次。";
    private static final String AI_TIMEOUT = "思考超时了，稍后再发。";
    private static final String NAPCAT_OFFLINE = "与 QQ 的连接断了，请管理员检查 NapCat。";

    private ReplyErrorMessages() {
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String toUserMessage(Throwable error) {
        Throwable root = unwrap(error);
        if (root instanceof AiReplyException ai) {
            return ai.getUserMessage();
        }
        if (root instanceof TimeoutException) {
            return AI_TIMEOUT;
        }
        if (root instanceof IllegalStateException ise) {
            String message = ise.getMessage();
            if (message != null && message.contains("未连接")) {
                return NAPCAT_OFFLINE;
            }
        }
        return GENERIC;
    }
}
