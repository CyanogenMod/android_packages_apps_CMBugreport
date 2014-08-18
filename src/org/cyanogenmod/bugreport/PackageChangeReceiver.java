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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;

public class PackageChangeReceiver extends BroadcastReceiver {

    private static final String XPOSED_INSTALLER_PACAKGE = "de.robv.android.xposed.installer";

    @Override
    public void onReceive(Context context, Intent intent) {
        int newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (isPackageInstalled(context, XPOSED_INSTALLER_PACAKGE)) {
            // disable bug reporting
            newState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }

        ComponentName crashActivity = new ComponentName(context, CrashFeedbackActivity.class);
        ComponentName bugActivity = new ComponentName(context, MainActivity.class);

        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(crashActivity, newState, 0);
        pm.setComponentEnabledSetting(bugActivity, newState, 0);
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        if (pkg == null) {
            return false;
        }
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
            if (!pi.applicationInfo.enabled) {
                return false;
            } else {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

}