package de.danoeh.antennapod.net.download.service.episode.adscan;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AdDetector {
    static final long WINDOW_MS = 20L * 60 * 1000;
    static final long OVERLAP_MS = 5L * 60 * 1000;
    static final long MIN_SEGMENT_MS = 8000;
    static final long MERGE_GAP_MS = 3000;
    static final float MIN_CONFIDENCE = 0.6f;
    static final List<String> KNOWN_KINDS = Arrays.asList(AdSegment.KIND_AD, AdSegment.KIND_HOST_READ,
            AdSegment.KIND_PROMO, AdSegment.KIND_INTRO, AdSegment.KIND_OUTRO);

    static final String SYSTEM_PROMPT = "You find advertisements, intros and outros in podcast transcripts. "
            + "Each transcript line starts with a timestamp in seconds. "
            + "Return only JSON of the form {\"segments\":[{\"start\":<seconds>,\"end\":<seconds>,"
            + "\"kind\":\"ad\"|\"host_read\"|\"promo\"|\"intro\"|\"outro\",\"confidence\":<0-1>}]}. "
            + "\"ad\" is an inserted third-party commercial, usually with a different voice, music, "
            + "a product, a discount code or a URL. "
            + "\"host_read\" is a sponsor message read by the host, typically introduced with phrases like "
            + "\"this episode is brought to you by\", \"our sponsor\", \"use code\", "
            + "or \"support for this show comes from\". "
            + "\"promo\" is a promotion for another show from the same network. "
            + "\"intro\" is the recurring opening at the very start that is the same every episode, such as a theme, "
            + "a standard welcome or a disclaimer, ending where the episode-specific content begins. "
            + "\"outro\" is the recurring closing at the very end after the episode-specific content, such as credits, "
            + "a standard sign-off, calls to rate and subscribe, or a closing theme. "
            + "Only mark intro and outro when they are clearly generic boilerplate; never mark a greeting that "
            + "introduces the episode's topic or guest. "
            + "Use the timestamp of the first line of the ad as start and the timestamp of the first line after "
            + "the ad as end. Include the whole ad break when several ads are back to back. "
            + "Do not include the show's own content, interviews, or mentions of products in normal conversation. "
            + "If there are no ads return {\"segments\":[]}.";

    private final DeepInfraClient client;

    public AdDetector(DeepInfraClient client) {
        this.client = client;
    }

    public List<AdSegment> detect(List<TranscriptSegment> transcript) throws IOException {
        if (transcript.isEmpty()) {
            return Collections.emptyList();
        }
        long totalMs = transcript.get(transcript.size() - 1).getEndTime();
        List<AdSegment> raw = new ArrayList<>();
        long windowStart = 0;
        while (windowStart < totalMs || windowStart == 0) {
            long windowEnd = windowStart + WINDOW_MS;
            String text = render(transcript, windowStart, windowEnd);
            if (!text.isEmpty()) {
                raw.addAll(parse(client.chat(SYSTEM_PROMPT, text)));
            }
            if (windowEnd >= totalMs) {
                break;
            }
            windowStart = windowEnd - OVERLAP_MS;
        }
        return postProcess(raw, transcript);
    }

    static String render(List<TranscriptSegment> transcript, long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment segment : transcript) {
            if (segment.getStartTime() < fromMs || segment.getStartTime() >= toMs) {
                continue;
            }
            sb.append(String.format(Locale.US, "[%.1f] ", segment.getStartTime() / 1000.0));
            sb.append(segment.getWords().replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    static List<AdSegment> parse(String content) {
        List<AdSegment> result = new ArrayList<>();
        int first = content.indexOf('{');
        int last = content.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return result;
        }
        try {
            JSONArray segments = new JSONObject(content.substring(first, last + 1)).optJSONArray("segments");
            if (segments == null) {
                return result;
            }
            for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                long start = Math.round(segment.getDouble("start") * 1000);
                long end = Math.round(segment.getDouble("end") * 1000);
                if (end <= start) {
                    continue;
                }
                String kind = segment.optString("kind", AdSegment.KIND_AD);
                if (!KNOWN_KINDS.contains(kind)) {
                    kind = AdSegment.KIND_AD;
                }
                float confidence = (float) segment.optDouble("confidence", 1.0);
                result.add(new AdSegment(start, end, kind, confidence));
            }
        } catch (JSONException expected) {
        }
        return result;
    }

    static List<AdSegment> postProcess(List<AdSegment> raw, List<TranscriptSegment> transcript) {
        List<AdSegment> filtered = new ArrayList<>();
        for (AdSegment segment : raw) {
            if (segment.getConfidence() >= MIN_CONFIDENCE) {
                filtered.add(segment);
            }
        }
        Collections.sort(filtered, (a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
        List<AdSegment> merged = new ArrayList<>();
        for (AdSegment candidate : filtered) {
            AdSegment segment = candidate;
            if (!merged.isEmpty()) {
                AdSegment previous = merged.get(merged.size() - 1);
                boolean sameKind = previous.getKind().equals(segment.getKind());
                if (sameKind && segment.getStartMs() <= previous.getEndMs() + MERGE_GAP_MS) {
                    merged.set(merged.size() - 1, new AdSegment(previous.getStartMs(),
                            Math.max(previous.getEndMs(), segment.getEndMs()),
                            previous.getKind(), Math.max(previous.getConfidence(), segment.getConfidence())));
                    continue;
                }
                if (segment.getEndMs() <= previous.getEndMs()) {
                    continue;
                }
                if (segment.getStartMs() < previous.getEndMs()) {
                    segment = new AdSegment(previous.getEndMs(), segment.getEndMs(),
                            segment.getKind(), segment.getConfidence());
                }
            }
            merged.add(segment);
        }
        List<AdSegment> result = new ArrayList<>();
        for (AdSegment segment : merged) {
            long start = snap(segment.getStartMs(), transcript);
            long end = snap(segment.getEndMs(), transcript);
            if (end - start >= MIN_SEGMENT_MS) {
                result.add(new AdSegment(start, end, segment.getKind(), segment.getConfidence()));
            }
        }
        return result;
    }

    private static long snap(long timeMs, List<TranscriptSegment> transcript) {
        long best = timeMs;
        long bestDistance = Long.MAX_VALUE;
        for (TranscriptSegment segment : transcript) {
            long distance = Math.abs(segment.getStartTime() - timeMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment.getStartTime();
            }
            if (segment.getStartTime() > timeMs + bestDistance) {
                break;
            }
        }
        return bestDistance <= 2000 ? best : timeMs;
    }

    public static String toPodcastIndexJson(List<TranscriptSegment> transcript) {
        JSONObject root = new JSONObject();
        JSONArray segments = new JSONArray();
        try {
            for (TranscriptSegment segment : transcript) {
                JSONObject object = new JSONObject();
                object.put("startTime", segment.getStartTime() / 1000.0);
                object.put("endTime", segment.getEndTime() / 1000.0);
                object.put("speaker", "");
                object.put("body", segment.getWords());
                segments.put(object);
            }
            root.put("version", "1.0.0");
            root.put("segments", segments);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return root.toString();
    }
}
