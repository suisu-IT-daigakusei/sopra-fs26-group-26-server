{
  description = "Cabo server development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachSystem ["aarch64-darwin" "x86_64-darwin" "x86_64-linux" "aarch64-linux"] (
      system: let
        overlays = [
          (self: super: {
            jdk = super.jdk25;
          })
        ];

        inherit (nixpkgs) lib;
        pkgs = import nixpkgs {
          inherit system;
          overlays = overlays;
        };

        nativeBuildInputs = with pkgs;
          [
            jdk
            git
          ]
          ++ lib.optionals (system == "aarch64-linux") [
            qemu
          ];
      in {
        devShells.default = pkgs.mkShell {
          inherit nativeBuildInputs;

          shellHook = ''
            export HOST_PROJECT_PATH="$(pwd)"
            export COMPOSE_PROJECT_NAME=cabo-server
            
            export PATH="${pkgs.jdk}/bin:$PATH"
            export PATH="${pkgs.git}/bin:$PATH"
            
          '';
        };
      }
    );
}
