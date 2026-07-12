cmake_minimum_required(VERSION 3.22.1)

project(deadzone_backend)

add_executable(deadzone_daemon
    deadzone_daemon.cpp
)

target_compile_features(deadzone_daemon PRIVATE cxx_std_17)
