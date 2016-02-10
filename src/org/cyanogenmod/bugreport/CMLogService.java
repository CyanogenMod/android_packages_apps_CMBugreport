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
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;
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
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CMLogService extends IntentService {
    private static final String TAG = "CMLogService";
    public static final String SCRUBBED_BUG_REPORT_PREFIX = "scrubbed_";
    private static final String FILENAME_PROC_VERSION = "/proc/version";

    public static final String RO_CM_VERSION = "ro.cm.version";
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
                } else if (uri.toString().endsWith("zip")) {
                    reportUri = zipUri(uri);
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
            fields.put("summary", summary);
            fields.put("description", description);
            fields.put("issuetype", issuetype);
            if(!getString(R.string.config_kernel_field).isEmpty() && !getString(R.string.config_buildid_field).isEmpty()) {
                fields.put(getString(R.string.config_buildid_field), SystemProperties.get(RO_CM_VERSION, ""));
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
            jiraBugId = UploadHelper.uploadAndGetId(this, json);
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


    private void attachFile(Uri reportUri, String bugId, Uri sshotUri)
            throws IOException {

        File bugreportFile = new File(reportUri.getPath());
        File scrubbedBugReportFile = getFileStreamPath(SCRUBBED_BUG_REPORT_PREFIX
                + bugreportFile.getName());

        // scrub file clean of user info
        ScrubberUtils.scrubFile(CMLogService.this, bugreportFile, scrubbedBugReportFile);

        // zip it back up, maybe with a screenshot
        File zippedReportFile;
        if (sshotUri != null) {
            File sshotFile = new File("/data" + sshotUri.getPath());
            zippedReportFile = zipFiles(scrubbedBugReportFile, sshotFile);
        } else {
            zippedReportFile = zipFiles(scrubbedBugReportFile);
        }
        UploadHelper.attachFile(this, zippedReportFile, bugId);
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

    private Uri zipUri(Uri zipUri) {
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
            String fullFileName = getCacheDir() + "/" + filename;
            unZipped = new FileOutputStream(fullFileName);
            int count = 0;
            while ((count = zis.read(buffer)) != -1) {
                unZipped.write(buffer, 0, count);
            }
            zis.closeEntry();
            fileUri = Uri.parse(fullFileName);
        } catch (Exception e) {
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
}
