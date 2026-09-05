# Analisi delle feature mancanti

Analisi dello stato corrente della working tree, incluse le modifiche non ancora committate. Per “plugin AI classici” si intendono strumenti come Copilot Chat, Continue, Cline e gli assistenti integrati negli IDE JetBrains/VS Code.

Il progetto è un buon MVP di chat ACP, ma oggi sfrutta solo una piccola parte del protocollo e quasi nulla dell’integrazione profonda disponibile in Eclipse.

## Sintesi

| Area | Stato attuale | Gap |
|---|---|---|
| Chat testuale streaming | Presente | UX ancora essenziale |
| Provider ACP locali | Presente | Configurazione limitata |
| Permessi | Parziale | Mancano dettagli, policy e persistenza |
| Sessioni | Solo nuove, in memoria | Mancano list/load/resume/delete/persistenza |
| Tool call, diff e plan | Solo status testuale | Manca una rappresentazione strutturata |
| Filesystem/terminal ACP v1 | Assenti | Non vengono pubblicizzati né implementati |
| ACP v2 | Assente | Serve un adapter separato |
| Integrazione editor Eclipse | Quasi assente | Mancano contesto, diff, quick action, marker |
| Test | Nessun test individuato | Alto rischio di regressione protocollare |

## P0 — Correttezza ACP

### Negoziazione delle capability

`AgentCapabilities` contiene `permissions`, `modes`, `fileSystem` e `terminal`, e il parser cerca questi campi dentro `agentCapabilities`.

- `session/request_permission` è una funzionalità base del client, non una capability dell’agent.
- Filesystem e terminal sono capability offerte dal client in ACP v1.
- Mode/config option sono stato della sessione, non booleani dell’agent.
- Le capability dell’agent includono invece `loadSession`, prompt multimediali, MCP, auth e lifecycle delle sessioni.

Riferimenti: [AcpClient.java](plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/AcpClient.java), [AgentCapabilities.java](plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/agent/AgentCapabilities.java).

### Chiusura senza `session/close`

Il pulsante Close distrugge direttamente il processo. Se l’agent supporta `session/close`, dovrebbe prima essere chiamato il metodo ACP per liberare risorse e consentire la persistenza ordinata della sessione.

Riferimento: [AcpChatView.java](plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpChatView.java).

### Update ACP quasi completamente scartati

L’handler mostra realmente solo `agent_message_chunk`; thought, tool call e plan diventano testo nella status bar. Mancano:

- `user_message_chunk`;
- tool call e tool call update completi;
- contenuti, location, diff, stato e risultati delle tool call;
- plan con entry, priorità e stato;
- `available_commands_update`, `current_mode_update`, `config_option_update`, `session_info_update`, `usage_update`;
- metadati ed estensioni `_meta`.

### Bootstrap Markdown come prompt nascosto

Dopo `session/new` viene inviato un prompt nascosto per imporre GFM. Non è un vero messaggio system in ACP v1 e altera cronologia e contesto, consuma token e potrebbe attivare tool o permission request. Meglio renderlo opzionale o affidare il formato alla UI.

### Trasporto JSON-RPC fragile

Mancano batch JSON-RPC, timeout, `$/cancel_request`, validazione dell’envelope, result `null`, classificazione corretta degli errori, monitoraggio dell’exit del processo, shutdown con timeout e limiti di dimensione. Gli unsupported method vengono oggi trasformati in `-32603`, ma dovrebbero restituire `-32601`.

Riferimento: [JsonRpcConnection.java](plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/JsonRpcConnection.java).

## P1 — Feature ACP v1 mancanti

La superficie completa è descritta nella [overview ACP v1](https://agentclientprotocol.com/protocol/v1/overview).

### Sessioni persistenti

- `session/list`, `session/load`, `session/resume`, `session/close`, `session/delete`;
- salvataggio di session ID, provider, cwd e transcript;
- recupero dopo il riavvio Eclipse e replay corretto della storia;
- gestione dei workspace root aggiuntivi.

### Autenticazione

- Lettura e visualizzazione di `authMethods`;
- `authenticate`, terminal authentication e `logout`;
- stato “authentication required” e retry dopo login.

### Contenuti e allegati

- Immagini, audio, resource link e risorse embedded;
- drag-and-drop e allegati dal workspace;
- preview sicure e annotazioni/metadati.

### MCP

`mcpServers` è sempre vuoto. Mancano configurazione per provider/progetto, stdio e HTTP, gestione sicura di environment/header, validazione capability e un MCP server Eclipse per buffer editor, workspace, marker e comandi IDE.

### Filesystem e terminale ACP v1

- `fs/read_text_file` e `fs/write_text_file`, anche su buffer non salvati;
- `terminal/create`, `terminal/output`, `terminal/wait_for_exit`, `terminal/kill`, `terminal/release`;
- integrazione con workspace, local history, undo, console e permission policy.

### Mode, configurazioni e slash command

- `session/set_mode` e visualizzazione dello stato;
- config option generiche per modello e livello di reasoning;
- `available_commands_update`, completamento `/` e input guidato;
- `elicitation/create` per form e flussi URL.

### Permission UX completa

Servono descrizione, comando, cwd, file e diff coinvolti; policy persistenti per progetto/provider/tool, revoca delle policy e audit log. Il dialogo attuale mostra principalmente titolo e opzioni.

## P1 — ACP v2

ACP v2 richiede un adapter separato e negoziato per connessione; non basta impostare `protocolVersion` a 2. Vedi la [guida di migrazione ACP v2](https://agentclientprotocol.com/protocol/v2/migration).

Mancano:

- negoziazione v1/v2 e formati v2 `info`/`capabilities`;
- `auth/login` e `auth/logout`;
- baseline `session/list`, `session/resume`, `session/close`;
- lifecycle asincrono con `state_update` (`running`, `idle`, `requires_action`);
- messaggi per `messageId` e semantica upsert/patch;
- `tool_call_content_chunk`, terminale agent-owned e diff v2;
- plan per `planId` e config option per mode/modello/reasoning;
- enum aperti, fallback per varianti future e batch JSON-RPC;
- MCP v2 con discriminatore `type` e replay via `session/resume.replayFrom`.

## P1 — Gap rispetto agli assistenti AI classici

### Editor e contesto Eclipse

- Invio di selezione, file attivo, classe/metodo e cartelle;
- riferimenti `@file`, `@folder`, `@selection`, `@problems`;
- azioni Explain/Fix/Refactor/Test da editor e menu contestuali;
- quick assist, code action e completamento inline;
- navigazione chat → file/riga;
- Java model, symbol, references, hierarchy e progetti dipendenti.

### Review e applicazione modifiche

- Diff per file e hunk;
- accept/reject selettivo;
- Compare Editor;
- applicazione mediante workspace API;
- undo, local history e gestione dei dirty editor;
- create/rename/delete, refresh e riepilogo dei file modificati.

### Diagnostica e build

- Problem marker e diagnostics;
- Console, stack trace, build e test failure;
- contesto Maven/Gradle/PDE;
- azioni “fix this error” da Problems e Console.

### UX chat

- Messaggi strutturati, retry, edit e regenerate;
- copy button per codice e ricerca;
- cronologia, rinomina sessioni e sessioni in background;
- usage/costi, tool call espandibili e plan checklist;
- scelta provider/modello per sessione;
- tema, accessibilità, localizzazione e link Eclipse sicuri.

### Configurazione provider

La pagina espone solo nome, comando e argomenti. Mancano executable discovery, Test connection, environment, timeout, PATH, preset agent, import/export, scope progetto, secure storage, stato auth, MCP per provider, preferenza di versione ACP e capability inspector.

Riferimento: [AcpPreferencePage.java](plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/preferences/AcpPreferencePage.java).

## P2 — Qualità da plugin Eclipse maturo

- Test unitari per parsing, JSON-RPC e capability;
- test PDE/SWTBot e fixture con agent diversi;
- modelli/schema ACP generati e versionati;
- extension point e servizi OSGi separati dalla UI;
- Eclipse Job con progress/cancellation;
- preference/project property page, key binding e toolbar;
- help context, i18n, icone e release notes;
- redazione dei dati sensibili nei log;
- CI multipiattaforma, firma e metadata p2 completi.

## Roadmap consigliata

1. Correttezza ACP v1: capability tipizzate, auth, session lifecycle e update completi.
2. Verticale “tool call → diff Eclipse → permission → apply/reject → undo”.
3. Persistenza: session list/load/resume, cronologia e ripristino dopo restart.
4. Contesto Eclipse: editor selection, Problems, Console e Java model.
5. MCP: configurazione server e bridge Eclipse.
6. ACP v2 affiancato a v1 mediante adapter separato.
7. UX: config option, slash command, usage, elicitation, terminal display e allegati.
8. Hardening: test di protocollo, timeout, process lifecycle, secure storage, accessibilità e CI.
