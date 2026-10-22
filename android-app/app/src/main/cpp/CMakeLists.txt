cmake_minimum_required(VERSION 3.22.1)
project(vint_jni)
# llama.cpp is NOT a submodule: tools/setup_llama_cpp.{sh,ps1} fetch it at the commit PINNED in docs/LLAMA_CPP_PIN.txt.
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/llama.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)            # no networking code compiled in
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama_build)
add_library(pidgin_jni SHARED pidgin_jni.cpp)       # library name kept: Kotlin loads "pidgin_jni"
if(ANDROID)
  target_link_libraries(pidgin_jni llama android log)
else()
  target_link_libraries(pidgin_jni llama)           # host build used by tools/host-verify
endif()
