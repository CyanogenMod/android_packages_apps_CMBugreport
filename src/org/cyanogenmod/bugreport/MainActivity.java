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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.CheckBox;
import android.net.Uri;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private ArrayList<Uri> mAttachments;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            if ("application/vnd.android.bugreport".equals(type)) {
                mAttachments = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
        }
        if (mAttachments == null) {
            mAttachments = new ArrayList<Uri>();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_submit) {
            sendBug();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void sendBug() {
        // Grab entered text
        EditText summaryEditText = (EditText) findViewById(R.id.summary);
        String summary = summaryEditText.getText().toString();
        summaryEditText.setError(TextUtils.isEmpty(summary)
                ? getString(R.string.error_no_text) : null);

        EditText descriptionEditText = (EditText) findViewById(R.id.description);
        String description = descriptionEditText.getText().toString();
        descriptionEditText.setError(TextUtils.isEmpty(description)
                ? getString(R.string.error_no_text) : null);

        CheckBox ssCheck = (CheckBox) findViewById(R.id.ssCheckBox);
        boolean ssPreference = ssCheck.isChecked();

        if (descriptionEditText.getError() != null || summaryEditText.getError() != null) {
            return;
        }

        Intent intent = new Intent(this, CMLogService.class);
        intent.putExtra(Intent.EXTRA_SUBJECT, summary);
        intent.putExtra(Intent.EXTRA_TEXT, description);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, mAttachments);
        intent.putExtra("org.cyanogenmod.bugreport.AddScreenshot", ssPreference);
        startService(intent);

        // Make the screen go away
        finish();
    }
}
