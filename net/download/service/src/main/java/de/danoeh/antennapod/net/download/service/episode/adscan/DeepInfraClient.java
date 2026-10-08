package de.danoeh.antennapod.net.download.service.episode.adscan;

import androidx.annotation.NonNull;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DeepInfraClient {
    private final String apiKey;
    private final String transcriptionUrl;
    private final String transcriptionModel;
    private final String chatUrl;
    private final String chatModel;
    private final OkHttpClient client;

    public DeepInfraClient(@NonNull String apiKey, @NonNull String transcriptionUrl,
                           @NonNull String transcriptionModel, @NonNull String chatUrl, @NonNull String chatModel) {
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        this.transcriptionModel = transcriptionModel;
        this.chatUrl = chatUrl;
        this.chatModel = chatModel;
        this.client = AntennapodHttpClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .build();
    }

    public static class ApiException extends IOException {
        public final int code;

        public ApiException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
        }

        public boolean isAuthError() {
            return code == 401 || code == 403;
        }

        public boolean isRetryable() {
            return code == 429 || code >= 500;
        }
    }

    public List<TranscriptSegment> transcribe(File audio, String mimeType, long offsetMs) throws IOException {
        RequestBody fileBody = RequestBody.create(audio, MediaType.parse(mimeType));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.getName(), fileBody)
                .addFormDataPart("model", transcriptionModel)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build();
        Request request = new Request.Builder()
                .url(parseUrl(transcriptionUrl))
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        String json = execute(request);
        try {
            JSONArray segments = new JSONObject(json).getJSONArray("segments");
            List<TranscriptSegment> result = new ArrayList<>();
            for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                String text = segment.optString("text", "").trim();
                if (text.isEmpty()) {
                    continue;
                }
                long start = offsetMs + Math.round(segment.getDouble("start") * 1000);
                long end = offsetMs + Math.round(segment.getDouble("end") * 1000);
                result.add(new TranscriptSegment(start, end, text, ""));
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("Unexpected transcription response", e);
        }
    }

    public String chat(String systemPrompt, String userPrompt) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("model", chatModel);
            payload.put("temperature", 0);
            payload.put("max_tokens", 2048);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            payload.put("messages", messages);
            payload.put("response_format", new JSONObject().put("type", "json_object"));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Request request = new Request.Builder()
                .url(parseUrl(chatUrl))
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build();
        String json = execute(request);
        try {
            return new JSONObject(json).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
        } catch (JSONException e) {
            throw new IOException("Unexpected chat response", e);
        }
    }

    private static HttpUrl parseUrl(String url) throws ApiException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new ApiException(400, "Invalid endpoint URL: " + url);
        }
        return parsed;
    }

    private String execute(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(), body.length() > 500 ? body.substring(0, 500) : body);
            }
            return body;
        }
    }
}
