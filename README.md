# Eclipse ACP

An Eclipse IDE client for the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/).
The first supported agent is [Mistral Vibe](https://docs.mistral.ai/vibe/code/use-vibe-in-other-ides), launched locally through `vibe-acp`.

## Status

Early MVP. The plug-in currently provides:

- an **ACP Chat** view;
- launch of a configurable ACP agent over stdio;
- ACP v1 initialization and session creation;
- streamed agent text in the Eclipse view;
- project-aware working directory selection;
- configurable command and arguments for `vibe-acp`.

File-system callbacks, terminal integration, permission dialogs, rich diffs and ACP v2 are planned next.

## Requirements

- Eclipse IDE 2026-06 or newer;
- Java 21;
- Maven 3.9+ for command-line builds;
- an installed and configured ACP agent, for example `vibe-acp`.

Test Vibe before starting Eclipse:

```shell
vibe-acp --help
```

## Build

```shell
mvn clean verify
```

The p2 update site is generated in:

```text
releng/dev.eclipseacp.repository/target/repository
```

## Run from Eclipse

1. Import the repository as **Existing Maven Projects**.
2. Set `releng/dev.eclipseacp.target/dev.eclipseacp.target.target` as the active target platform.
3. Launch an **Eclipse Application** containing `dev.eclipseacp.client`.
4. Open **Window → Show View → Other… → ACP → ACP Chat**.
5. Configure the agent under **Window → Preferences → ACP** if `vibe-acp` is not on Eclipse's `PATH`.

## Architecture

```text
Eclipse ACP Chat
      |
      | ACP / JSON-RPC 2.0, newline-delimited stdio
      v
  vibe-acp
      |
      v
Mistral model/provider
```

## Security

The plug-in does not store Mistral API keys. Authentication remains owned by the selected ACP agent.
The MVP deliberately advertises no Eclipse file-system or terminal capabilities; those capabilities will be added together with explicit user approval flows.

## License

Apache-2.0.
