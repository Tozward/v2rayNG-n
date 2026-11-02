#!/system/bin/sh
# UiAutomation executes argv directly, so keep the pipeline in a real shell script.
printf tun-smoke | toybox nc "$@"
