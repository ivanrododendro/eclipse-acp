# Eclipse ACP Connector

An [Agent Client Protocol (ACP)](https://agentclientprotocol.com/) client for Eclipse IDE. It opens a chat for an Eclipse project and runs a configurable local ACP agent over stdio. [Cline CLI](https://github.com/cline/cline/blob/main/docs/usage/acp.mdx) is one supported example.

## Features

- **Project chats:** choose **ACP → Open ACP Session** from a project or resource context menu, or press `Ctrl+Alt+O` (`⌘⌥O` on macOS) with a project selected or its editor active. The agent uses the project directory as its working directory. Switch between projects in the view, start a new chat, or stop a response.
- **Conversations:** streamed responses, session history, and restoration for agents that support `session/list` and either `session/load` or `session/resume`. The transcript renders CommonMark and GFM tables; file references within the project can open in an Eclipse editor.
- **Transcript controls:** the view shows agent status and can show tool calls, terminal output, change previews, and usage data when enabled in preferences. Select text in the transcript and use **Copy and Paste in a New Session** to place it in a new chat's prompt. Debug logging of ACP messages is also available in preferences and is off by default.
- **Agents and options:** manage multiple providers under **Window → Preferences → ACP Connector** and choose a default. When the agent offers them, the view displays session mode, model, and reasoning level selectors, with other settings under **Options…**.
- **Interaction and files:** permission requests, authentication, and agent input requests appear in dialogs. 

Optional features depend on the capabilities advertised by the agent.

## Install in Eclipse

Requires Eclipse IDE 2026-06 or newer, Java 21, and an ACP agent installed and authenticated separately. For example, install Cline CLI with `npm i -g cline` and check that `cline --help` works. Cline runs in ACP mode with `cline --acp`.

1. In Eclipse, open **Help → Eclipse Marketplace…**, search for **Eclipse ACP Connector**, select **Install**, and complete the wizard. Restart Eclipse if prompted.
2. Open **Window → Preferences → ACP Connector**. Add a provider named **Cline** with command `cline` and arguments `--acp`, then set it as the default. If Eclipse cannot find the command, use its absolute path. You can configure other ACP agents in the same way.
3. In **Project Explorer**, right-click an open project and choose **ACP → Open ACP Session**. Enter a prompt in the **ACP Chat** view and send it with **Send** or `Ctrl+Enter` (`⌘+Enter` on macOS).

If the listing does not appear in Marketplace, install from the p2 update site via **Help → Install New Software… → Add…**. Enter `https://ivanrododendro.github.io/eclipse-acp/` and select **Eclipse ACP Connector**. Leave **Contact all update sites during install to find required software** unchecked. The update site is published with GitHub releases; if it is unavailable, see the release assets for the p2 archive.

The selected agent handles authentication and API keys. Permission requests are shown to the user, with rejection selected by default when the agent offers that option.

## Build and development

Requires JDK 21 and Maven 3.9 or newer. The Tycho build uses Eclipse 2026-06 as its target platform:

```sh
mvn clean verify
```

The generated p2 repository is in `releng/dev.eclipseacp.repository/target/repository`. To run the plugin from Eclipse, import the repository as **Existing Maven Projects**, activate `releng/dev.eclipseacp.target/dev.eclipseacp.target.target` as the target platform, and launch an **Eclipse Application** configuration that includes `dev.eclipseacp.client`.

The [release workflow](.github/workflows/release.yml) builds and checks the p2 repository, attaches a ZIP and SHA-256 checksum to the GitHub Release, and publishes the update site to GitHub Pages. The repository contains only the Eclipse ACP feature and bundle; the host Eclipse installation supplies its platform dependencies.

## Architecture

```text
Eclipse commands / AcpChatView (SWT composition and change-review controls)
                  │
                  ├─ ChatComposer / ChatTranscript → SWT editor / browser
                  ├─ AcpChatDialogs → modal interactions
                  ├─ AcpSessionService → AgentProviderRegistry → Eclipse preferences
                  ├─ ChangeReviewService → WorkspaceDiffApplier → Eclipse project
                  └─ AcpChatSessionListener → ChatSessionModel
                         │
                    AgentClient / AgentListener (protocol-neutral interfaces and models)
                         │
                    AcpClient (ACP v1 adapter)
                         │
                    JsonRpcConnection → stdio → ACP agent process
```

`plugins/dev.eclipseacp.client` contains the UI, session services, agent model, and ACP adapter. `AgentClient` and `AgentListener` keep protocol messages out of the UI; `AcpClient` negotiates capabilities, manages sessions, and translates ACP events. Process launch and JSON-RPC 2.0 transport are separate and injectable. `ChangeReviewService` and `WorkspaceDiffApplier` mediate project file operations. `features/` defines the installable feature, `releng/` the target platform and p2 repository, and `plugins/dev.eclipseacp.client.tests` the tests. See the [detailed component diagram](docs/architecture-plugin.puml).

`AcpChatView` composes the UI and presents the selected project. `ChatComposer` owns prompt editing and option selectors; `ChatTranscript` owns HTML rendering, file navigation, and text selection. `AcpSessionService` owns open chats, connection reuse, restoration, prompts, and configuration changes. `AcpChatSessionListener` batches agent events and updates `ChatSessionModel`; dialogs and asynchronous workspace operations stay in their dedicated services.

## License

Apache-2.0. See [LICENSE](LICENSE).
