package de.danoeh.antennapod.ui.transcript;

import android.util.Log;
import org.apache.commons.io.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import de.danoeh.antennapod.model.feed.AdScan;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptType;
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
import de.danoeh.antennapod.storage.database.DBReader;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class TranscriptUtils {
    private static final String TAG = "Transcript";

    public static String loadTranscriptFromUrl(String url, boolean forceRefresh) throws InterruptedIOException {
        if (forceRefresh) {
            return loadTranscriptFromUrl(url, CacheControl.FORCE_NETWORK);
        }
        String str = loadTranscriptFromUrl(url, CacheControl.FORCE_CACHE);
        if (str == null || str.length() <= 1) {
            // Some publishers use one dummy transcript before actual transcript are available
            return loadTranscriptFromUrl(url, CacheControl.FORCE_NETWORK);
        }
        return str;
    }

    private static String loadTranscriptFromUrl(String url, CacheControl cacheControl) throws InterruptedIOException {
        StringBuilder str = new StringBuilder();
        Response response = null;

        try {
            Log.d(TAG, "Downloading transcript URL " + url);
            Request request = new Request.Builder().url(url).cacheControl(cacheControl).build();
            response = AntennapodHttpClient.getHttpClient().newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                Log.d(TAG, "Done Downloading transcript URL " + url);
                str.append(response.body().string());
            } else {
                Log.d(TAG, "Error Downloading transcript URL " + url + ": " + response.message());
            }
        } catch (InterruptedIOException e) {
            Log.d(TAG, "InterruptedIOException while downloading transcript URL " + url);
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return str.toString();
    }

    public static Transcript loadTranscript(FeedMedia media, Boolean forceRefresh) throws InterruptedIOException {
        String transcriptType = media.getItem().getTranscriptType();
        String transcriptUrl = media.getItem().getTranscriptUrl();

        if (!forceRefresh && media.getItem().getTranscript() != null) {
            return media.getTranscript();
        }

        boolean generated = isGeneratedTranscript(media);
        if ((!forceRefresh || transcriptUrl == null || generated) && media.getTranscriptFileUrl() != null) {
            File transcriptFile = new File(media.getTranscriptFileUrl());
            try {
                if (transcriptFile.exists()) {
                    String t = FileUtils.readFileToString(transcriptFile, (String) null);
                    if (StringUtils.isNotEmpty(t)) {
                        String type = generated || transcriptType == null
                                ? TranscriptType.JSON.canonicalMime : transcriptType;
                        media.setTranscript(TranscriptParser.parse(t, type));
                        return media.getTranscript();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (transcriptUrl == null) {
            return null;
        }
        String t = TranscriptUtils.loadTranscriptFromUrl(transcriptUrl, forceRefresh);
        if (StringUtils.isNotEmpty(t)) {
            return TranscriptParser.parse(t, transcriptType);
        }
        return null;
    }

    public static boolean isGeneratedTranscript(FeedMedia media) {
        if (media.getItem() != null && media.getItem().getTranscriptUrl() != null) {
            return false;
        }
        AdScan scan = DBReader.loadAdScan(media.getId());
        return scan != null && scan.getState() == AdScan.STATE_DONE && scan.isValidFor(media);
    }

    public static void storeTranscript(FeedMedia media, String transcript) {
        File transcriptFile = new File(media.getTranscriptFileUrl());
        FileOutputStream ostream = null;
        try {
            if (transcriptFile.exists() && !transcriptFile.delete()) {
                Log.e(TAG, "Failed to delete existing transcript file " + transcriptFile.getAbsolutePath());
            }
            if (transcriptFile.createNewFile()) {
                ostream = new FileOutputStream(transcriptFile);
                ostream.write(transcript.getBytes(Charset.forName("UTF-8")));
                ostream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(ostream);
        }
    }
}
