LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)


LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := CMBugReportTests

LOCAL_INSTRUMENTATION_FOR := CMBugReport

#LOCAL_JAVA_LIBRARIES += android.test.runner

#LOCAL_JACK_ENABLED := disabled

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)
