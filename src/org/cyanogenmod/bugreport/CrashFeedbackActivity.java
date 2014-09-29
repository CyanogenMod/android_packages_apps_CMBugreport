package org.cyanogenmod.bugreport;
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

import android.app.Activity;
import android.app.ApplicationErrorReport;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class CrashFeedbackActivity extends Activity {

    private static final boolean DEBUG = true;

    private static final String TAG = CrashFeedbackActivity.class.getSimpleName();
    public static final String LAST_SUBMISSION = "last_submission";
    public static final String RO_CM_VERSION = "ro.cm.version";
    public static final String CRASH_PREFIX = "[CRASH] ";

    ApplicationErrorReport mReport;
    Button mCancelButton, mSubmitButton;
    TextView mSubjectText;
    TextView mContentText;

    ViewGroup mNetworkUnavailableView;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onNetworkAvailabilityChange();
        }
    };

    private View.OnLongClickListener mContextTextOnLongClickListener =
            new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    boolean success = copyToClipboard(getContent());
                    int messageStringId = success ? R.string.crash_clipboard_toast_message_success :
                                                    R.string.crash_clipboard_toast_message_failure;
                    Toast.makeText(CrashFeedbackActivity.this,
                                   getResources().getString(messageStringId),
                                   Toast.LENGTH_SHORT).show();
                    return true;
                }
    };

    private void onNetworkAvailabilityChange() {
        boolean networkAvailable = isNetworkAvailable();
        if (DEBUG) {
            Log.d(TAG, "onNetworkAvailabilityChange(), available: " + networkAvailable);
        }
        if (mNetworkUnavailableView != null) {
            mNetworkUnavailableView.setVisibility(networkAvailable ? View.GONE : View.VISIBLE);
        }
        if (mSubmitButton != null) {
            mSubmitButton.setEnabled(networkAvailable);
        }
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.submit_crash);

        mCancelButton = (Button) findViewById(R.id.cancel);
        mSubmitButton = (Button) findViewById(R.id.submit);
        mSubjectText = (TextView) findViewById(R.id.subject);
        mContentText = (TextView) findViewById(R.id.content);
        mContentText.setOnLongClickListener(mContextTextOnLongClickListener);
        mNetworkUnavailableView = (ViewGroup) findViewById(R.id.no_network_warning);

        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mSubmitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadCrashReport();
            }
        });

        handleIncomingCrashReport(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        onNetworkAvailabilityChange();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingCrashReport(intent);
    }

    private void handleIncomingCrashReport(Intent intent) {
        if (intent == null) {
            return;
        }

        mReport = intent.getParcelableExtra(Intent.EXTRA_BUG_REPORT);
        mSubjectText.setText(getSubjectLine());
        mContentText.setText(getContent());
    }

    /**
     * Copy the given String to the clipboard.
     * @param stringToCopy The string to copy to the clipboard
     * @return True if successful, false otherwise
     */
    private boolean copyToClipboard(String stringToCopy) {
        ClipboardManager clipBoard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String clipLabel = getResources().getString(R.string.crash_clipboard_label);
        if (clipBoard != null && !TextUtils.isEmpty(stringToCopy)) {
            ClipData clipData = ClipData.newPlainText(clipLabel,
                                                      stringToCopy);
            clipBoard.setPrimaryClip(clipData);
            return true;
        }
        return false;
    }

    private String getSubjectLine() {
        if (mReport == null || mReport.crashInfo == null) {
            return "";
        }
        return CRASH_PREFIX + mReport.packageName
                + " threw " + mReport.crashInfo.exceptionClassName;
    }

    private String getContent() {
        if (mReport == null) {
            return "";
        }
        return mReport.crashInfo.stackTrace;
    }

    private void uploadCrashReport() {
        String content = getContent();

        if (getPreferences(0).getString(LAST_SUBMISSION, "").equals(content)) {
            Toast.makeText(this, R.string.already_submitted, Toast.LENGTH_LONG).show();
        } else if (mReport != null) {
            if (DEBUG) {
                Log.i(TAG, "Exception class name: " + mReport.crashInfo.exceptionClassName);
                Log.i(TAG, "Exception message: " + mReport.crashInfo.exceptionMessage);
                Log.i(TAG, "Throw class name: " + mReport.crashInfo.throwClassName);
                Log.i(TAG, "Throw file name: " + mReport.crashInfo.throwFileName);
                Log.i(TAG, "Throw line number: " + mReport.crashInfo.throwLineNumber);
                Log.i(TAG, "Throw method name: " + mReport.crashInfo.throwMethodName);
                Log.i(TAG, "Stack trace: " + mReport.crashInfo.stackTrace);
            }

            Intent uploadBugReportIntent = new Intent(this, CMLogService.class);
            uploadBugReportIntent.putExtra(Intent.EXTRA_SUBJECT, getSubjectLine());
            uploadBugReportIntent.putExtra(Intent.EXTRA_TEXT, content);

            getPreferences(0).edit().putString(LAST_SUBMISSION, content).commit();

            startService(uploadBugReportIntent);
        } else {
            if (DEBUG) {
                Log.d(TAG, "getIntent() was null?");
            }
        }
        finish();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnected();
        return isConnected;
    }
}
