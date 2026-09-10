# Runs a generated end-to-end program and captures its stdout to a file. Used by
# the T8 build integration so the result can be diffed with
# `cmake -E compare_files`.
#
# Usage:
#   cmake -DEXE=<executable> -DOUT=<stdout file> -P run_capture.cmake

if(NOT DEFINED EXE OR NOT DEFINED OUT)
    message(FATAL_ERROR "run_capture.cmake requires -DEXE=<exe> and -DOUT=<file>")
endif()

get_filename_component(out_dir "${OUT}" DIRECTORY)
file(MAKE_DIRECTORY "${out_dir}")

# The generated program may read repo-relative paths, so allow the caller to set
# the working directory (the e2e integration passes the repository root).
set(run_args COMMAND "${EXE}" OUTPUT_FILE "${OUT}" RESULT_VARIABLE exit_code)
if(DEFINED WORKDIR)
    list(APPEND run_args WORKING_DIRECTORY "${WORKDIR}")
endif()

execute_process(${run_args})

if(NOT exit_code EQUAL 0)
    message(FATAL_ERROR "generated program '${EXE}' exited with code ${exit_code}")
endif()
