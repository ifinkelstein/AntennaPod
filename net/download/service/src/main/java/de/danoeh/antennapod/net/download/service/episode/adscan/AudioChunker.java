package de.danoeh.antennapod.net.download.service.episode.adscan;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AudioChunker {
    public static final long MAX_UPLOAD_BYTES = 24L * 1024 * 1024;
    public static final long MAX_CHUNK_DURATION_US = 15L * 60 * 1000 * 1000;
    private static final int AAC_PROFILE_LC = 2;

    public static class Chunk {
        public final File file;
        public final long offsetMs;
        public final String mimeType;

        Chunk(File file, long offsetMs, String mimeType) {
            this.file = file;
            this.offsetMs = offsetMs;
            this.mimeType = mimeType;
        }
    }

    public static class UnsupportedFormatException extends IOException {
        public UnsupportedFormatException(String message) {
            super(message);
        }
    }

    public static boolean needsChunking(File file) {
        return file.length() > MAX_UPLOAD_BYTES;
    }

    public static List<Chunk> split(File source, File workDir) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        List<Chunk> chunks = new ArrayList<>();
        OutputStream out = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = trackFormat;
                    break;
                }
            }
            if (track < 0) {
                throw new UnsupportedFormatException("No audio track");
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            boolean isMp3 = MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime);
            boolean isAac = MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime);
            if (!isMp3 && !isAac) {
                throw new UnsupportedFormatException("Unsupported codec for chunking: " + mime);
            }
            extractor.selectTrack(track);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String extension = isMp3 ? ".mp3" : ".aac";
            String chunkMime = isMp3 ? "audio/mpeg" : "audio/aac";

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            int chunkIndex = 0;
            long chunkStartUs = 0;
            long chunkBytes = 0;
            File chunkFile = null;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                long timeUs = extractor.getSampleTime();
                int frameBytes = size + (isAac ? 7 : 0);
                if (out == null || timeUs - chunkStartUs >= MAX_CHUNK_DURATION_US
                        || chunkBytes + frameBytes > MAX_UPLOAD_BYTES) {
                    if (out != null) {
                        out.close();
                        chunks.add(new Chunk(chunkFile, chunkStartUs / 1000, chunkMime));
                    }
                    chunkFile = new File(workDir, "adscan-chunk-" + chunkIndex++ + extension);
                    out = new BufferedOutputStream(new FileOutputStream(chunkFile));
                    chunkStartUs = timeUs;
                    chunkBytes = 0;
                }
                if (isAac) {
                    out.write(adtsHeader(size, sampleRate, channels));
                }
                out.write(buffer.array(), 0, size);
                chunkBytes += frameBytes;
                extractor.advance();
            }
            if (out != null) {
                out.close();
                out = null;
                chunks.add(new Chunk(chunkFile, chunkStartUs / 1000, chunkMime));
            }
        } finally {
            if (out != null) {
                out.close();
            }
            extractor.release();
        }
        return chunks;
    }

    private static byte[] adtsHeader(int frameLength, int sampleRate, int channels) {
        int freqIndex = sampleRateIndex(sampleRate);
        int fullLength = frameLength + 7;
        byte[] header = new byte[7];
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xF9;
        header[2] = (byte) (((AAC_PROFILE_LC - 1) << 6) + (freqIndex << 2) + (channels >> 2));
        header[3] = (byte) (((channels & 3) << 6) + (fullLength >> 11));
        header[4] = (byte) ((fullLength & 0x7FF) >> 3);
        header[5] = (byte) (((fullLength & 7) << 5) + 0x1F);
        header[6] = (byte) 0xFC;
        return header;
    }

    private static int sampleRateIndex(int sampleRate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) {
                return i;
            }
        }
        return 4;
    }
}
