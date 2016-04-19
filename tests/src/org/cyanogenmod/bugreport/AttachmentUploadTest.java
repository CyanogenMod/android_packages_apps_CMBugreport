package org.cyanogenmod.bugreport;

import android.content.Context;
import android.os.FileUtils;
import android.os.PowerManager;
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

        final String projectName = getContext().getString(R.string.config_project_name);
        final String issueType = getContext().getString(R.string.config_issue_type);

        Log.d(TAG, "projectName: " + projectName + ", issueType: " + issueType);

        project.put("id", projectName);
        issuetype.put("id", issueType);
        fields.put("project", project);
        fields.put("summary", "test summary");
        fields.put("description", "test description");
        fields.put("issuetype", issuetype);

        final String kernelVer = "fake_kernel";
        final String vers = SystemProperties.get(CMLogService.RO_CM_VERSION, "");

        if (!mContext.getString(R.string.config_kernel_field).isEmpty()
                && !mContext.getString(R.string.config_buildid_field).isEmpty()) {
            fields.put(mContext.getString(R.string.config_buildid_field), vers);
            fields.put(mContext.getString(R.string.config_kernel_field), kernelVer);
        } else {
            fields.put(CMLogService.BUILD_ID_FIELD, vers);
            fields.put(CMLogService.KERNELVER_FIELD, kernelVer);
        }

        labels.put("user");
        fields.put("labels", labels);
        inputJSON.put("fields", fields);

        return inputJSON;
    }

    @SmallTest
    public void test1CreateIssue() throws JSONException, IOException {
        final String issueId = UploadHelper.uploadAndGetId(getContext(), createDummyJSONInput());
        System.out.println("issue id: " + issueId);
        assertNotNull(issueId);
    }

    @MediumTest
    public void test2AttachFile() throws IOException, JSONException {
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

    /**
     * not including the "unprocessed_text.txt" file in assets/ due to size, so it's private
     * so we don't run false-positive tests
     */
    private void test3ProcessAndUploadFile() throws JSONException, IOException {
        final PowerManager power = (PowerManager) getContext()
                .getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "test");

        try {
            wakeLock.acquire();

            final String issueId = UploadHelper.uploadAndGetId(getContext(),
                    createDummyJSONInput());
            System.out.println("issue id: " + issueId);
            assertNotNull("issue ID is null", issueId);


            // copy file over
            final InputStream open = getTestContext().getResources().getAssets().open(
                    "unprocessed_text.txt");
            final File destination = new File(getContext().getCacheDir(), "unprocessed_text.txt");
            final File processedDestionation = new File(getContext().getCacheDir(),
                    "processed_text.txt");

            assertTrue("error while copying asset over", FileUtils.copyToFile(open, destination));

            System.out.println("Scub start.");
            ScrubberUtils.scrubFile(getContext(), destination, processedDestionation);
            System.out.println("Scub end");

            final File zipTestFile = UploadHelper.zipFiles(getContext(), processedDestionation);
            assertNotSame(0, zipTestFile.length());

            Log.d(TAG, "mZipTestFile:" + zipTestFile + ", size: " + zipTestFile.length());

            assertTrue(UploadHelper.attachFile(getContext(), zipTestFile, issueId));
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
