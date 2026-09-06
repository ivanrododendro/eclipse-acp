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
- explicit dialogs for ACP permission requests;
- configurable command and arguments for multiple ACP agents/providers;
- provider list management (add, edit, remove, select active) persisted in Eclipse preferences;
- capability negotiation at connection time, with optional features ignored when not advertised.
- safe CommonMark rendering with GFM tables in the chat transcript;
- a session bootstrap instruction asking agents to format answers as GitHub Flavored Markdown.

File-system callbacks, terminal integration, rich diffs and ACP v2 are planned next. ACP details are isolated behind the agent client abstraction so these additions do not require UI/session changes.

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
# Maven 3.9+ is required. On this workstation, mvnd provides Maven 3.9.14.
mvnd clean verify
```

With another Maven 3.9+ installation, the equivalent command is `mvn clean verify`.

The p2 update site is generated in:

```text
releng/dev.eclipseacp.repository/target/repository
```

## Installing safely

The update site contains only the Eclipse ACP feature and bundle; it never embeds or
updates Eclipse Platform, ASM, Aries, m2e, Jetty, or any other third-party bundle.
The host Eclipse installation supplies the platform APIs required by ACP.

When installing from **Help → Install New Software…**, keep **Contact all update
sites during install to find required software** disabled: on Eclipse 2026-06 the
required platform APIs are already present, and this prevents the installation from
reconciling unrelated update sites.

## Run from Eclipse

1. Import the repository as **Existing Maven Projects**.
2. Set `releng/dev.eclipseacp.target/dev.eclipseacp.target.target` as the active target platform.
3. Launch an **Eclipse Application** containing `dev.eclipseacp.client`.
4. Configure providers under **Window → Preferences → ACP**. The original Vibe settings are migrated automatically as the `vibe` provider.
5. In Project Explorer, right-click a project or any child resource and choose **ACP → Open session**. The session always uses the owning project directory. Use `Ctrl+Alt+O` (`⌘⌥O` on macOS) to open a session for the selected project or active editor's project.
6. The selector at the top-right contains projects opened during the current view lifetime; it is empty after restarting Eclipse. Opening chat for a project already in the selector selects that project's active session. The **+** button closes and persists the current ACP session, then starts a fresh one for the same project. **History** lists and restores ACP sessions whose working directory is that project.

## Architecture

```text
Eclipse ACP Chat
      |
      | ACP / JSON-RPC 2.0, newline-delimited stdio
      v
  selected ACP provider (for example `vibe-acp`)
      |
      v
Mistral model/provider
```

## Security

The plug-in does not store Mistral API keys. Authentication remains owned by the selected ACP agent.
The MVP deliberately advertises no Eclipse file-system or terminal capabilities. Agent permission requests are always presented to the user, with rejection selected by default.

ACP v1 has no standard `system` message role. The client therefore sends the Markdown directive as an initial, hidden session instruction after `session/new`, without adding provider-specific protocol fields.

## License

Apache-2.0.
