{ pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = [ "34.0.0" "35.0.0" ];
    platformVersions = [ "35" ];
    includeNDK = true;
    includeSources = false;
    includeSystemImages = false;
    includeEmulator = false;
    extraLicenses = [
      "android-sdk-license"
      "android-sdk-preview-license"
    ];
  };
  androidSdk = androidComposition.androidsdk;
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    androidSdk
    jdk17
    gradle
    patchelf
  ];

  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
    pkgs.stdenv.cc.cc.lib
    pkgs.zlib
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";

  shellHook = ''
    # Patch AAPT2 binaries downloaded by Gradle (NixOS has no /lib64/ld-linux)
    for f in $(find ~/.gradle/caches -name 'aapt2' -type f 2>/dev/null); do
      if file "$f" | grep -q "ELF.*interpreter /lib64"; then
        patchelf --set-interpreter "$(cat ${pkgs.stdenv.cc}/nix-support/dynamic-linker)" "$f" 2>/dev/null || true
      fi
    done

    echo "PAI Android dev shell"
    echo "  Android SDK: $ANDROID_HOME"
    echo "  Java: $(java -version 2>&1 | head -1)"
    echo "  Gradle: $(gradle --version 2>&1 | grep Gradle | head -1)"
    echo ""
    echo "Run: ./gradlew assembleDebug"
  '';
}
