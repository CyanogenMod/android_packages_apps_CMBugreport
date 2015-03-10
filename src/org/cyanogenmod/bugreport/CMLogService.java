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
import android.os.SystemProperties;
import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

public class CMLogService extends IntentService {
    private static final String TAG = "CMLogService";
    private static final String SCRUBBED_BUG_REPORT_PREFIX = "scrubbed_";
    private static final String FILENAME_PROC_VERSION = "/proc/version";

    public static final String RO_CM_VERSION = "ro.cm.version";
    public static final String SYSTEMLIB = "persist.sys.dalvik.vm.lib";
    public static final String DALVIKLIB = "libdvm.so";
    public static final String ARTLIB = "libart.so";
    public static final String BUILD_ID_FIELD = "customfield_10800";
    public static final String KERNELVER_FIELD = "customfield_10104";

    public static Boolean isCMKernel = false;
    private PowerManager.WakeLock mWakeLock;

    public CMLogService() {
        super("CMLogService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        ArrayList<Uri> attachments = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        Uri reportUri = null;
        Uri sshotUri = null;

        if (attachments != null) {
            for (Uri uri : attachments) {
                if (uri.toString().endsWith("txt")) {
                    reportUri = uri;
                } else if (uri.toString().endsWith("png")) {
                    sshotUri = uri;
                }
            }
        }

        String summary = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String description = intent.getStringExtra(Intent.EXTRA_TEXT);
        String kernelver = getFormattedKernelVersion();
        String syslib = SystemProperties.get(SYSTEMLIB);
        if(!intent.getBooleanExtra("org.cyanogenmod.bugreport.AddScreenshot", false)) {
            sshotUri = null;
        }

        JSONObject fields = new JSONObject();
        JSONObject project = new JSONObject();
        JSONObject issuetype = new JSONObject();
        JSONObject inputJSON = new JSONObject();
        JSONArray labels = new JSONArray();

        try {
            project.put("id", getString(R.string.config_project_name));
            issuetype.put("id", getString(R.string.config_issue_type));
            fields.put("project", project);
            fields.put("summary", summary);
            fields.put("description", description);
            fields.put("issuetype", issuetype);
            fields.put(BUILD_ID_FIELD, SystemProperties.get(RO_CM_VERSION, ""));
            fields.put(KERNELVER_FIELD, kernelver);
            if (summary.startsWith(CrashFeedbackActivity.CRASH_PREFIX)) {
                labels.put("crash");
            } else {
                labels.put("user");
            }
            if (ARTLIB.equals(syslib)){
                labels.put("ART");
            } else if (DALVIKLIB.equals(syslib)){
                labels.put("Dalvik");
            }
            if (!isCMKernel){
                labels.put("non-CM-kernel");
            }
            fields.put("labels", labels);
            inputJSON.put("fields", fields);
        } catch (JSONException e) {
            Log.e(TAG, "Input JSON could not be compiled", e);
            notifyUploadFailed(R.string.error_problem_creating);
            return;
        }


        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire(3000);

        notifyOfUpload();
        doUpload(reportUri, sshotUri, inputJSON);

        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    private void notify(CharSequence message, int iconResId, boolean withProgress,
                        boolean ongoing) {
        Notification.Builder notificationBuilder = new Notification.Builder(this);
        notificationBuilder
                .setSmallIcon(iconResId)
                .setOngoing(ongoing)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(message);
        if (withProgress) {
            notificationBuilder.setProgress(0, 0, true);
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(CMLogService.class.getSimpleName(), R.string.notif_title);
        nm.notify(CMLogService.class.getSimpleName(), R.string.notif_title,
                notificationBuilder.build());
    }

    private void notifyOfUpload() {
        notify(getString(R.string.notif_uploading), R.drawable.ic_tab_upload, true, true);
    }

    private void notifyUploadFinished(String issueNumber) {
        notify(getString(R.string.notif_thanks), R.drawable.stat_cmbugreport, false, false);
    }

    private void notifyProcessing() {
        notify(getString(R.string.notif_processing), R.drawable.stat_cmbugreport, true, true);
    }

    private void notifyUploadFailed(int reasonResId) {
        String reason = getString(reasonResId);
        notify(getString(R.string.error_upload_failed, reason), R.drawable.stat_cmbugreport, false,
                false);
    }

    public static String getFormattedKernelVersion() {
       try {
           return formatKernelVersion(readLine(FILENAME_PROC_VERSION));
        } catch (IOException e) {
           Log.e(TAG,
               "IO Exception when getting kernel version for Device Info screen",
               e);
            return "Unavailable";
       }
   }

   public static String formatKernelVersion(String rawKernelVersion) {
        // Example (see tests for more):
        // Linux version 3.0.31-g6fb96c9 (android-build@xxx.xxx.xxx.xxx.com) \
        //     (gcc version 4.6.x-xxx 20120106 (prerelease) (GCC) ) #1 SMP PREEMPT \
        //     Thu Jun 28 11:02:39 PDT 2012

        final String PROC_VERSION_REGEX =
            "Linux version (\\S+) " + /* group 1: "3.0.31-g6fb96c9" */
            "\\((\\S+?)\\) " +        /* group 2: "x@y.com" (kernel builder) */
            "(?:\\(gcc.+? \\)) " +    /* ignore: GCC version information */
            "(#\\d+) " +              /* group 3: "#1" */
            "(?:.*?)?" +              /* ignore: optional SMP, PREEMPT, and any CONFIG_FLAGS */
            "((Sun|Mon|Tue|Wed|Thu|Fri|Sat).+)"; /* group 4: "Thu Jun 28 11:02:39 PDT 2012" */

        String builder_regex = "build\\d\\d\\@cyanogenmod";

        Matcher m = Pattern.compile(PROC_VERSION_REGEX).matcher(rawKernelVersion);
        if (!m.matches()) {
            Log.e(TAG, "Regex did not match on /proc/version: " + rawKernelVersion);
            return "Unavailable";
        } else if (m.groupCount() < 4) {
            Log.e(TAG, "Regex match on /proc/version only returned " + m.groupCount()
                    + " groups");
            return "Unavailable";
        }

        Matcher k = Pattern.compile(builder_regex).matcher(m.group(2));
        if (k.matches()){
            isCMKernel = true;
        }

        return m.group(1) + "\n" +                 // 3.0.31-g6fb96c9
            m.group(2) + " " + m.group(3) + "\n" + // x@y.com #1
            m.group(4);                            // Thu Jun 28 11:02:39 PDT 2012
    }

    /**
     * Reads a line from the specified file.
     * @param filename the file to read from
     * @return the first line, if any.
     * @throws IOException if the file couldn't be read
     */
    private static String readLine(String filename) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(filename), 256);
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
    }

    private String doUpload(Uri reportUri, Uri screenshotUri, JSONObject json) {
        String jiraBugId;

        try {
            jiraBugId = uploadAndGetId(json);
        } catch (IOException e) {
            Log.e(TAG, "Could not upload bug report", e);
            notifyUploadFailed(R.string.error_connection_problem);
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "Could not parse JSON response", e);
            notifyUploadFailed(R.string.error_bad_response);
            return null;
        }

        if (jiraBugId == null || jiraBugId.isEmpty()) {
            notifyUploadFailed(R.string.error_bad_response);
            return null;
        }

        if (reportUri != null) {
            // Now we attach the report
            try {
                notifyProcessing();
                attachFile(reportUri, jiraBugId, screenshotUri);
                notifyUploadFinished(jiraBugId);
                return jiraBugId; // success!
            } catch (ZipException e) {
                notifyUploadFailed(R.string.error_zip_fail);
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
                notifyUploadFailed(R.string.error_file_fail);
            }
        } else {
            notifyUploadFinished(jiraBugId);
        }
        return null;
    }

    private String uploadAndGetId(JSONObject input) throws IOException, JSONException {
        URL url = new URL(getString(R.string.config_api_url));
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        try {
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestProperty("Authorization", "Basic " + getString(R.string.config_auth));
            urlConnection.setRequestProperty("Content-Type", "application/json");

            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);
            urlConnection.setFixedLengthStreamingMode(input.toString().length());

            OutputStream os = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(input.toString());
            writer.flush();
            writer.close();
            os.close();

            urlConnection.connect();

            String response = getResponse(urlConnection);
            JSONObject output = new JSONObject(response);
            return output.getString("key");
        } finally {
            urlConnection.disconnect();
        }
    }

    private String getResponse(HttpURLConnection httpUrlConnection) throws IOException {
        InputStream responseStream = new BufferedInputStream(httpUrlConnection.getInputStream());

        BufferedReader responseStreamReader = new BufferedReader(
                new InputStreamReader(responseStream));
        String line = "";
        StringBuilder stringBuilder = new StringBuilder();
        while ((line = responseStreamReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        responseStreamReader.close();
        responseStream.close();

        return stringBuilder.toString();
    }

    private void attachFile(Uri reportUri, String bugId, Uri sshotUri)
            throws IOException, ZipException {
        DefaultHttpClient client = new DefaultHttpClient();
        HttpConnectionParams.setConnectionTimeout(client.getParams(), 3000);
        HttpConnectionParams.setSoTimeout(client.getParams(), 3000);
        HttpPost post = new HttpPost(getString(R.string.config_api_url)
                + bugId + "/attachments");
        File zippedReportFile = null;

        post.setHeader("Authorization","Basic " + getString(R.string.config_auth));
        post.setHeader("X-Atlassian-Token","nocheck");
        try {
            File bugreportFile = new File("/data" + reportUri.getPath());
            File scrubbedBugReportFile = getFileStreamPath(SCRUBBED_BUG_REPORT_PREFIX
                    + bugreportFile.getName());
            ScrubberUtils.scrubFile(CMLogService.this, bugreportFile, scrubbedBugReportFile);
            if(sshotUri != null) {
                File sshotFile = new File("/data" + sshotUri.getPath());
                zippedReportFile = zipFiles(scrubbedBugReportFile, sshotFile);
            } else {
                zippedReportFile = zipFiles(scrubbedBugReportFile);
            }

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

    private File zipFiles(File... files) throws ZipException {
        ZipOutputStream zos = null;
        File zippedFile = new File(getCacheDir(), files[0].getName() + ".zip");
        try {
            byte[] buffer = new byte[1024];
            FileOutputStream fos = new FileOutputStream(zippedFile);
            zos = new ZipOutputStream(fos);

            for (int i = 0; i < files.length; i++) {
                FileInputStream fis = new FileInputStream(files[i]);
                try {
                    zos.putNextEntry(new ZipEntry(files[i].getName()));
                    int length;
                    while ((length = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                } finally {
                    try {
                        fis.close();
                    } catch (IOException e) {
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not zip bug report", e);
            throw new ZipException();
        } finally {
            if (zos != null)
                try {
                    zos.close();
                } catch (IOException e) {
                }
        }
        return zippedFile;
    }
}
