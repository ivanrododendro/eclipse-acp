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

4. **`AcpClient` mélange adaptation ACP, gestion du processus et transport.**

   La classe construit et lance le processus (`AcpClient.java`, lignes 92 à 114),
   instancie le transport JSON-RPC, lit `stderr` (`AcpClient.java`, lignes 637 à
   656), et traduit en même temps les messages et sessions ACP.

   Le transport `JsonRpcConnection` existe bien, mais son cycle de vie et le
   lancement du processus restent inclus dans l'adaptateur. Un `AgentProcessLauncher`
   et une fabrique de transport rendraient ces responsabilités explicites et
   testables séparément.

5. **La page de préférences contourne `McpServerRegistry`.**

   Le diagramme décrit le flux `AcpPreferencePage -> registres -> IPreferenceStore`.
   Pourtant la page lit, valide et écrit directement le JSON MCP dans les préférences
   (`AcpPreferencePage.java`, lignes 47 et 58), sans passer par
   `McpServerRegistry`.

   La persistance MCP est ainsi répartie entre la page et le registre. La page devrait
   déléguer la validation et la sauvegarde au registre.

6. **La vue contourne partiellement les services de configuration.**

   `AcpChatView` instancie les registres et lit directement
   `AcpPreferences.store()` pour des décisions de session (`AcpChatView.java`,
   lignes 345, 348, 364, 671, 676, 1025 et 1326).

   La vue devient dépendante du mécanisme de persistance Eclipse. Elle devrait
   recevoir une configuration/session préparée par une couche de service.

## Points conformes

- `JsonRpcConnection` est correctement isolé comme transport JSON-RPC.
- `WorkspaceDiffApplier` concentre bien la médiation, l'application et l'annulation
  des modifications du workspace.
- La séparation entre l'interface `AgentClient` et l'implémentation `AcpClient` est
  amorcée, mais elle est affaiblie par les dépendances et types ACP décrits ci-dessus.
