package de.danoeh.antennapod.net.download.service.episode.adscan;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class DeepInfraClientTest {

    @Test
    public void plainHttpEndpointIsRefusedBeforeSendingTheKey() throws Exception {
        AntennapodHttpClient.setCacheDirectory(RuntimeEnvironment.getApplication().getCacheDir());
        DeepInfraClient client = new DeepInfraClient("secret", "https://example.org/transcribe", "whisper",
                "http://example.org/chat", "model");
        try {
            client.chat("system", "user");
            fail("Expected the http endpoint to be refused");
        } catch (DeepInfraClient.ApiException e) {
            assertEquals(400, e.code);
            assertFalse(e.isRetryable());
            assertFalse(e.getMessage().contains("secret"));
            assertTrue(e.getMessage().contains("https"));
        }
    }
}
