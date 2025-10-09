if(NOT TARGET hermes-engine::libhermes)
add_library(hermes-engine::libhermes SHARED IMPORTED)
set_target_properties(hermes-engine::libhermes PROPERTIES
    IMPORTED_LOCATION "/Users/khawarizma/.gradle/caches/8.8/transforms/9847228f87e395c5f14272f762568bbd/transformed/hermes-android-0.76.5-debug/prefab/modules/libhermes/libs/android.arm64-v8a/libhermes.so"
    INTERFACE_INCLUDE_DIRECTORIES "/Users/khawarizma/.gradle/caches/8.8/transforms/9847228f87e395c5f14272f762568bbd/transformed/hermes-android-0.76.5-debug/prefab/modules/libhermes/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

