LOCAL_PATH:=$(call my-dir)
include $(CLEAR_VARS)

LOCAL_CERTIFICATE := platform

#This tag is must for making it system apps
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

#Any aidl files in your application can be added here
LOCAL_SRC_FILES += 

LOCAL_JAVA_STATIC_LIBRARIES := httpcore httpmime apachemime httpclient 

# include the prebuilt static library which is mentioned in
#LOCAL_PREBUILT_STATIC_JAVA_LIBARIES
LOCAL_STATIC_JAVA_LIBRARIES := httpcore httpmime apachemime httpclient 

LOCAL_PACKAGE_NAME := CMLogCapture

LOCAL_PROGUARD_FLAGS := -include $(LOCAL_PATH)/proguard.cfg

include $(BUILD_PACKAGE)

include $(CLEAR_VARS)


#include any prebuilt jars you are using in your application which is present in
#libs folder of your package
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := httpcore:libs/httpcore-4.0.1.jar httpmime:libs/httpmime-4.0.3.jar apachemime:libs/apache-mime4j-0.6.jar httpclient:libs/httpclient-4.0.3.jar

include $(BUILD_MULTI_PREBUILT)

include $(call all-makefiles-under,$(LOCAL_PATH))
