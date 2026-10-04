# Eclipse ACP — code review estesa e roadmap del debito tecnico

Data: 3 ottobre 2026. Baseline: `e1772a2d0b6bca83df5201659b1ba139cbdfea81`.

Aggiornamento del 4 ottobre 2026: rimossi i rilievi e le attività relativi alla gestione delle revisioni dei file eliminata dal plugin. Gli ID dei rilievi restanti sono conservati; le verifiche storiche riportate sotto si riferiscono alla baseline originale. Le evidenze aggiornate sul codice corrente sono indicate esplicitamente.

## 1. Valutazione complessiva

Il progetto ha una buona base di separazione tra interfaccia, sessioni, modello dell'agente, adattatore ACP e trasporto. Le estrazioni già effettuate hanno migliorato la testabilità: factory e launcher sono sostituibili, diversi callback sono tipizzati e i test coprono transizioni di sessione e ordinamento degli eventi.

Il debito prioritario riguarda però la correttezza delle modifiche ai file e il comportamento sotto concorrenza o guasto. In particolare, una notifica informativa di un tool può attivare una scrittura e la validazione dei file precede l'acquisizione della scheduling rule. La presenza di `CompletableFuture` non rende automaticamente asincroni l'avvio del processo e le scritture sul trasporto.

Raccomandazione: trattare prima i rilievi P1, introdurre test Eclipse sui percorsi di scrittura e disconnessione, poi completare il consolidamento SOLID. Una riscrittura generale o una suddivisione immediata in molti bundle non è necessaria.

### Priorità

| Livello | Criterio |
| --- | --- |
| P1 — alta | Possibili modifiche indesiderate, perdita di aggiornamenti, esposizione di contenuti o blocco dell'IDE/sessione. Da affrontare prima di ampliare le funzionalità. |
| P2 — media | Difetti funzionali, integrazione Eclipse incompleta e debito che rende costosi manutenzione e rilascio. |
| P3 — bassa | Coerenza, documentazione e miglioramenti circoscritti. |

Non sono assegnati P0: mancano evidenze di un problema universale e immediato tale da giustificare quella classificazione. P1 non significa che lo scenario sia già stato osservato in produzione.

## 2. Perimetro, metodo e verifiche

Esaminati i percorsi principali dei package `acp`, `agent`, `ui`, `preferences`, `mcp`, il manifest OSGi, `plugin.xml`, POM, target platform, feature, repository p2, workflow CI/release e documentazione architetturale. L'inventario contiene 62 file Java di produzione, 5.632 righe, e 8 file di test, 1.027 righe. Sono conteggi fisici, non misure di complessità o copertura.

L'analisi combina lettura del codice, ricostruzione dei flussi tra chiamanti e chiamati, consultazione di fonti primarie e verifiche locali. Le linee indicate si riferiscono alla baseline; i link relativi aprono i file nel repository.

| Verifica | Esito e limiti |
| --- | --- |
| Compilazione diretta | Tutti i 70 sorgenti Java compilano con `javac --release 21` e i bundle p2 già presenti nella cache locale. |
| Suite esistente | `JUnitCore` 4.13.2: **43 test superati**, zero errori/fallimenti nelle esecuzioni effettuate. Classpath Java ordinario, senza avvio Equinox/SWT. |
| Build ufficiale | Tentato `mvn --offline --batch-mode --no-transfer-progress verify` usando Maven 3.9.9. Interrotta prima dei test con `lock timeout` sul file `.m2/repository/.meta/p2-artifacts.properties.tycholock`. Build Tycho e p2 **non verificati**. Il timeout non dimostra un difetto del progetto; la causa del lock non è stata accertata. |
| Probe del renderer | Con il renderer personalizzato dei link, un URL `javascript:` e un'immagine HTTPS esterna rimangono nell'HTML generato. Non è stata eseguita una prova di sfruttamento nel browser nativo. |
| Probe EOF del trasporto | Dopo EOF del reader: `closed=false`, callback di errore invocato zero volte. La prova della richiesta successiva si interrompe nell'accesso alle preferenze Eclipse fuori da OSGi; l'attesa indefinita della richiesta successiva resta dedotta dal codice. |
| Probe argomenti | `parseArguments("--name \"\" next")` restituisce `[--name, next]`: l'argomento vuoto è perso. |
| Ispezione runtime Eclipse | Non eseguita: non sono certificati installazione p2, UI nativa, accessibilità, race e comportamento dei processi su Windows/Linux/macOS. |

La verifica alternativa ha usato una directory temporanea, `/private/tmp/eclipse-acp-review.2OB6EP`, senza modificare sorgenti o test del repository. La compilazione su classpath non verifica dichiarazioni OSGi, wiring dei bundle o extension point. Non è stata misurata la copertura: 43 test verdi non equivalgono a copertura dei percorsi critici.

## 3. Best practice adottate e fonti

Fonti consultate il 3 ottobre 2026. La target del progetto è Eclipse **2026-06**; le pagine `latest` descrivono la documentazione pubblicata al momento della consultazione. Prima di usare nuove API, verificarne la disponibilità nella target minima. Le Eclipse UI Guidelines sono esplicitamente una bozza mantenuta dalla comunità; qui sono usate come guida progettuale.

| Area | Pratica applicabile | Fonte primaria |
| --- | --- | --- |
| Thread UI | Tenere breve il lavoro sul thread SWT; spostare I/O e operazioni lunghe in background; accedere ai widget sul thread UI e controllarne il ciclo di vita. | [Eclipse — Threading issues](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_threading.htm) |
| Lavoro asincrono | Usare Job e progress monitor per lavoro integrato nel workbench; assegnare proprietà, cancellazione e ownership alle attività; completare il cleanup allo shutdown. | [Eclipse — Concurrency infrastructure](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/runtime_jobs.htm), [Workbench concurrency](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/workbench_jobs.htm) |
| Workspace | Raggruppare modifiche con `IWorkspaceRunnable`/`WorkspaceJob` e scheduling rule appropriate. Il batching non è una transazione con rollback automatico. | [Eclipse — Concurrency and the workspace](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/resAdv_concurrency.htm), [Batching resource changes](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/resAdv_batching.htm) |
| File | Usare le API workspace, rispettare charset e stato delle risorse, progettare conflitti e conservazione della storia. | [Eclipse — IFile](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/resources/IFile.html) |
| Risorse SWT | Definire chi possiede immagini, font, browser bridge e listener; rilasciare le risorse create senza distruggere quelle condivise dalla piattaforma. | [Eclipse — Widgets / Resource disposal](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_widgets.htm) |
| UX Eclipse | Preferire comportamenti coerenti con workbench, tastiera, temi, dialog di preferenze e segnalazione degli errori. | [Eclipse UI Guidelines](https://eclipse-platform.github.io/ui-best-practices/) |
| OSGi | Dichiarare le dipendenze realmente usate e intervalli di compatibilità verificati; evitare di affidarsi alla visibilità accidentale delle classi. `Import-Package` riduce l'accoppiamento quando applicabile. | [OSGi Core 8 — Module Layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html) |
| API e compatibilità | Usare API pubbliche; introdurre controlli PDE su uso, compatibilità e versionamento quando si pubblica un'API. | [PDE — API Tools options](https://help.eclipse.org/latest/topic/org.eclipse.pde.doc.user/tasks/api_tooling_options.htm) |
| Test di plugin | Affiancare ai test Java un harness Eclipse/OSGi per verificare ciò che dipende dalla piattaforma. | [Tycho Surefire](https://tycho.eclipseprojects.io/doc/latest/tycho-surefire-plugin/plugin-info.html) |
| SOLID | Separare ragioni di cambiamento, dipendere da contratti stabili, evitare default che alterano silenziosamente il significato di un'operazione. | [Robert C. Martin — Principles of OOD](https://butunclebob.com/ArticleS.UncleBob.PrinciplesOfOod) |
| Markdown non fidato | Distinguere escaping HTML da sanitizzazione URL e applicare la policy anche ai renderer personalizzati. | [commonmark-java — Usage](https://github.com/commonmark/commonmark-java#usage) |
| Protocollo | Distinguere notifiche di tool, permessi e richieste di scrittura; mantenere coerenti successo, effetti e stato di sessione. | [ACP v1 — Tool calls](https://agentclientprotocol.com/protocol/v1/tool-calls), [ACP v1 — File system](https://agentclientprotocol.com/protocol/v1/file-system), [JSON-RPC 2.0](https://www.jsonrpc.org/specification) |

Queste pratiche sono criteri di valutazione. Non implicano che ogni plugin debba avere un activator, usare dependency injection tramite framework o esporre extension point propri.

## 4. Rilievi prioritizzati

### R01 — P1: notifiche di tool e richieste di permesso possono causare scritture

**Evidenza statica, confidenza alta.** [AcpChatSessionListener.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpChatSessionListener.java), linee 125–146; [AcpClient.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/AcpClient.java), linee 678–687.

`onToolCall` chiama `applyAsync(toolCall.diffs())`, senza distinguere stato `pending`, `failed`, notifica di avanzamento o scrittura richiesta. Anche la gestione di `session/request_permission` pubblica `onToolCall` prima di richiedere la decisione all'utente.

**Scenario:** una richiesta di permesso contiene un diff applicabile. La callback può avviare la modifica prima della scelta nel dialog. Un successivo rifiuto non annulla quella scrittura. La ripetizione di notifiche può inoltre riapplicare gli stessi diff e interferire con altre modifiche.

Le notifiche ACP descrivono operazioni eseguite dall'agente; la richiesta filesystem è un percorso distinto. [ACP — Tool calls](https://agentclientprotocol.com/protocol/v1/tool-calls).

**Intervento:** rendere informativa la gestione dei tool; eseguire scritture solo tramite il flusso filesystem. Distinguere le scritture mediate dalle modifiche già effettuate dall'agente.

**Accettazione:** notifiche `pending`, `failed`, duplicate e una richiesta di permesso rifiutata non modificano alcun byte del workspace.

### R02 — P1: URL e navigazione del transcript non sono confinati

**Confermato sul rendering; impatto nel browser da verificare.** [GfmRenderer.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/GfmRenderer.java), linee 89–94 e 153–165; [ChatTranscript.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/ChatTranscript.java), linee 79–93.

Il renderer abilita `escapeHtml(true)` ma non una policy URL. Il renderer personalizzato di `Link` scrive direttamente la destinazione quando non è un riferimento workspace. Il listener di navigazione blocca solo i link interni riconosciuti; le altre destinazioni possono essere caricate nel browser del transcript. Le immagini remote restano presenti nell'HTML.

Il probe con `[link](javascript:alert%281%29)` mantiene `href="javascript:..."`; un'immagine HTTPS mantiene il proprio `src`. Non è provata esecuzione JavaScript o esfiltrazione su tutti i backend SWT, ma la barriera applicativa manca. L'escaping HTML da solo non filtra URL pericolosi. [commonmark-java](https://github.com/commonmark/commonmark-java#usage).

**Intervento:** allowlist degli schemi, controllo nel renderer personalizzato, blocco della navigazione del browser verso documenti esterni; apertura dei link HTTP(S) consentiti nel browser di sistema. Definire una policy per immagini remote e bridge JavaScript; valutare CSP compatibile con il backend.

**Accettazione:** test per `javascript:`, `data:`, `file:`, schemi sconosciuti e immagini esterne; test UI che il transcript resti nel documento applicativo. Attivare solo `sanitizeUrls(true)` non coprirebbe automaticamente il renderer personalizzato.

### R04 — P1: verifica e scrittura non formano un'operazione protetta unica

**Evidenza statica, confidenza alta; race da riprodurre in PDE.** [WorkspaceDiffApplier.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceDiffApplier.java), metodo `apply` (codice corrente, 4 ottobre 2026).

`apply` legge e confronta i contenuti prima di entrare in `workspace.run`. Un altro job può cambiare il file tra le due fasi. Il successivo `setContents(..., FORCE, ...)` non ripete la verifica e può sovrascrivere un aggiornamento valido. `synchronized` protegge solo l'istanza dell'applier, non gli altri plugin o altre sessioni.

Inoltre, se fallisce il secondo file di un batch, il primo può essere già cambiato. `workspace.run` raggruppa e coordina modifiche, ma non offre un rollback automatico. [Eclipse — Batching](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/resAdv_batching.htm).

**Intervento:** acquisire la rule prima della verifica definitiva; controllare contenuto/versione immediatamente prima della scrittura, definire recupero dei batch parziali e registrare gli effetti realmente eseguiti. Integrare la gestione dei buffer editor. Non pretendere che una rule blocchi processi esterni a Eclipse.

**Accettazione:** modifica concorrente tra anteprima e apply senza perdita di dati; errore sul secondo file con ripristino o recupero documentato.

### R05 — P1: il controllo del percorso non considera la destinazione fisica

**Evidenza statica, confidenza alta; scenario linked resource/symlink da verificare nel workspace.** [WorkspaceDiffApplier.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceDiffApplier.java), linee 120–142.

La verifica usa `toAbsolutePath().normalize().startsWith(root)`, poi risolve un `IFile`. Un percorso lessicale interno al progetto può essere una linked resource Eclipse o attraversare un symlink diretto fuori dalla root. Il controllo non esamina `getLocationURI`, destinazioni reali o antenati delle risorse nuove.

**Impatto:** una policy presentata come accesso limitato al progetto può leggere o modificare file esterni attraverso richieste mediate. Questo non significa che il processo agente sia sandboxato: essendo un processo locale, possiede comunque i permessi del sistema operativo con cui viene avviato.

**Intervento:** definire se linked resource esterne siano consentite; applicare la decisione sia a letture sia a scritture e mostrarne la destinazione. Per una policy confinata, verificare root e percorsi reali, inclusi gli antenati esistenti dei nuovi file; gestire esplicitamente risorse non locali. Documentare i limiti rispetto alla sostituzione concorrente dei link.

**Accettazione:** test su `..`, prefissi simili, symlink, linked folder, nuova risorsa sotto link e percorsi Windows. Nessun accesso fuori policy.

### R06 — P1: avvio, invio e cancellazione possono bloccare il thread SWT

**Evidenza statica, confidenza alta.** [AcpSessionService.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpSessionService.java), linee 127–144, 227–265; [AcpClient.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/AcpClient.java), linee 120–149, 265–301 e 336–344; [JsonRpcConnection.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/JsonRpcConnection.java), linee 49–67 e 179–188.

Le azioni UI chiamano direttamente `connect`, `prompt`, `cancel` e le opzioni. Prima di restituire un future, il codice può avviare processi, leggere file e fare `writer.flush()`. Su Windows anche `where.exe`, `readAllBytes()` e `waitFor()` sono sincroni. Se l'agente smette di leggere stdin, la pipe può bloccare l'IDE; anche Stop usa la stessa via.

La preparazione degli allegati legge tutto il contenuto prima di verificare il limite di 10 MiB. Il pulsante allegati attuale è disabilitato, quindi questo ramo è un rischio latente; il blocco delle normali scritture RPC è già raggiungibile.

**Intervento:** executor di I/O posseduto dal servizio, coda di scrittura limitata, timeout e cancellazione; Job per le operazioni integrate nel workbench. Acquisire sul thread UI solo lo snapshot necessario, compreso il contesto dell'editor. [Eclipse — Threading](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_threading.htm).

**Accettazione:** agente che non legge stdin, risoluzione comando lenta e file grandi non impediscono di usare Eclipse; nessuna attesa di processo/pipe sul thread SWT.

### R07 — P1: accessi workbench dal worker delle operazioni file

**Evidenza statica sul codice corrente, 4 ottobre 2026; eccezioni SWT specifiche da verificare.** [WorkspaceFileService.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceFileService.java), metodi `writeAsync` e `applyAsync`; [WorkspaceDiffApplier.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceDiffApplier.java), metodi `apply` e `isDirty`.

Le scritture vengono eseguite in background tramite `CompletableFuture.supplyAsync`. Durante `apply`, `isDirty` attraversa workbench, pagine ed editor direttamente dal worker, senza un contratto di accesso confinato alla UI.

**Intervento:** gestire lo stato degli editor tramite un adapter con accesso UI e integrazione ai text file buffer. Acquisire lo snapshot necessario prima dell'operazione background e definire come rilevare modifiche successive senza introdurre attese circolari tra UI e worker.

**Accettazione:** test Eclipse con editor aperti e modificati: nessun accesso UI dal worker e nessuna sovrascrittura dei buffer non salvati; la UI continua a rispondere durante le operazioni file.

### R08 — P1: EOF ed errori del reader lasciano il trasporto apparentemente aperto

**EOF confermato con probe; conseguenze ricostruite staticamente.** [JsonRpcConnection.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/JsonRpcConnection.java), linee 84–103 e 179–188; [ChatSessionModel.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/ChatSessionModel.java), linea 97.

Su EOF vengono fallite le richieste esistenti, ma `closed` resta falso e `errorHandler` non viene chiamato. Anche l'uscita per eccezione non marca il trasporto chiuso. La UI considera connessa una sessione finché `client != null`; una terminazione dell'agente mentre la sessione è inattiva può restare invisibile.

Una richiesta successiva può essere inserita in `pending` quando non esiste più un reader: se il writer accetta ancora i dati, nessuno completa la risposta. La prova runtime di questo secondo passaggio è stata limitata dalla dipendenza del logging dalle preferenze OSGi, come riportato nel metodo.

**Intervento:** un'unica transizione terminale, idempotente e visibile tra thread, che chiuda il trasporto, fallisca le richieste e notifichi il servizio. Modellare `Connecting/Ready/Busy/Disconnected/Closing/Closed` anziché dedurre la connessione dal riferimento non nullo.

**Accettazione:** EOF prima/dopo initialize e durante inattività disconnette la UI; ogni richiesta successiva fallisce immediatamente; callback terminale una sola volta.

### R09 — P1: richieste e interazioni pendenti non hanno una politica completa di timeout/cancellazione

**Evidenza statica, confidenza alta.** [JsonRpcConnection.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/JsonRpcConnection.java), linee 29, 49–67 e 213–224; [AcpChatDialogs.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpChatDialogs.java), linee 82–129; [AcpSessionService.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpSessionService.java), linee 253–259 e 297–307.

Initialize, creazione sessione, lista e configurazione possono restare pendenti indefinitamente. Cancellare il future non rimuove l'entry da `pending`. Stop invia una notifica, ma non gestisce le interazioni UI pendenti. Un runnable scartato dopo dispose della view non completa il future creato dal dialog; una richiesta accodata per una sessione chiusa non verifica la validità della sessione prima di mostrare il dialog.

Anche la lista delle sessioni segue ricorsivamente `nextCursor` senza rilevare cursori ripetuti o fissare un limite di pagine.

**Intervento:** timeout per tipo di operazione, cancellazione per sessione e registry delle richieste/interazioni; rimozione dal pending in ogni percorso terminale; limite frame/pending/paginazione. Per prompt e autenticazione usare timeout adeguati alla durata e all'interazione umana, non una scadenza breve uniforme. I permessi di un turno cancellato devono completarsi come cancellati. [ACP — Tool calls](https://agentclientprotocol.com/protocol/v1/tool-calls).

**Accettazione:** agente silenzioso, cursore ciclico, Stop e chiusura view non lasciano future o dialog orfani; dimensioni delle code restano limitate.

### R10 — P2: letture e scritture ignorano charset e buffer non salvati

**Evidenza statica, confidenza alta.** [WorkspaceDiffApplier.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceDiffApplier.java), linee 35–43 e 135–142.

Il contenuto viene sempre decodificato/codificato come UTF-8. Un file Windows-1252 o UTF-16 può essere letto male e riscritto con byte incompatibili con il charset configurato. Le letture usano `IFile.getContents()` e non vedono modifiche non salvate dell'editor; la difesa sugli editor dirty riguarda le scritture.

Esistono anche due casi limite: `line` oltre EOF può produrre `first > last` e un'eccezione da `copyOfRange`; `first + limit` può andare in overflow. La creazione non prepara cartelle parent mancanti.

**Intervento:** rispettare `IFile.getCharset()` e una policy esplicita per nuovi file, BOM e newline; integrare i text file buffer; validare line/limit con aritmetica sicura e creare gerarchie quando previsto dal contratto. [Eclipse — IFile](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/resources/IFile.html), [ACP — File system](https://agentclientprotocol.com/protocol/v1/file-system).

**Accettazione:** round trip senza perdita di caratteri per UTF-8, Windows-1252 e UTF-16; lettura coerente del buffer dirty; casi EOF, limite massimo e file in nuova directory.

### R11 — P2: preferenze salvate prima di OK e gestione incompleta degli errori

**Evidenza statica, confidenza alta.** [AcpPreferencePage.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/preferences/AcpPreferencePage.java), linee 30–33, 60–79; [AgentProviderRegistry.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/preferences/AgentProviderRegistry.java), linee 24–53.

Add/update, selezione default e rimozione scrivono subito sullo store e chiamano `flush()`. Cancel della pagina non annulla queste modifiche, mentre i checkbox vengono salvati in `performOk`: il comportamento è incoerente. `performDefaults` ripristina solo il numero di chat recenti.

Gli handler non intercettano errori prevedibili come provider duplicato, campi vuoti o rimozione dell'ultimo provider. Un JSON corrotto viene silenziosamente sostituito dal provider di fallback durante il caricamento, con rischio di perdere la configurazione utile al recupero.

**Intervento:** modello di editing locale, validazione inline, commit su Apply/OK e rollback su Cancel; ripristino completo dei default; preservazione del JSON non valido e segnalazione recuperabile. Separare serializzazione e persistenza: `AgentProviderRegistry` riceve uno store ma forza il flush di uno scope globale.

**Accettazione:** Cancel non altera configurazioni; Defaults ripristina tutti i campi previsti; input errato non produce eccezioni non gestite; dati corrotti restano recuperabili.

### R12 — P2: la bozza del prompt non è isolata per progetto

**Evidenza statica, confidenza alta; da verificare con test UI.** [ChatComposer.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/ChatComposer.java), linee 193–216 e 241–264; [AcpChatView.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpChatView.java), linee 235–250.

Passando da una sessione all'altra, `update()` cambia `activeSession` ma non salva o sostituisce il testo del widget. `pendingInputText` copre un caso di creazione sessione, non le bozze ordinarie. Una bozza scritta per A può rimanere visibile dopo il passaggio a B e venire inviata all'agente di B. Inoltre, il testo viene svuotato prima di espansione e invio; il fallimento ripristina gli allegati, non la bozza nell'editor.

**Intervento:** bozza posseduta dalla sessione, salvataggio/ripristino esplicito alla selezione, possibilità di retry del prompt fallito. Chiarire se il contesto dell'editor attivo può appartenere a un progetto diverso da quello della chat.

**Accettazione:** A → B → A preserva due bozze distinte; fallimento prima dell'invio consente retry senza ricostruire il testo.

### R13 — P2: reflection JDT senza dipendenza OSGi dichiarata

**Evidenza statica, confidenza alta sul wiring mancante; da verificare in Equinox.** [EclipseContext.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/EclipseContext.java), linee 76–108 e 174–186; [MANIFEST.MF](../plugins/dev.eclipseacp.client/META-INF/MANIFEST.MF), linee 8–19.

`Class.forName("org.eclipse.jdt.core.JavaCore")` usa il classloader del bundle, che non dichiara una dipendenza JDT né un import di quei package. Installare JDT nell'IDE non garantisce che il bundle possa caricarlo: `@java` può riportarlo assente anche quando è installato. La reflection sui metodi delle implementazioni aggiunge fragilità.

Il commento che motiva la reflection di `ITextSelection` con un bundle Text opzionale è inoltre incoerente con le dipendenze testuali già richieste nel manifest.

**Intervento:** adapter JDT opzionale, preferibilmente separato se serve supportare IDE senza JDT; in alternativa dipendenza opzionale dichiarata e comportamento verificato. Usare i contratti pubblici tipizzati per la parte già obbligatoria. [OSGi — Module Layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html).

**Accettazione:** test in un'installazione con JDT e una senza; contesto Java disponibile nel primo caso, fallback comprensibile nel secondo.

### R14 — P2: il costo di rendering cresce con l'intera conversazione

**Evidenza statica; degrado da misurare.** [ChatTranscript.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/ChatTranscript.java), linee 118–136; [WorkspaceFileLinks.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/WorkspaceFileLinks.java), linee 23–53; [AcpChatView.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpChatView.java), linee 271–273.

Il batching a 40 ms è positivo, ma ogni aggiornamento ricostruisce il documento Markdown completo e sostituisce l'intero `main`. Il resolver può attraversare tutto il progetto per ogni nome file non risolto: `computeIfAbsent` non memorizza i risultati null, quindi il lavoro si ripete. Il tutto avviene sul percorso UI. La cache dei risultati positivi non viene invalidata dopo rename/delete o nuovi nomi ambigui.

Il transcript e le mappe di tool crescono senza retention. Lo scroll è sempre riportato al fondo, anche mentre l'utente legge messaggi precedenti.

**Intervento:** misurare prima; snapshot/rendering separati dalla UI, aggiornamento del messaggio corrente, indice dei file con invalidazione e cache negativa, budget per sessione. Scorrere automaticamente solo se l'utente segue già il fondo.

**Accettazione:** benchmark con conversazione lunga e progetto grande, tempi del thread UI e memoria registrati; nessuna scansione completa ripetuta per un riferimento inesistente.

### R15 — P2: mancano test del plugin nel runtime che lo esegue — risolto il 4 ottobre 2026

**Risolto sul codice corrente.** La suite Java veloce resta nel modulo [dev.eclipseacp.client.tests](../plugins/dev.eclipseacp.client.tests/pom.xml); il nuovo frammento [dev.eclipseacp.client.runtime.tests](../plugins/dev.eclipseacp.client.runtime.tests/pom.xml) viene invece eseguito da Tycho dentro un framework Equinox reale.

Lo smoke test verifica che il bundle host e le dipendenze della piattaforma siano risolti, richiama codice del plugin dal frammento e crea, legge ed elimina un file tramite le API workspace. Il workflow pubblica sempre i report Surefire/Failsafe come artifact, anche quando la build fallisce.

Resta utile ampliare gradualmente l'harness con test UI mirati e una matrice CI sui sistemi operativi dichiarati supportati; la compilazione multi-ambiente della target non equivale all'esecuzione su più sistemi. [Tycho Surefire](https://tycho.eclipseprojects.io/doc/latest/tycho-surefire-plugin/plugin-info.html).

**Verifica:** `mvnd -T1 verify` esegue 55 test unitari e 2 test nel runtime Equinox, tutti verdi. R10 richiede nuovi test solo se vengono reintrodotte operazioni ACP sul workspace.

### R16 — P2: compatibilità OSGi e installabilità non sono abbastanza vincolate

**Evidenza di configurazione, non installazione fallita dimostrata.** [MANIFEST.MF](../plugins/dev.eclipseacp.client/META-INF/MANIFEST.MF), linee 8–19; [target](../releng/dev.eclipseacp.target/dev.eclipseacp.target.target), linee 5–10; [repository POM](../releng/dev.eclipseacp.repository/pom.xml); [script p2](../scripts/verify-p2-isolation.sh).

Tutti i `Require-Bundle` sono privi di intervalli di versione. La target usa versioni `0.0.0` e quindi delega la selezione al repository di release. I test dichiarano Gson 2.14.0 separatamente: un aggiornamento target può divergere dal classpath test. Inoltre il repository p2 esclude le dipendenze, assumendo che l'IDE ospite fornisca anche CommonMark e Gson.

L'isolamento p2 è intenzionale e utile, ma il relativo script non dimostra l'installabilità su tutte le distribuzioni supportate. Il README suggerisce anche di disabilitare il contatto con altri update site, rendendo particolarmente importante verificare questa assunzione.

**Intervento:** matrice esplicita di distribuzioni/versioni supportate, limiti di compatibilità giustificati, target risolta riproducibile e test p2 director su IDE puliti. Scegliere se le librerie non-platform vadano distribuite o rese prerequisiti documentati. Valutare `Import-Package` per librerie dove riduce l'accoppiamento. [OSGi — Module Layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html).

**Accettazione:** install/update/uninstall su target minima e una più recente; assenza di bundle irrisolti; dipendenze test/runtime allineate. Nessun intervallo di versione inventato senza prova.

### R18 — P2: logging troppo accoppiato al workbench e contenuti diagnostici non filtrati

**Dipendenza headless confermata dal probe; rischio di contenuti sensibili dedotto dai percorsi.** [AcpLog.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/AcpLog.java), linee 24–31; [DefaultAgentProcessLauncher.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/DefaultAgentProcessLauncher.java), linee 126–145; [JsonRpcConnection.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/JsonRpcConnection.java), linee 89, 124–126 e 185.

Il debug integrale è correttamente disabilitato per default e la pagina avvisa del contenuto sensibile. Tuttavia stderr viene sempre registrato a INFO, e payload di errore dell'agente vengono inclusi nelle eccezioni. Non si può assumere che un agente escluda token o contenuti da questi canali. Ogni notifica produce inoltre logging INFO.

`AcpLog.debug` legge lo store Eclipse dal trasporto: la prova headless della scrittura reale fallisce inizializzando le preferenze OSGi. Questa dipendenza ostacola proprio i test di resilienza che mancano.

**Intervento:** sink diagnostico iniettato, controllo del livello prima di costruire payload, limiti di dimensione/frequenza e redazione per campi strutturati; politica esplicita per stderr e tracing integrale. Non memorizzare segreti letterali in preferenze quando sono sufficienti riferimenti esterni o storage sicuro.

**Accettazione:** trasporto testabile senza Eclipse; fixture con token fittizi non compare nei log ordinari; tracing opt-in chiaramente distinguibile.

### R19 — P2: cleanup asincrono senza ownership completa di processi e attività

**Evidenza statica; processo orfano non osservato direttamente.** [AcpSessionService.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/AcpSessionService.java), linee 351–363; [AcpClient.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/AcpClient.java), linee 898–929; [DefaultAgentProcessLauncher.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/DefaultAgentProcessLauncher.java), linee 126–163.

`disconnect` accoda `client.close` nel common pool e dimentica i future. I reader sono daemon non conservati; il processo riceve soltanto `destroy()`, senza attesa/escalation. Non è definita una policy per eventuali discendenti del launcher. La chiusura anticipata del processo rispetto agli stream è una buona precauzione, ma non certifica che il processo termini o che il cleanup finisca prima dell'arresto del bundle.

Le operazioni file già accodate continuano indipendentemente dalla rimozione della sessione; il controllo `contains` protegge alcuni callback di presentazione, non gli effetti sul workspace.

**Intervento:** proprietario esplicito di executor, processi e operazioni per sessione; shutdown idempotente, attesa limitata, escalation e gestione dei soli processi posseduti. Agganciare il cleanup al ciclo di vita adatto al plugin, senza introdurre un activator solo per convenzione. [Eclipse — Concurrency infrastructure](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/runtime_jobs.htm).

**Accettazione:** chiusura durante initialize, prompt e scrittura; agente che ignora la terminazione; nessuna nuova operazione dopo retirement e nessun processo posseduto rimasto attivo oltre il limite stabilito.

### R20 — P2: parsing degli argomenti perde valori validi

**Confermato con probe.** [DefaultAgentProcessLauncher.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/acp/DefaultAgentProcessLauncher.java), linee 48–59 e 96–123.

Il parser aggiunge un token solo se il buffer non è vuoto: `--name "" next` diventa `--name next`, cambiando il significato del comando. Non esiste una grammatica documentata per escape e quote letterali. Su Windows gli script passano da `cmd /c`, che aggiunge un diverso livello di interpretazione; la correttezza dei metacaratteri non è provata dai due test del resolver.

**Intervento:** rappresentare internamente gli argomenti come lista, conservando eventualmente un editor testuale con grammatica esplicita. Testare l'invocazione Windows tramite uno script fixture che restituisca gli argomenti ricevuti; non classificare come vulnerabilità di shell un comando scelto intenzionalmente dall'utente senza distinguere il modello di fiducia.

**Accettazione:** token vuoti, spazi, Unicode, quote e percorsi con backslash attraversano l'invocazione senza alterazioni sui sistemi supportati.

## 5. Analisi SOLID e direzione architetturale

Le dimensioni di `AcpClient` (936 righe), `AcpChatView` (600), `ChatComposer` (538) e `AcpSessionService` (372) indicano dove investigare, non dimostrano da sole una violazione. La valutazione seguente si basa sulle responsabilità effettive.

| Principio | Stato osservato | Evoluzione raccomandata |
| --- | --- | --- |
| **S — Single Responsibility** | Buona estrazione di process launcher, transport, transcript e service. `AcpClient` continua però a unire negoziazione, autenticazione, ciclo sessioni, codec JSON, preparazione allegati e routing richieste. `WorkspaceDiffApplier` combina policy percorsi, codec testo e stato editor. | Estrarre prima i componenti con invarianti/test distinti: `SessionLifecycle`, codec ACP, `WorkspaceAccessPolicy`, adapter dei buffer e operazione di modifica. Tenere la composizione SWT nella view. |
| **O — Open/Closed** | Le interfacce di processo/trasporto consentono sostituzione. Nuovi eventi o metodi ACP richiedono modifiche alle lunghe catene in `deliverSessionUpdate` e `onRequest`. | Codec e handler coesi per famiglia di messaggi; estendere una famiglia senza cambiare lifecycle e UI. Non serve un registro dinamico o un extension point per ogni ramo. |
| **L — Liskov Substitution** | `AgentClient` è utile ma i default `prompt(text, attachments)` e `startNewSession(path, listener)` ignorano rispettivamente allegati e nuovo listener. L'implementazione ACP li sovrascrive, quindi non è un bug attuale di quell'adapter; è un rischio contrattuale per implementazioni alternative. | Documentare precondizioni, completamento asincrono, threading e postcondizioni. Operazioni non supportate devono fallire esplicitamente o essere separate in contratti di capability. Contract test condivisi per gli adapter. |
| **I — Interface Segregation** | `AgentClient` copre chat, history, auth, configurazione e lifecycle; `AgentListener` unisce eventi, permessi, filesystem e dialog. Molti default nascondono le funzionalità assenti. | Piccoli port per esigenze realmente diverse: eventi, interazioni, filesystem e gestione sessioni. Conservare una facade di composizione se semplifica la UI; evitare proliferazione di interfacce senza utilizzatori distinti. |
| **D — Dependency Inversion** | Il package `agent` non importa Gson né classi ACP: miglioramento concreto. Launcher/factory e UI executor sono iniettabili. Persistono dipendenze statiche da `AcpLog`/preferenze, `PlatformUI`, `ResourcesPlugin` e costruzione diretta di servizi nel modello. | Spostare la composizione nel punto di ingresso; iniettare diagnostica, scheduler, gateway workspace e provider della configurazione. Rendere il modello proprietario dello stato, senza costruirvi gli adapter di infrastruttura. |

Riferimenti contrattuali: [AgentClient.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/agent/AgentClient.java), linee 12–28; [AgentListener.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/agent/AgentListener.java); [ChatSessionModel.java](../plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/ui/ChatSessionModel.java), linee 51–59. Interpretazione applicativa dei [principi OOD di Martin](https://butunclebob.com/ArticleS.UncleBob.PrinciplesOfOod).

### Confini proposti

| Componente | Responsabilità | Dipendenze ammesse |
| --- | --- | --- |
| Modello e port agente | Messaggi tipizzati, capability, contratti | Java e tipi di dominio |
| Servizio sessioni | Stato, bozza, richieste, cancellazione, ownership | Port agente, scheduler, port di configurazione/presentazione |
| Adapter ACP | Codec, negoziazione, traduzione errori/eventi | Modello, transport e process port |
| Integrazione workspace | Percorsi, buffer, charset, scheduling e conflitti | API Eclipse resources/filebuffers e policy esplicite |
| UI | Widget, dialog, rendering e azioni utente | Servizi applicativi e API pubbliche SWT/JFace/workbench |
| Composition root | Creazione e collegamento delle implementazioni | Adapter concreti e lifecycle Eclipse |

Applicare questi confini prima come package e contratti. Valutare successivamente un bundle core senza UI e un adapter JDT opzionale: il beneficio deve essere verificabile in testabilità, installazione o riuso. Non esportare tutti i package per facilitare i test; gli export OSGi sono una decisione di API.

## 6. Ulteriori osservazioni e aspetti positivi

### Debito secondario

| ID | Osservazione | Azione |
| --- | --- | --- |
| M01 — P3 | `docs/conformite-architecture.md` conserva numeri di riga/dimensioni obsoleti e affermazioni contraddittorie: dice che la view non dipende dalle preferenze, mentre ora le legge; descrive una configurazione MCP non collegata al percorso corrente. | Aggiornare diagramma e documento dopo le decisioni architetturali, distinguendo stato attuale e obiettivo. |
| M02 — P3 | `McpServerRegistry` non è usato dal service, che passa `List.of()` alla factory; `AcpSessionUpdate` non ha utilizzatori emersi dalla ricerca. | Decidere esplicitamente se completare o rimuovere questi percorsi. Non presentarli come feature funzionanti. |
| M03 — P3 | `IconButton` attiva l'azione su qualsiasi `MouseUp`, senza verificare `event.button == 1`, diversamente dalle righe recenti della view. | Test del click destro e della tastiera; preferire semantica di pulsante standard o completarla nel controllo custom. |
| M04 — P3 | Il footer forza bianco/nero e disabilita il CSS workbench; etichette e testi sono hardcoded; mancano prove ad alto contrasto e con screen reader. | Usare risorse di tema e NLS dove il prodotto richiede localizzazione; verificare focus, tastiera, DPI e contrasto. Non assumere accessibilità completa dalla sola etichetta accessibile. |
| M05 — P3 | Il README indica Ctrl+Alt+O/⌘⌥O, mentre `plugin.xml:89` usa `M1+M2+O`; il binding va verificato e allineato sui sistemi supportati. Sono presenti anche comandi deliberatamente disabilitati. | Allineare shortcut reale, documentazione e scelta di mostrare funzionalità future. |
| M06 — P2 | Il trasporto trasforma errori diversi in `IOException` e risponde `-32603` anche a metodi non supportati; l'autenticazione richiesta è identificata cercando testo nel messaggio (`AcpClient:206–210`). | Errori JSON-RPC tipizzati con code/data; mapping a `-32601` per metodo sconosciuto e validazione di envelope/parametri. Riferimento: [JSON-RPC 2.0](https://www.jsonrpc.org/specification). |
| M07 — P3 | `initialize` comunica versione client `0.1.0` hardcoded, anche quando la release cambia il bundle. Il workflow release pubblica Pages senza una policy esplicita di serializzazione/versione monotona. | Leggere la versione del bundle; serializzare pubblicazioni e impedire downgrade involontari del sito stabile. |
| M08 — P3 | `GfmRenderer` interpreta qualsiasi heading `## You`/`## Agent` come confine di messaggio; anche il testo ricevuto può contenerli. | Modello di messaggi strutturato e rendering del Markdown separato per ciascun messaggio, senza riconoscere i ruoli dal contenuto. |

### Scelte da conservare

- `Bundle-ActivationPolicy: lazy`, dichiarazione Java 21 coerente con il compilatore e assenza di export indiscriminati nel manifest.
- Uso di API workspace e `KEEP_HISTORY`, confronto con la baseline e protezione degli editor dirty come intenti corretti, da rendere robusti sotto concorrenza.
- `ImageRegistry` rilasciato dalla view, `BrowserFunction` rilasciate e dispose listener per il colore creato. Nessun leak generalizzato di immagini dimostrato dall'ispezione.
- Separazione dei DTO dal JSON ACP, launcher/transport iniettabili e test per sessioni ritirate, riuso connessione, replay e batching dei chunk.
- Permesso di rifiuto selezionato per default quando presente; debug integrale disabilitato per default con avviso esplicito.
- Isolamento degli artifact p2 e checkout del tag selezionato nella fase di build release. Il checksum degli asset è utile; non sostituisce una politica di firma/provenienza se questa diventa un requisito del progetto.

## 7. Roadmap eseguibile

Stime orientative in **giorni-persona**, comprendenti implementazione e test mirati, per chi conosce Java/Eclipse. Non sono scadenze impegnative: dipendono dal supporto dei sistemi operativi e dalle decisioni implementative. La stima della Fase 1 e il totale vanno ricalcolati sul perimetro ridotto; le altre stime restano quelle della baseline. Le stime sono per fase, per evitare doppio conteggio di interventi condivisi, ed escludono attese e interventi sugli agenti esterni.

| Fase | Obiettivo e deliverable | Rilievi | Dipendenze | Stima | Criterio di uscita |
| --- | --- | --- | --- | --- | --- |
| 0 — Baseline verificabile | Ripristinare build ufficiale in ambiente con cache scrivibile; registrare versioni risolte; aggiungere harness minimo Eclipse/workspace e fake agent controllabile. | R15, R16 | Nessuna | 2–3 gg | `mvn verify` e un test workspace passano; i risultati sono archiviati in CI. |
| 1 — Integrità delle modifiche | Separare eventi/permessi/scritture; proteggere percorsi, verifica/apply e recupero batch; filtrare URL. | R01, R02, R04, R05, R07 | Fase 0 per regressioni workspace; R02 può procedere subito | Da ristimare | Nessuna scrittura da notifica o permesso rifiutato; successo write coerente; race/failure non perdono dati; URL fuori policy bloccati. |
| 2 — Resilienza e lifecycle | I/O fuori UI, writer serializzato, stati di connessione, timeout, cancellazione e cleanup per sessione. | R06–R09, R18, R19, M06 | Contratti di Fase 1 | 6–9 gg | Agente silenzioso/terminato non blocca Eclipse; future e processi terminano entro le policy stabilite. |
| 3 — Correttezza d'uso | Charset/buffer/nuovi file, preferenze transazionali, bozze per progetto, argomenti e UX essenziale. | R10–R12, R20, M03–M05 | Fasi 1–2 | 4–6 gg | Test di round trip, Cancel/Defaults, cambio progetto e invocazione multipiattaforma verdi. |
| 4 — Consolidamento SOLID e prestazioni | Estrarre codec/lifecycle/port; rendere il core testabile senza workbench; adapter JDT corretto; rendering incrementale e indice file. | R13, R14, R18, analisi SOLID, M02, M08 | Invarianti stabilizzate e test precedenti | 6–10 gg | Nessuna regressione di protocollo; transport testabile headless; benchmark UI/memoria e test con/senza JDT. |
| 5 — Compatibilità e rilascio | Test di install/update su IDE puliti, intervalli dipendenze, matrice runtime, documenti aggiornati e pipeline stabile. | R15, R16, M01, M07 | Fasi precedenti | 4–6 gg | Artifact installabile sulla matrice dichiarata, smoke test OS verdi e documentazione allineata alla versione pubblicata. |

### Prime unità di lavoro consigliate

1. **PR A — Notifiche senza effetti:** isolare R01 con test che dimostri la mancata scrittura su richiesta di permesso rifiutata e notifiche ripetute. È il primo rischio da ridurre.
2. **PR B — Transcript confinato:** R02 con test del renderer e controllo di navigazione; includere i renderer personalizzati nella verifica.
3. **PR C — Operazioni workspace affidabili:** R04/R05/R07, con test di collisione e fallimento parziale; introdurre solo le astrazioni necessarie alla correzione.
4. **PR D — Trasporto terminale e timeout:** R08/R09, diagnostica iniettata e test con EOF/frame invalidi/agente silenzioso; poi completare R06/R19.

Ogni PR deve contenere un comportamento verificabile, non un insieme di rinominazioni e refactoring scollegati. Aggiornare stato e test di accettazione degli ID interessati. La policy delle linked resource è una decisione di prodotto da registrare esplicitamente.

### Responsabilità suggerite

| Ruolo logico | Responsabilità |
| --- | --- |
| Maintainer protocollo | Contratto ACP, stati delle richieste, transport e compatibilità con agenti fixture. |
| Maintainer Eclipse | Workspace, editor buffer, Job, UI e lifecycle del plugin. |
| Responsabile release | Target, dipendenze, p2, matrice e riproducibilità. |

Una sola persona può coprire più ruoli. L'assegnazione serve a evitare che i problemi tra protocollo e piattaforma restino senza proprietario.

## 8. Piano di verifica e criteri di completamento

| Livello | Scenari da aggiungere | Perché |
| --- | --- | --- |
| Unit/contract | Codec, errori RPC, capability e default delle interfacce, argomenti, parsing limiti, policy URL, bozze e preferenze in memoria. | Invarianti rapide e riproducibili senza workbench. |
| Trasporto/processo | EOF, frame malformati/grandi, risposta mancante, backpressure stdin, stderr continuo, cancellazione, shutdown e launcher che non termina. | I fake attuali non esercitano stream e processo reali. |
| PDE workspace | Linked resource/symlink, charset, dirty buffer, conflitto tra job, creazione cartelle, errore sul secondo file, letture e scritture dirette. | Qui si concentra il rischio di effetti sui dati. |
| UI Eclipse | Dialog dopo chiusura, due progetti con bozze diverse, browser indisponibile, navigazione URL, tastiera, temi e DPI. | Verificare thread e lifecycle dei widget reali. |
| Installazione | IDE minimo dichiarato, release più recente supportata, distribuzione con/senza JDT, update e uninstall. | Confermare wiring e assunzioni del repository p2. |

Obiettivi iniziali proposti, da calibrare con una baseline reale:

- **Correttezza:** zero scritture da notifiche informative; nessun ack di scrittura prima dell'effetto promesso; nessuna perdita di aggiornamenti nei test di concorrenza.
- **UI:** nessuna attesa di processo, stream o scheduling rule sul thread SWT; misurare il 95° percentile delle attività UI sul carico di riferimento e fissare inizialmente un budget di 50 ms per aggiornamento. È un obiettivo, non una misura ottenuta oggi.
- **Lifecycle:** nessun future irrisolto o processo posseduto attivo dopo il limite di shutdown definito; contatori di pending e code tornano a zero.
- **Memoria/prestazioni:** carico dichiarato, per esempio transcript da 1 MiB e progetto da 10.000 file; budget di memoria e retention espliciti, senza crescita illimitata delle cache.
- **Release:** build Tycho ufficiale, test plugin e installazione p2 obbligatori; la compilazione Java diretta resta una diagnostica alternativa.

Un rilievo si considera chiuso quando esistono correzione, test dello scenario indicato, verifica nel livello runtime necessario e aggiornamento della documentazione coinvolta. I rilievi legati a un rischio runtime non vanno chiusi soltanto perché un test con fake è verde.

## 9. Riproduzione della verifica locale e limiti residui

Comando ufficiale tentato, con Maven 3.9.9 installato fuori dal `PATH` predefinito:

```sh
/Users/ivanrododendro/.sdkman/candidates/maven/3.9.9/bin/mvn \
  --offline --batch-mode --no-transfer-progress verify
```

Il Maven predefinito dell'ambiente è 3.6.3; il README richiede 3.9 o superiore. È stata quindi scelta l'installazione 3.9.9 già presente. Non sono stati rimossi lock né alterate cache condivise per forzare la build.

La verifica alternativa compila i sorgenti tracciati con Java 21, usa i JAR della cache `p2/osgi/bundle` escludendo source e frammenti SWT GTK/Win32, aggiunge JUnit 4.13.2/Hamcrest 1.3 ed esegue gli otto `*Test.java` tramite `JUnitCore`. Lo script temporaneo `verify.sh` documenta questi comandi; include anche un probe aggiuntivo che termina con l'errore di preferenze descritto sopra. Questo errore del probe non è un fallimento dei 43 test esistenti.

Output essenziale osservato:

```text
JUnit version 4.13.2
OK (43 tests)
unsafe javascript URL preserved=true
remote image URL preserved=true
empty quoted argument result=[--name, next]
after EOF closed=false, error notifications=0
```

I file temporanei sono ausili diagnostici locali, non parte della suite del progetto. Per risultati riproducibili in CI occorre implementare gli harness proposti nella roadmap. Questa review non certifica compatibilità con tutti gli agenti ACP, non misura copertura e non sostituisce i test del browser e del workspace su Eclipse reale.
