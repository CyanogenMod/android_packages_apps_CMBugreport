LOCAL_PATH:=$(call my-dir)
include $(CLEAR_VARS)

LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

#This tag is must for making it system apps
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

# include the prebuilt static library which is mentioned in
LOCAL_STATIC_JAVA_LIBRARIES := bugreport_httpmime apachemime

LOCAL_PACKAGE_NAME := CMBugReport

LOCAL_PROGUARD_FLAGS := -include $(LOCAL_PATH)/proguard.cfg

LOCAL_STATIC_JAVA_LIBRARIES += org.cyanogenmod.platform.sdk

include $(BUILD_PACKAGE)

include $(CLEAR_VARS)


#include any prebuilt jars you are using in your application which is present in
#libs folder of your package
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := bugreport_httpmime:libs/httpmime-4.0.3.jar apachemime:libs/apache-mime4j-0.6.jar

include $(BUILD_MULTI_PREBUILT)

include $(call all-makefiles-under,$(LOCAL_PATH))
