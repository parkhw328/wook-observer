# Repository Guidelines

## Project Structure & Module Organization

`wook-observer` is currently a minimal scaffold containing two empty directories:

- `resources/`: use for project assets and supporting files.
- `rules/`: use for project-specific development instructions; read applicable rules before making changes.

No application source or tests exist yet. When adding the first implementation, establish clear source and test directories and document their layout in `README.md`. Keep generated output separate from maintained files.

## Build, Test, and Development Commands

No dependency manifest, build scripts, or local development commands are configured yet.

- `rg --files --hidden -g '!.git'`: list project files, including hidden configuration.
- `ls -la rules resources`: inspect the existing scaffold directories.
- After Git is initialized, `git diff --check`: check tracked changes for whitespace errors.

When introducing a toolchain, document exact dependency installation, local execution, build, and test commands in `README.md`, and update this guide.

## Coding Style & Naming Conventions

No language-specific style, formatter, or linter is configured. Introduce shared formatting settings alongside the first source code and use consistent indentation within each file.

Use descriptive, lowercase, hyphen-separated names for new documentation and resource files, preserving standard names such as `README.md` and `AGENTS.md`. Write Markdown with ATX headings (`## Section`), language-tagged code fences, and relative links to repository files.

## Testing Guidelines

No testing framework or coverage threshold exists. Add an appropriate test runner with executable functionality, document test locations and naming conventions, and include focused regression coverage for behavior changes.

For documentation changes, verify paths, command accuracy, links, and whitespace. Record checks performed and any validation limitations.

## Commit & Pull Request Guidelines

This directory has no Git metadata, so historical commit conventions cannot be established. Once version control is configured, use concise, imperative subjects, such as `docs: describe local setup`, and keep commits focused.

Pull requests should explain the purpose and resulting behavior, link relevant issues, list verification performed, and identify configuration changes. Include screenshots when a change affects a visual interface.

## Security & Configuration

Keep credentials, local environment files, logs, and generated artifacts out of version control. Provide sanitized configuration examples when setup requires environment-specific values.
