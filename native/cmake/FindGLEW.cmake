# projectM calls find_package(GLEW REQUIRED) on desktop OpenGL builds and then
# links GLEW::glew or GLEW::glew_s. We vendor glew-cmake (third_party/glew) so
# the Windows build needs no system GLEW; this module sits ahead of CMake's own
# FindGLEW on CMAKE_MODULE_PATH and routes to the vendored static target.
if(TARGET libglew_static)
    if(NOT TARGET GLEW::glew_s)
        add_library(GLEW::glew_s ALIAS libglew_static)
    endif()
    set(GLEW_FOUND TRUE)
    set(GLEW_INCLUDE_DIRS "${CMAKE_CURRENT_LIST_DIR}/../../third_party/glew/include")
    set(GLEW_LIBRARIES GLEW::glew_s)
else()
    message(FATAL_ERROR "FindGLEW.cmake: add_subdirectory(third_party/glew) must run before projectM")
endif()
