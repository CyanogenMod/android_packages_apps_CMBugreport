LOCAL_PATH:=$(call my-dir)
include $(CLEAR_VARS)

LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

#This tag is must for making it system apps
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

# include the prebuilt static library which is mentioned in
LOCAL_STATIC_JAVA_LIBRARIES := org.cyanogenmod.platform.sdk

LOCAL_PACKAGE_NAME := CMBugReport

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

# build tests
include $(call all-makefiles-under,$(LOCAL_PATH))
