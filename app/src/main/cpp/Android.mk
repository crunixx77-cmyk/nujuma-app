LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libnujuma-bootstrap
LOCAL_SRC_FILES := nujuma-bootstrap-zip.S nujuma-bootstrap.c
include $(BUILD_SHARED_LIBRARY)
