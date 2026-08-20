#pragma once

#include "jni.h"
#include <string>

bool tjr_register_mobile_natives(
    JNIEnv *environment,
    std::string &error
);
