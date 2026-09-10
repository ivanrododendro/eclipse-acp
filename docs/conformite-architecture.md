# Conformité code / architecture PlantUML

## Conclusion

La conformité est partielle : les couches principales du diagramme existent, mais
plusieurs frontières de responsabilité ne sont pas respectées dans le code.

## Inconformités

1. **`AcpChatView` concentre trop de responsabilités. — Traité partiellement**

   Cette classe (1 413 lignes) gère l'interface SWT, le cycle de vie des sessions,
   le choix du fournisseur, la création du client, les préférences, les callbacks
   ACP, les permissions, les diffs et les accès fichiers. Voir notamment la création
   de session et du client (`AcpChatView.java`, lignes 334 à 389), l'adaptateur de
   callbacks (`AcpChatView.java`, lignes 587 à 647) et la gestion des diffs
   (`AcpChatView.java`, lignes 1021 à 1128).

   Cela dépassait le rôle de vue « ACP Chat » du diagramme. L'orchestration de
   session est désormais extraite dans `AcpSessionService` : sélection et lecture de
   configuration du fournisseur, création du client, connexion/restauration,
   pagination de l'historique et changement de session.

   La vue conserve intentionnellement les contrôles SWT, le rendu, les dialogues et
   la présentation des changements. Les callbacks ACP et la coordination détaillée
   des diffs restent toutefois dans `AcpChatView` et constituent le prochain
   périmètre d'extraction possible.

2. **L'abstraction `AgentClient` dépend de l'adaptateur ACP. — Traité**

   `AcpClient` implémente normalement `AgentClient`, mais l'ancienne
   `AgentClientFactory` importait directement `AcpClient` et `AcpListener`, puis
   instancie l'implémentation ACP. Il en résultait le cycle de dépendances
   `agent -> acp -> agent`.

   La fabrique a été déplacée dans l'adaptateur sous le nom `AcpClientFactory`.
   La couche `agent` ne référence désormais plus de type ACP ; elle ne contient que
   le port `AgentClient` et ses modèles. La composition ACP est réalisée par
   `AcpSessionService`, côté application Eclipse.

3. **Des détails ACP/JSON traversent la frontière de l'abstraction. — Traité**

   `AgentClient` exposait `JsonElement` et un discriminateur `valueType` pour les
   options de configuration. La factory prenait un `AcpListener`, tandis que ce
   listener exposait `JsonObject` et `AcpSessionUpdate`.

   `AgentClient` reçoit désormais `ConfigValue`. Les callbacks sont portés par
   `AgentListener`, avec `SessionUpdate` (une `Map` immuable) et des modèles neutres
   pour les outils, permissions, fichiers et diffs. `AcpClient` convertit les objets
   Gson et messages ACP avant de franchir cette frontière.

4. **`AcpClient` mélange adaptation ACP, gestion du processus et transport. — Traité**

   Avant le correctif, la classe construisait et lançait le processus, instanciait le
   transport JSON-RPC, lisait `stderr` et traduisait en même temps les messages et
   sessions ACP.

   Le lancement, l'arrêt et les diagnostics `stderr` sont désormais isolés derrière
   `AgentProcessLauncher` / `AgentProcess` (implémentation locale :
   `DefaultAgentProcessLauncher`). La création du transport est isolée derrière
   `JsonRpcTransportFactory` (implémentation : `DefaultJsonRpcTransportFactory`).
   `AcpClient` ne conserve que l'adaptation du protocole ACP et dépend de ces deux
   abstractions, injectables pour des tests indépendants.

5. **La page de préférences contourne `McpServerRegistry`. — Traité**

   Le diagramme décrit le flux `AcpPreferencePage -> registres -> IPreferenceStore`.
   Auparavant, la page lisait, validait et écrivait directement le JSON MCP dans les
   préférences, sans passer par `McpServerRegistry`.

   La page délègue désormais la lecture du JSON sérialisé, sa validation et sa
   sauvegarde à `McpServerRegistry`; elle ne conserve que l'affichage de l'éditeur et
   la remontée des erreurs à l'utilisateur.

6. **La vue contourne partiellement les services de configuration. — Traité**

   Auparavant, `AcpChatView` instanciait les registres et lisait directement
   `AcpPreferences.store()` pour des décisions de session.

   La vue ne dépend plus de `AcpPreferences`, des registres ou de
   `IPreferenceStore`. `AcpSessionService` compose les registres et prépare une
   `SessionConfiguration` immuable, dont les choix sont portés par la session UI.

## Points conformes

- `JsonRpcConnection` est correctement isolé comme transport JSON-RPC.
- `WorkspaceDiffApplier` concentre bien la médiation, l'application et l'annulation
  des modifications du workspace.
- La séparation entre l'interface `AgentClient` et l'implémentation `AcpClient` est
  amorcée, mais elle est affaiblie par les dépendances et types ACP décrits ci-dessus.
