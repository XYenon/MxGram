{
  description = "MxGram development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-parts,
      ...
    }@inputs:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        # keep-sorted start
        inputs.treefmt-nix.flakeModule
        # keep-sorted end
      ];

      systems = import inputs.systems;

      perSystem =
        { system, ... }:
        {
          imports = [
            {
              _module.args.pkgs = import nixpkgs {
                inherit system;
                config = {
                  allowUnfree = true;
                  android_sdk.accept_license = true;
                };
              };
            }

            (
              { pkgs, config, ... }:
              let
                jdk = pkgs.jdk21;
                android = rec {
                  platformVersion = "35";

                  buildToolsVersion = "36.0.0";
                  buildToolsVersions = [
                    "35.0.0"
                    buildToolsVersion
                  ];

                  platformToolsVersion = "35.0.2";
                  cmdLineToolsVersion = "13.0";
                };

                androidComposition = pkgs.androidenv.composeAndroidPackages {
                  inherit (android) platformToolsVersion cmdLineToolsVersion buildToolsVersions;
                  platformVersions = [ android.platformVersion ];
                  includeEmulator = false;
                  includeSystemImages = false;
                  includeNDK = false;
                  includeSources = false;
                };

                androidSdk = androidComposition.androidsdk;
                androidSdkRoot = "${androidSdk}/libexec/android-sdk";

                aapt2FromMavenOverride = "${androidSdkRoot}/build-tools/${android.buildToolsVersion}/aapt2";

                python3WithLottie = pkgs.python3.withPackages (ps: with ps; [ lottie ]);
              in
              {
                treefmt = import ./treefmt.nix;

                formatter = config.treefmt.build.wrapper;
                checks.formatting = config.treefmt.build.check self;

                devShells.default = pkgs.mkShell {
                  packages = with pkgs; [
                    # keep-sorted start
                    androidSdk
                    config.treefmt.build.wrapper
                    git
                    imagemagick
                    jdk
                    librsvg
                    python3WithLottie
                    unzip
                    which
                    zip
                    # keep-sorted end
                  ];

                  env = {
                    # keep-sorted start
                    ANDROID_HOME = androidSdkRoot;
                    ANDROID_SDK_ROOT = androidSdkRoot;
                    GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2FromMavenOverride}";
                    JAVA_HOME = jdk.home;
                    # keep-sorted end
                  };

                  shellHook = ''
                    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

                    if [[ $- == *i* ]]; then
                      echo "MxGram dev shell ready"
                      echo "JAVA_HOME=$JAVA_HOME"
                      echo "ANDROID_HOME=$ANDROID_HOME"
                    fi
                  '';
                };
              }
            )
          ];
        };
    };
}
