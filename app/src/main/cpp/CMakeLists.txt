cmake_minimum_required(VERSION 3.22.1)
project(integrity LANGUAGES CXX)

# 仅 arm64-v8a（与 app 的 ndk.abiFilters 一致，minSdk 30 只跑真机 arm64）。
# 不依赖任何第三方库，纯 C++ 标准 + Android log。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(integrity SHARED integrity.cpp)

find_library(log-lib log)
target_link_libraries(integrity ${log-lib})

# 关闭可预测的函数节名，稍微提高对符号扫描的成本（非安全银弹）。
set_target_properties(integrity PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
