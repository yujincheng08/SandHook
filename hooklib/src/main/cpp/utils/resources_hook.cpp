//
// Created by loves on 8/31/2020.
// Code from https://github.com/ElderDrivers/EdXposed/blob/master/edxp-core/src/main/cpp/main/src/resource_hook.cpp
//

#include <jni.h>
#include <dlfcn.h>
#include <string_view>
#include <android/log.h>

#include "../includes/resources_type.h"
#include "../includes/dlfcn_nougat.h"

static jclass x_resources_class;
static jmethodID translate_attr_id;
static jmethodID translate_res_id;


template<typename T>
constexpr inline T lp_select(T x, T y) {
#if defined(__LP64__)
    return y;
#else
    return x;
#endif
}

static std::string_view kLibFwPath{lp_select("/system/lib/libandroidfw.so", "/system/lib64/libandroidfw.so")};

using TYPE_NEXT = int32_t (*)(void *);
using TYPE_RESTART = void(*)(void *);
using TYPE_STRING_AT =  char16_t *(*)(const void *, int32_t, size_t *);
using TYPE_GET_ATTR_NAME_ID = int32_t (*)(void *, int);

static TYPE_NEXT res_XML_parser_next = nullptr;
static TYPE_RESTART res_XML_parser_restart = nullptr;
static TYPE_GET_ATTR_NAME_ID res_XML_parser_get_attribute_name_id = nullptr;
static TYPE_STRING_AT res_string_pool_string_at = nullptr;

class ScopedDlHandle {
public:
    ScopedDlHandle(const char *file) {
        handle_ = fake_dlopen(file, RTLD_LAZY | RTLD_GLOBAL);
    }

    ~ScopedDlHandle() {
        if (handle_) {
            fake_dlclose(handle_);
        }
    }

    void *Get() const {
        return handle_;
    }

    template<typename T>
    T DlSym(const char *sym_name) const {
        return reinterpret_cast<T>(fake_dlsym(handle_, sym_name));
    }

    bool IsValid() const {
        return handle_ != nullptr;
    }

private:
    void *handle_;
};




void rewrite_xml_native(JNIEnv *env, jclass,
                        jlong parserPtr, jobject origRes, jobject repRes) {
    auto parser = (android::ResXMLParser *) parserPtr;

    if (parser == nullptr)
        return;

    const android::ResXMLTree &mTree = parser->mTree;
    auto mResIds = (uint32_t *) mTree.mResIds;
    android::ResXMLTree_attrExt *tag;
    int attrCount;

    do {
        switch (res_XML_parser_next(parser)) {
            case android::ResXMLParser::START_TAG:
                tag = (android::ResXMLTree_attrExt *) parser->mCurExt;
                attrCount = dtohs(tag->attributeCount);
                for (int idx = 0; idx < attrCount; idx++) {
                    auto attr = (android::ResXMLTree_attribute *)
                            (((const uint8_t *) tag)
                             + dtohs(tag->attributeStart)
                             + (dtohs(tag->attributeSize) * idx));

                    // find resource IDs for attribute names
                    int32_t attrNameID = res_XML_parser_get_attribute_name_id(parser, idx);
                    // only replace attribute name IDs for app packages
                    if (attrNameID >= 0 && (size_t) attrNameID < mTree.mNumResIds &&
                        dtohl(mResIds[attrNameID]) >= 0x7f000000) {
                        size_t attNameLen;
                        const char16_t *attrName = res_string_pool_string_at(&(mTree.mStrings),
                                                                             attrNameID,
                                                                             &attNameLen);
                        jint attrResID = env->CallStaticIntMethod(x_resources_class,
                                                                  translate_attr_id,
                                                                  env->NewString(
                                                                          (const jchar *) attrName,
                                                                          attNameLen), origRes);
                        if (env->ExceptionCheck())
                            goto leave;

                        mResIds[attrNameID] = htodl(attrResID);
                    }

                    // find original resource IDs for reference values (app packages only)
                    if (attr->typedValue.dataType != android::Res_value::TYPE_REFERENCE)
                        continue;

                    jint oldValue = dtohl(attr->typedValue.data);
                    if (oldValue < 0x7f000000)
                        continue;

                    jint newValue = env->CallStaticIntMethod(x_resources_class,
                                                             translate_res_id,
                                                             oldValue, origRes, repRes);
                    if (env->ExceptionCheck())
                        goto leave;

                    if (newValue != oldValue)
                        attr->typedValue.data = htodl(newValue);
                }
                continue;
            case android::ResXMLParser::END_DOCUMENT:
            case android::ResXMLParser::BAD_DOCUMENT:
                goto leave;
            default:
                continue;
        }
    } while (true);

    leave:
    res_XML_parser_restart(parser);

}

static bool prepare_symbols() {
    ScopedDlHandle fw_handle(kLibFwPath.data());
    if (!fw_handle.IsValid()) {
        return false;
    };
    if (!(res_XML_parser_next = fw_handle.DlSym<TYPE_NEXT>(
            "_ZN7android12ResXMLParser4nextEv"))) {
        return false;
    }
    if (!(res_XML_parser_restart = fw_handle.DlSym<TYPE_RESTART>(
            "_ZN7android12ResXMLParser7restartEv"))) {
        return false;
    };
    if (!(res_XML_parser_get_attribute_name_id = fw_handle.DlSym<TYPE_GET_ATTR_NAME_ID>(
            lp_select("_ZNK7android12ResXMLParser18getAttributeNameIDEj",
                      "_ZNK7android12ResXMLParser18getAttributeNameIDEm")))) {
        return false;
    }
    return (res_string_pool_string_at = fw_handle.DlSym<TYPE_STRING_AT>(
            lp_select("_ZNK7android13ResStringPool8stringAtEjPj",
                      "_ZNK7android13ResStringPool8stringAtEmPm"))) != nullptr;
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_com_swift_sandhook_xposedcompat_XposedCompat_initXResourcesNative(JNIEnv *env, jclass clazz, jclass x_res_class) {
    x_resources_class = x_res_class;
    if(!x_resources_class) return JNI_FALSE;
    JNINativeMethod methods[1] {JNINativeMethod{"rewriteXmlReferencesNative", "(JLandroid/content/res/XResources;Landroid/content/res/Resources;)V",
                                                reinterpret_cast<void*>(rewrite_xml_native)}};
    auto reg_result = env->RegisterNatives(x_resources_class, methods,1);
    if(reg_result != JNI_OK) return JNI_FALSE;

    __android_log_print(ANDROID_LOG_WARN, "SANDHOOK_NATIVE", "HERE");

    translate_res_id = env->GetStaticMethodID(
            x_resources_class, "translateResId",
            "(ILandroid/content/res/XResources;Landroid/content/res/Resources;)I");
    if (!translate_res_id) {
        return JNI_FALSE;
    }
    translate_attr_id = env->GetStaticMethodID(
            x_resources_class, "translateAttrId",
            "(Ljava/lang/String;Landroid/content/res/XResources;)I");
    if (!translate_attr_id) {
        return JNI_FALSE;
    }
    if(!prepare_symbols()) {
        return JNI_FALSE;
    }
    x_resources_class = reinterpret_cast<jclass>(env->NewGlobalRef(x_resources_class));
    return JNI_TRUE;
}

