# Runs a generated program (or any executable) and captures its stdout to a file.
# Used by the T8/T11 build integration so the result can be diffed with
# `cmake -E compare_files`.
#
# Usage:
#   cmake -DEXE=<executable> [-DEXE_ARGS=<args>] -DOUT=<stdout file>
#         [-DWORKDIR=<dir>] -P run_capture.cmake

if(NOT DEFINED EXE OR NOT DEFINED OUT)
    message(FATAL_ERROR "run_capture.cmake requires -DEXE=<exe> and -DOUT=<file>")
endif()

get_filename_component(out_dir "${OUT}" DIRECTORY)
file(MAKE_DIRECTORY "${out_dir}")

set(run_args COMMAND "${EXE}")
if(DEFINED EXE_ARGS)
    list(APPEND run_args ${EXE_ARGS})
endif()
list(APPEND run_args OUTPUT_FILE "${OUT}" RESULT_VARIABLE exit_code)
# The generated program may read repo-relative paths, so allow the caller to set
# the working directory (the e2e integration passes the repository root).
if(DEFINED WORKDIR)
    list(APPEND run_args WORKING_DIRECTORY "${WORKDIR}")
endif()

execute_process(${run_args})

if(NOT exit_code EQUAL 0)
    message(FATAL_ERROR "program '${EXE}' exited with code ${exit_code}")
endif()
