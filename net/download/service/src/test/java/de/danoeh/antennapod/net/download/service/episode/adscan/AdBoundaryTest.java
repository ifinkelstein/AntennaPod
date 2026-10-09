package de.danoeh.antennapod.net.download.service.episode.adscan;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class AdBoundaryTest {

    private static JSONObject word(String text, double start, double end) throws Exception {
        return new JSONObject().put("word", " " + text).put("start", start).put("end", end);
    }

    @Test
    public void linesBreakAtSentencesAndPauses() throws Exception {
        JSONArray words = new JSONArray()
                .put(word("You'll", 41.83, 42.65))
                .put(word("need", 42.65, 42.83))
                .put(word("a", 42.83, 42.97))
                .put(word("tweak,", 43.19, 43.71))
                .put(word("won't", 43.71, 43.87))
                .put(word("you?", 43.87, 44.31))
                .put(word("Absolutely", 61.49, 61.51))
                .put(word("free", 61.51, 61.87))
                .put(word("casino", 62.41, 62.77));
        List<TranscriptSegment> lines = DeepInfraClient.linesFromWords(words, 2_750_000);
        assertEquals(2, lines.size());
        assertEquals("You'll need a tweak, won't you?", lines.get(0).getWords());
        assertEquals(2_791_830, lines.get(0).getStartTime());
        assertEquals(2_794_310, lines.get(0).getEndTime());
        assertEquals("Absolutely free casino", lines.get(1).getWords());
        assertEquals(2_811_490, lines.get(1).getStartTime());
    }

    @Test
    public void wordPiecesWithoutLeadingSpaceStayAttached() throws Exception {
        JSONArray words = new JSONArray()
                .put(new JSONObject().put("word", " Visit").put("start", 0.0).put("end", 0.3))
                .put(new JSONObject().put("word", " Monarch").put("start", 0.3).put("end", 0.7))
                .put(new JSONObject().put("word", ".com").put("start", 0.7).put("end", 0.9))
                .put(new JSONObject().put("word", " for").put("start", 0.9).put("end", 1.0))
                .put(new JSONObject().put("word", " 50").put("start", 1.0).put("end", 1.2))
                .put(new JSONObject().put("word", "%").put("start", 1.2).put("end", 1.3))
                .put(new JSONObject().put("word", " off.").put("start", 1.3).put("end", 1.5));
        List<TranscriptSegment> lines = DeepInfraClient.linesFromWords(words, 0);
        assertEquals("Visit Monarch.com for 50% off.", lines.get(0).getWords());
    }

    @Test
    public void longLinesAreSplit() throws Exception {
        JSONArray words = new JSONArray();
        for (int i = 0; i < 30; i++) {
            words.put(word("word", i * 0.9, i * 0.9 + 0.8));
        }
        List<TranscriptSegment> lines = DeepInfraClient.linesFromWords(words, 0);
        for (TranscriptSegment line : lines) {
            assertEquals(true, line.getEndTime() - line.getStartTime() <= DeepInfraClient.MAX_LINE_MS);
        }
    }

    @Test
    public void wordlessJingleBeforeAdIsIncluded() {
        List<TranscriptSegment> transcript = Arrays.asList(
                new TranscriptSegment(2_791_830, 2_794_310, "You'll need a tweak, won't you?", ""),
                new TranscriptSegment(2_811_490, 2_815_000, "Absolutely free casino", ""));
        assertEquals(2_794_810, AdDetector.includeLeadIn(2_811_490, transcript));
    }

    @Test
    public void adRightAfterSpeechIsUnchanged() {
        List<TranscriptSegment> transcript = Arrays.asList(
                new TranscriptSegment(0, 5_000, "Back to the show.", ""),
                new TranscriptSegment(5_800, 9_000, "This episode is brought to you by", ""));
        assertEquals(5_800, AdDetector.includeLeadIn(5_800, transcript));
    }

    @Test
    public void adAtStartWithJingleStartsAtZero() {
        List<TranscriptSegment> transcript = Arrays.asList(
                new TranscriptSegment(4_000, 8_000, "Crown Coins Casino!", ""));
        assertEquals(0, AdDetector.includeLeadIn(4_000, transcript));
    }
}
