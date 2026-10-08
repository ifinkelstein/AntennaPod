package de.danoeh.antennapod.model.feed;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AdSegment {
    public static final String KIND_AD = "ad";
    public static final String KIND_HOST_READ = "host_read";
    public static final String KIND_PROMO = "promo";
    public static final String KIND_INTRO = "intro";
    public static final String KIND_OUTRO = "outro";

    private final long startMs;
    private final long endMs;
    private final String kind;
    private final float confidence;

    public AdSegment(long startMs, long endMs, String kind, float confidence) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.kind = kind;
        this.confidence = confidence;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public String getKind() {
        return kind;
    }

    public float getConfidence() {
        return confidence;
    }

    public static String toJson(List<AdSegment> segments) {
        JSONArray array = new JSONArray();
        for (AdSegment segment : segments) {
            JSONObject object = new JSONObject();
            try {
                object.put("start", segment.startMs);
                object.put("end", segment.endMs);
                object.put("kind", segment.kind);
                object.put("confidence", segment.confidence);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            array.put(object);
        }
        return array.toString();
    }

    public static List<AdSegment> fromJson(String json) {
        List<AdSegment> segments = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return segments;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                segments.add(new AdSegment(object.getLong("start"), object.getLong("end"),
                        object.optString("kind", KIND_AD), (float) object.optDouble("confidence", 1.0)));
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return segments;
    }

    @Override
    public String toString() {
        return "AdSegment [" + kind + " " + startMs + "-" + endMs + "]";
    }
}
