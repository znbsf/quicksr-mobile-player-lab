cmake_minimum_required(VERSION 3.22.1)
project(quicksr_output_packer LANGUAGES CXX)

add_library(
        quicksr_output_packer
        SHARED
        native_output_packer.cpp)

target_compile_features(quicksr_output_packer PRIVATE cxx_std_17)
