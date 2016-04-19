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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;
import libcore.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CMLogService extends IntentService {

    private static final String ACTION_RETRY_UPLOAD
            = "org.cyanogenmod.bugreport.action.RETRY_BUGREPORT_UPLOAD";
    private static final String ACTION_CANCEL_UPLOAD
            = "org.cyanogenmod.bugreport.action.CANCEL_BUGREPORT_UPLOAD";
    private static final String EXTRA_RETRY_FILE_PATH = "extra_zipped_file";
    private static final String EXTRA_JSON_INFO = "extra_json_info";

    private static final String TAG = "CMLogService";
    public static final String SCRUBBED_BUG_REPORT_PREFIX = "scrubbed_";
    private static final String FILENAME_PROC_VERSION = "/proc/version";

    public static final String RO_CM_VERSION = "ro.cm.version";
    public static final String BUILD_ID_FIELD = "customfield_10800";
    public static final String KERNELVER_FIELD = "customfield_10104";

    public static Boolean isCMKernel = false;

    public CMLogService() {
        super("CMLogService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        ProcessedInfo processedInfo;
        if (Objects.equal(intent.getAction(), ACTION_RETRY_UPLOAD)) {
            boolean fail = false;

            processedInfo = new ProcessedInfo();
            if (intent.hasExtra(EXTRA_RETRY_FILE_PATH) && intent.hasExtra(EXTRA_JSON_INFO)) {
                try {
                    processedInfo.inputJson = new JSONObject(intent.getStringExtra(EXTRA_JSON_INFO));
                    final String filePath = intent.getStringExtra(EXTRA_RETRY_FILE_PATH);
                    if (filePath != null) {
                        processedInfo.zippedFile = new File(filePath);
                    } else {
                        fail = true; // can't upload a null file
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "fail parsing extra JSON", e);
                    fail = true;
                }
            } else {
                fail = true; // no filepath extra
            }
            if (fail) {
                if (processedInfo.zippedFile != null && processedInfo.zippedFile.exists()) {
                    notifyNotOnlineAndRetry(processedInfo, 0);
                } else {
                    notifyUploadFailed(R.string.error_file_fail);
                }
                return;
            }
        } else if (Objects.equal(intent.getAction(), ACTION_CANCEL_UPLOAD)) {
            if (intent.hasExtra(EXTRA_RETRY_FILE_PATH)) {
                final File zippedFile = new File(intent.getStringExtra(EXTRA_RETRY_FILE_PATH));
                if (zippedFile.exists()) {
                    zippedFile.delete();
                }
            }
            // clear notification
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(CMLogService.class.getSimpleName(), R.string.notif_title);
            return;
        } else {
            notifyProcessing();
            final UnprocessedInfo info = gatherInfo(intent);
            if (info == null) {
                notifyUploadFailed(R.string.error_problem_creating);
                return;
            }
            processedInfo = new ProcessedInfo();
            if (info.reportUri != null) {
                processedInfo.zippedFile = scrubFileAndZip(info.reportUri, info.ssUri);
                if (processedInfo.zippedFile == null || !processedInfo.zippedFile.exists()) {
                    notifyUploadFailed(R.string.error_zip_fail);
                    return;
                }
            }

            processedInfo.inputJson = info.inputJson;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();
        try {
            if (!isNetworkAvailable()) {
                notifyNotOnlineAndRetry(processedInfo, 0);
                return;
            }

            notifyOfUpload();
            doUpload(processedInfo);
        } finally {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    private File scrubFileAndZip(Uri reportUri, Uri sshotUri) {
        File bugreportFile = new File(reportUri.getPath());
        File scrubbedFile = null;
        File zippedReportFile = null;

        // first scrub
        try {
            scrubbedFile = getFileStreamPath(SCRUBBED_BUG_REPORT_PREFIX + bugreportFile.getName());
            ScrubberUtils.scrubFile(CMLogService.this, bugreportFile, scrubbedFile);

            // zip it back up, maybe with a screenshot
            if (sshotUri != null) {
                File sshotFile = new File("/data" + sshotUri.getPath());
                zippedReportFile = UploadHelper.zipFiles(this, scrubbedFile, sshotFile);
            } else {
                zippedReportFile = UploadHelper.zipFiles(this, scrubbedFile);
            }
        } catch (IOException e) {
            zippedReportFile = null;
        }
        return zippedReportFile;
    }

    private UnprocessedInfo gatherInfo(Intent intent) {
        ArrayList<Uri> attachments = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        Uri reportUri = null;
        Uri sshotUri = null;

        if (attachments != null) {
            for (Uri uri : attachments) {
                if (uri.toString().endsWith("txt")) {
                    reportUri = uri;
                } else if (uri.toString().endsWith("png")) {
                    sshotUri = uri;
                } else if (uri.toString().endsWith("zip")) {
                    reportUri = zipUri(this, uri);
                }
            }
        }

        String summary = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String description = intent.getStringExtra(Intent.EXTRA_TEXT);
        String kernelver = getFormattedKernelVersion();
        if (!intent.getBooleanExtra("org.cyanogenmod.bugreport.AddScreenshot", false)) {
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
            fields.put("summary", new String(summary.getBytes(), "UTF-8"));
            fields.put("description", new String(description.getBytes(), "UTF-8"));
            fields.put("issuetype", issuetype);
            if (!getString(R.string.config_kernel_field).isEmpty()
                    && !getString(R.string.config_buildid_field).isEmpty()) {
                fields.put(getString(R.string.config_buildid_field),
                        SystemProperties.get(RO_CM_VERSION, ""));
                fields.put(getString(R.string.config_kernel_field), kernelver);
            } else {
                fields.put(BUILD_ID_FIELD, SystemProperties.get(RO_CM_VERSION, ""));
                fields.put(KERNELVER_FIELD, kernelver);
            }
            if (summary.startsWith(CrashFeedbackActivity.CRASH_PREFIX)) {
                labels.put("crash");
            } else {
                labels.put("user");
            }
            if (!isCMKernel) {
                labels.put("non-CM-kernel");
            }
            fields.put("labels", labels);
            inputJSON.put("fields", fields);
        } catch (JSONException e) {
            Log.e(TAG, "error configuring JSON", e);
            return null;
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "error converting to UTF8", e);
            return null;
        }
        UnprocessedInfo info = new UnprocessedInfo();
        info.inputJson = inputJSON;
        info.reportUri = reportUri;
        info.ssUri = sshotUri;
        return info;
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
        nm.notify(CMLogService.class.getSimpleName(), R.string.notif_title,
                notificationBuilder.build());
    }

    private void notifyNotOnlineAndRetry(ProcessedInfo info, int uploadError) {
        final Intent retryIntent = new Intent(ACTION_RETRY_UPLOAD)
                .putExtra(EXTRA_JSON_INFO, info.inputJson.toString())
                .putExtra(EXTRA_RETRY_FILE_PATH, info.zippedFile.getPath());

        final PendingIntent retryPi = PendingIntent.getService(this, 0, retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification.Action retry = new Notification.Action.Builder(
                Icon.createWithResource(getApplicationContext(), R.drawable.ic_tab_upload),
                getString(R.string.action_retry),
                retryPi).build();

        final Notification.Action cancel = new Notification.Action.Builder(
                Icon.createWithResource(getApplicationContext(), android.R.drawable.ic_delete),
                getString(R.string.action_delete),
                PendingIntent.getService(this, 0, new Intent(ACTION_CANCEL_UPLOAD)
                                .putExtra(EXTRA_RETRY_FILE_PATH, info.zippedFile.getPath()),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .build();

        String notificationMessage;
        if (uploadError > 0) {
            notificationMessage = getString(R.string.error_upload_failed, getString(uploadError));
        } else {
            notificationMessage = getString(R.string.error_connection_problem);
        }

        Notification.Builder notificationBuilder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.stat_cmbugreport)
                .setOngoing(true)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(notificationMessage)
                .setContentIntent(retryPi)
                .addAction(retry)
                .addAction(cancel);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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

    private String doUpload(ProcessedInfo info) {
        String jiraBugId;

        // create a new JIRA ticket and grab the ID
        try {
            jiraBugId = UploadHelper.uploadAndGetId(this, info.inputJson);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Could not parse JSON response", e);
            notifyNotOnlineAndRetry(info, R.string.error_bad_response);
            return null;
        }

        // verify the id is sane
        if (jiraBugId == null || jiraBugId.isEmpty()) {
            notifyNotOnlineAndRetry(info, R.string.error_bad_response);
            return null;
        }

        // Now we attach the report
        if (info.zippedFile != null && info.zippedFile.exists()) {
            try {
                notifyOfUpload();
                UploadHelper.attachFile(this, info.zippedFile, jiraBugId);
                notifyUploadFinished(jiraBugId);
                info.zippedFile.delete();
                return jiraBugId; // success!
            } catch (IOException e) {
                notifyNotOnlineAndRetry(info, R.string.error_bad_response);
            }
        } else {
            notifyUploadFinished(jiraBugId);
        }
        return null;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private static Uri zipUri(Context context, Uri zipUri) {
        Uri fileUri = null;
        File zipFile = null;
        FileInputStream is = null;
        ZipInputStream zis = null;
        FileOutputStream unZipped = null;
        try {
            zipFile = new File("/data" + zipUri.getPath());
            is = new FileInputStream(zipFile);
            zis = new ZipInputStream(new BufferedInputStream(is));
            ZipEntry ze = zis.getNextEntry();
            byte[] buffer = new byte[1024];
            String filename = ze.getName();
            String fullFileName = context.getCacheDir() + "/" + filename;
            unZipped = new FileOutputStream(fullFileName);
            int count = 0;
            while ((count = zis.read(buffer)) != -1) {
                unZipped.write(buffer, 0, count);
            }
            zis.closeEntry();
            fileUri = Uri.parse(fullFileName);
        } catch (IOException e) {
            Log.e(TAG, " failed to unzip ", e);
        } finally {
            try {
                if (zis != null) {
                    zis.close();
                }
                if (unZipped != null) {
                    unZipped.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "can't even close things right", e);
            }
        }
        return fileUri;
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

        String builder_regex = "\\@cyanogenmod";

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
        if (k.find()) {
            isCMKernel = true;
        }

        return m.group(1) + "\n" +                 // 3.0.31-g6fb96c9
                m.group(2) + " " + m.group(3) + "\n" + // x@y.com #1
                m.group(4);                            // Thu Jun 28 11:02:39 PDT 2012
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

    /**
     * Reads a line from the specified file.
     * @param filename the file to read from
     * @return the first line, if any.
     * @throws IOException if the file couldn't be read
     */
    private static String readLine(String filename) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename), 256)) {
            return reader.readLine();
        }
    }

    private static class UnprocessedInfo {
        Uri reportUri;
        Uri ssUri;
        JSONObject inputJson;
    }

    private static class ProcessedInfo {
        JSONObject inputJson;
        File zippedFile;
    }
}
