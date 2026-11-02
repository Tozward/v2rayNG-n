#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_dir/native/singtun"

# Bind both packages in one invocation: separate AARs would embed two Go runtimes.
mobile_version=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$(go env GOPATH)/bin:$PATH"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$NDK_HOME}"
gomobile init
mkdir -p "$repo_dir/V2rayNG/app/libs"
gomobile bind -target=android -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o "$repo_dir/V2rayNG/app/libs/libv2ray.aar" \
  . github.com/2dust/AndroidLibXrayLite

python3 - "$repo_dir/V2rayNG/app/libs/libv2ray.aar" <<'PY'
import io
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as aar:
    with ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
        for name in ('libv2ray/CoreController.class', 'singtun/Controller.class'):
            assert name in classes.namelist(), f'Missing Java binding: {name}'
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        assert aar.getinfo(f'jni/{abi}/libgojni.so').file_size > 0
print('Verified combined Xray/Sing TUN AAR with one Go JNI library per ABI')
PY
