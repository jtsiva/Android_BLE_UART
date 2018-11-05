#!/bin/bash
adb shell dumpsys batterystats > ../data_v1/$1.zip
