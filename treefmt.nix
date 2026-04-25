{
  projectRootFile = "flake.nix";

  # Sorting (keep-sorted markers)
  programs.keep-sorted.enable = true;

  # Nix
  # keep-sorted start
  programs.deadnix.enable = true;
  programs.nixfmt.enable = true;
  programs.statix.enable = true;
  # keep-sorted end

  # Java / Kotlin / Gradle Kotlin DSL
  # keep-sorted start
  programs.google-java-format.enable = true;
  programs.ktlint.enable = true;
  # keep-sorted end

  # XML (AndroidManifest, resources)
  programs.xmllint.enable = true;

  # Markdown / JSON / YAML etc.
  programs.prettier.enable = true;

  settings.excludes = [
    # keep-sorted start
    ".direnv"
    ".gradle"
    "app/build"
    "build"
    "result"
    # keep-sorted end
  ];
}
