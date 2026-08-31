package com.vokie.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public final class AppUpdateInfoTest {
    @Test
    public void parsesValidManifestAndBuildsVersionedFileName() throws Exception {
        AppUpdateInfo update = AppUpdateInfo.fromJson("{" +
                "\"versionCode\":4," +
                "\"versionName\":\"0.3.1\"," +
                "\"downloadUrl\":\"https://xiguasay.echooai.com/vokie/android/" +
                "vokiephone-v0.3.1.apk\"," +
                "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
                "\"forceUpdate\":false," +
                "\"releaseNotes\":\"增加升级检查\"}");

        assertEquals(4, update.versionCode);
        assertEquals("0.3.1", update.versionName);
        assertEquals("vokiephone-v0.3.1.apk", update.apkFileName());
        assertTrue(update.isNewerThan(3));
        assertFalse(update.isNewerThan(4));
    }

    @Test
    public void rejectsInsecureDownloadUrl() {
        assertThrows(JSONException.class, () -> AppUpdateInfo.fromJson("{" +
                "\"versionCode\":4," +
                "\"versionName\":\"0.3.1\"," +
                "\"downloadUrl\":\"http://example.com/vokie.apk\"," +
                "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"));
    }
}
