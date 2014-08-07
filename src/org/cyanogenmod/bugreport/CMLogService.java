/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.bugreport;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

public class CMLogService extends IntentService {
    private final static String TAG = "CMLogService";

    private final static String PROJECT_NAME = "11400"; // 11102 = WIKI 11400 = bugdump
    private final static String ISSUE_TYPE = "1"; // 4 = improvement   1 = bug?
    private final static String AUTH = "QnVnQ29sbGVjdG9yOldlTE9WRWJ1Z3Mh"; // <--- BugCollector
    private final static String API_URL = "https://jira.cyanogenmod.org/rest/api/2/issue/";
    private final static String SCRUBBED_BUG_REPORT_PREFIX = "scrubbed_";

    public CMLogService() {
        super("CMLogService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        ArrayList<Uri> attachments = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        Uri reportUri = null;

        for (Uri uri : attachments) {
            if (uri.toString().contains("txt")) {
                reportUri = uri;
                break;
            }
        }
        if (reportUri == null) {
            return;
        }

        String summary = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String description = intent.getStringExtra(Intent.EXTRA_TEXT);

        JSONObject fields = new JSONObject();
        JSONObject project = new JSONObject();
        JSONObject issuetype = new JSONObject();
        JSONObject inputJSON = new JSONObject();

        try {
            project.put("id", PROJECT_NAME);
            issuetype.put("id", ISSUE_TYPE);
            fields.put("project", project);
            fields.put("summary", summary);
            fields.put("description", description);
            fields.put("issuetype", issuetype);
            inputJSON.put("fields", fields);
        } catch (JSONException e) {
            Log.e(TAG, "Input JSON could not be compiled", e);
            notifyUploadFailed(R.string.error_problem_creating);
        }

        notifyOfUpload();
        new CallAPITask(reportUri).execute(inputJSON);
    }

    private void notify(CharSequence message, int iconResId, boolean withProgress) {
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(iconResId)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(message);
        if (withProgress) {
            builder.setProgress(0, 0, true);
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(R.string.notif_title, builder.build());
    }

    private void notifyOfUpload() {
        notify(getString(R.string.notif_uploading), R.drawable.ic_tab_upload, true);
    }

    private void notifyUploadFinished(String issueNumber) {
        notify(getString(R.string.notif_thanks), R.drawable.ic_launcher, false);
    }

    private void notifyUploadFailed(int reasonResId) {
        String reason = getString(reasonResId);
        notify(getString(R.string.error_upload_failed, reason), R.drawable.ic_launcher, false);
    }

    private class CallAPITask extends AsyncTask<JSONObject, Void, String> {
        private Uri mReportUri;
        private PowerManager.WakeLock mWakeLock;

        public CallAPITask(Uri reportUri) {
            mReportUri = reportUri;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.acquire();
        }

        @Override
        protected String doInBackground(JSONObject...params) {
            String jiraBugId;

            try {
                jiraBugId = uploadAndGetId(params[0]);
            } catch (IOException e) {
                Log.e(TAG, "Could not upload bug report", e);
                notifyUploadFailed(R.string.error_connection_problem);
                return null;
            } catch (JSONException e) {
                Log.e(TAG, "Could not parse JSON response", e);
                notifyUploadFailed(R.string.error_bad_response);
                return null;
            }

            if (jiraBugId.isEmpty()) {
                notifyUploadFailed(R.string.error_bad_response);
                return null;
            }

            // Now we attach the file
            try {
                attachFile(mReportUri, jiraBugId);
            } catch (ZipException e) {
                notifyUploadFailed(R.string.error_zip_fail);
            } catch (IOException e) {
                notifyUploadFailed(R.string.error_file_fail);
            }

            return jiraBugId;
        }

        @Override
        protected void onPostExecute(String bugId) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;
            }
            notifyUploadFinished(bugId);
            stopSelf();
        }

        private String uploadAndGetId(JSONObject input) throws IOException, JSONException {
            DefaultHttpClient client = new DefaultHttpClient();
            HttpPost post = new HttpPost(API_URL);

            // Turn the JSONObject being passed into a stringentity for http consumption
            post.setEntity(new StringEntity(input.toString()));
            post.setHeader("Accept","application/json");
            post.setHeader("Authorization","Basic " + AUTH);
            post.setHeader("Content-Type","application/json");

            HttpResponse response = client.execute(post);
            HttpEntity entity = response.getEntity();

            JSONObject output = new JSONObject(EntityUtils.toString(entity));
            return output.getString("key");
        }

        private void attachFile(Uri reportUri, String bugId) throws IOException, ZipException {
            DefaultHttpClient client = new DefaultHttpClient();
            HttpPost post = new HttpPost(API_URL + bugId + "/attachments");
            File zippedReportFile = null;

            post.setHeader("Authorization","Basic " + AUTH);
            post.setHeader("X-Atlassian-Token","nocheck");
            try {
                File bugreportFile = new File("/data" + reportUri.getPath());
                File scrubbedBugReportFile = getFileStreamPath(SCRUBBED_BUG_REPORT_PREFIX
                        + bugreportFile.getName());
                ScrubberUtils.scrubFile(bugreportFile, scrubbedBugReportFile);
                zippedReportFile = zipFile(scrubbedBugReportFile);

                MultipartEntity bugreportUploadEntity = new MultipartEntity();
                bugreportUploadEntity.addPart("file", new FileBody(zippedReportFile));
                post.setEntity(bugreportUploadEntity);

                client.execute(post);
            } finally {
                if (zippedReportFile != null) {
                    zippedReportFile.delete();
                }
            }
        }

        private File zipFile(File bugreportFile) throws ZipException {
            FileInputStream fis = null;
            ZipOutputStream zos = null;
            File zippedFile = new File(getCacheDir(), bugreportFile.getName() + ".zip");
            try {
                byte[] buffer = new byte[1024];
                FileOutputStream fos = new FileOutputStream(zippedFile);
                zos = new ZipOutputStream(fos);
                fis = new FileInputStream(bugreportFile);
                zos.putNextEntry(new ZipEntry(bugreportFile.getName()));
                int length;
                while ((length = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
            } catch (IOException e) {
                Log.e(TAG, "Could not zip bug report", e);
                throw new ZipException();
            } finally {
                if (fis != null)
                    try {
                        fis.close();
                    } catch (IOException e) {
                    }
                if (zos != null)
                    try {
                        zos.close();
                    } catch (IOException e) {
                    }
            }
            return zippedFile;
        }
    }
}
