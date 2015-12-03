package org.cyanogenmod.bugreport;

import android.os.FileUtils;
import android.os.SystemProperties;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import org.cyanogenmod.bugreport.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class AttachmentUploadTest extends AndroidTestCase {

    private static final String TAG = AttachmentUploadTest.class.getSimpleName();

    private JSONObject createDummyJSONInput() throws JSONException {
        JSONObject fields = new JSONObject();
        JSONObject project = new JSONObject();
        JSONObject issuetype = new JSONObject();
        JSONObject inputJSON = new JSONObject();
        JSONArray labels = new JSONArray();

        project.put("id", getContext().getString(R.string.config_project_name));
        issuetype.put("id", getContext().getString(R.string.config_issue_type));
        fields.put("project", project);
        fields.put("summary", "test summary");
        fields.put("description", "test description");
        fields.put("issuetype", issuetype);
        fields.put(CMLogService.BUILD_ID_FIELD,
                SystemProperties.get(CMLogService.RO_CM_VERSION, ""));
        fields.put(CMLogService.KERNELVER_FIELD, "fake kernel");

        labels.put("user");
        fields.put("labels", labels);
        inputJSON.put("fields", fields);

        return inputJSON;
    }

    @SmallTest
    public void testCreateIssue() throws JSONException, IOException {
        final String issueId = UploadHelper.uploadAndGetId(getContext(), createDummyJSONInput());
        System.out.println("issue id: " + issueId);
        assertNotNull(issueId);
    }

    @MediumTest
    public void testAttachFile() throws IOException, JSONException {
        final String issueId = UploadHelper.uploadAndGetId(getContext(), createDummyJSONInput());
        System.out.println("issue id: " + issueId);
        assertNotNull("issue ID is null", issueId);

        // copy file over
        final InputStream open = getTestContext().getResources().getAssets().open("test.zip");
        final File destination = new File(getContext().getCacheDir(), "test1.zip");
        final File zipTestFile = FileUtils.copyToFile(open, destination) ? destination : null;
        assertNotNull("null zip file while copying asset over", zipTestFile);

        assertNotSame(0, zipTestFile.length());

        Log.d(TAG, "mZipTestFile:" + zipTestFile + ", size: " + zipTestFile.length());

        assertTrue(UploadHelper.attachFile(getContext(), zipTestFile, issueId));
    }
}
