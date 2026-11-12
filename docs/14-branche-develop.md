# 14. Branche develop et circuit de merge

Exigence du CTO, 2026-11-11 :

- le Lead crée une branche `develop` ;
- les agents mergent sur `develop` : l'agent soumet au Lead, et si le Lead approuve, le merge se fait ;
- le merge sur `main` reste soumis à l'approbation du CTO, et c'est le Lead qui la demande.

## Circuit

```
main ──────────────────────────────●──────────  (approbation du CTO, demandée par le Lead)
  └─ develop ─────●───────●───────┘
                  │       │   (approbation du Lead = merge)
     agent/x/t-1 ─┘       │
     agent/y/t-2 ─────────┘
```

1. **Création de `develop`.** Au démarrage de la première tâche d'un projet, la plateforme crée `develop` depuis `main` au nom du Lead (événement `branch.created`, acteur : le Lead). Les projets existants l'obtiennent à leur prochaine tâche démarrée.
2. **Branches de tâche.** Elles partent de `develop` (« Branch … created from develop »).
3. **Soumission.** `task.submit` déroule les mêmes contrôles qu'avant, mais contre `develop` :
   - on intègre `develop` dans la branche, et les conflits retournent à l'agent ;
   - on vérifie que la branche contient des changements ;
   - on recherche des secrets ;
   - on lance les tests dans la sandbox.

   La merge request vise `develop` (`target_branch`).
4. **Revue par le Lead.** Le Lead relit la MR. Son approbation, avec les 5 contrôles au vert, vaut décision de merge : la plateforme merge dans `develop`, sans passer par le CTO. La tâche passe à DONE. L'approbation est auditée (`merge_request.merge`, acteur : le Lead, `into: develop`).
   - **Travail écrit par le Lead :** il ne s'approuve pas lui-même. Un coéquipier relit (QA d'abord), puis le CTO approuve le merge dans `develop` depuis l'Inbox.
   - **Aucun Lead actif avec un modèle :** le CTO relit, comme avant.
   - **Le CTO peut toujours intervenir :** il relit lui-même depuis la page de la MR.
5. **`develop` → `main`.** Le Lead demande l'approbation du CTO (action `branch.merge_main`), de deux façons :
   - **automatiquement**, quand plus aucune tâche du projet n'est ouverte après un merge dans `develop` ;
   - **sur ordre** dans le chat du Lead : `{"do":"merge_main","note":"…"}`, par exemple « demande-moi le merge sur main ».

   La demande enregistre :
   - le commit de `develop` proposé ;
   - le nombre de commits d'avance ;
   - les tâches mergées depuis le dernier merge sur `main`.

   Elle est refusée, avec la raison, dans trois cas :
   - `develop` n'apporte rien ;
   - une demande attend déjà ;
   - `main` est en conflit avec `develop`.
6. **Approbation du CTO.** Dans l'Inbox, la carte montre le projet, la note du Lead, les tâches et le commit. Si le CTO approuve, c'est **le commit proposé** qui est mergé dans `main` (`--no-ff`, « Merge develop into main »), pas un `develop` qui aurait bougé depuis. Un refus laisse `main` intact.

   Événements : `branch.merged` en cas de succès, `branch.merge_failed` avec la raison en cas d'échec.

## Ce que voit chacun

- **Agent :** son manuel d'outils dit que `task.submit` « asks the Lead to review: if the Lead approves, your branch merges into develop ».
- **Lead :** `<project_state>` indique de combien de commits `develop` devance `main` et si un merge sur `main` attend le CTO. Son manuel d'ordres explique le circuit et l'ordre `merge_main`.
- **CTO :**
  - la page MR affiche la cible (`develop`) et « merging into develop » quand le Lead a approuvé ;
  - le fil d'événements décrit `branch.created`, `branch.merged` et `branch.merge_failed` ;
  - l'Inbox détaille la demande de merge sur `main`.

## Compatibilité

- Les MR déjà ouvertes vers `main` en attente d'approbation gardent leur circuit (approbation CTO, merge dans `main`). Une MR resoumise est reciblée vers `develop`.
- Un dépôt sans `develop` continue de fonctionner sur `main` (`GitService.integrationBranch`).
- Les merges dans `develop` se font dans un worktree dédié à `develop`, si bien que le checkout `main` du dépôt n'est jamais basculé.

## Vérification

`ExecutionIntegrationTest`, avec un vrai Git et une vraie sandbox Docker :

- **`anAssignedTaskIsExecutedReviewedValidatedAndMerged`**
  - la branche part de `develop` et le Lead relit ;
  - son approbation merge dans `develop` et la tâche passe à DONE ;
  - `main` n'a rien tant que le CTO n'a pas approuvé ;
  - le Lead demande automatiquement le merge (`branch.merge_main`, demandé par le Lead, tâches listées) ;
  - après approbation, `main` contient le code.
- **`theWatchdogStopsALoopAndTheCtoReviewsWhenNoTeammateCan`**
  - le travail du Lead est relu par le CTO puis mergé dans `develop` ;
  - un refus du CTO laisse `main` intact ;
  - l'ordre `merge_main` dans le chat crée une nouvelle demande, et un second ordre est refusé (« already waiting ») ;
  - `<project_state>` montre l'avance de `develop` ;
  - l'approbation merge dans `main`.

## Revue qui tient dans les limites du modèle (2026-11-11)

Constaté sur « To do list » : la relecture de T-17 par le Lead (Groq, 8000 tokens par minute) a demandé 3 fois les mêmes changements, puis a bloqué la tâche. Le diff commençait par 1002 lignes de `package-lock.json`, qui remplissaient toute la place : le Lead ne voyait jamais `src/` ni `tests/`, où l'agent avait pourtant fait les corrections.

- **Diff de revue** (`TaskExecution.reviewDiff`) :
  - tous les fichiers changés sont listés (`<changed_files>`) ;
  - les fichiers générés sont listés sans contenu : fichiers lock, `*.min.js`, `*.map`, `dist/`, `build/`, `node_modules/`, `target/`, `vendor/` ;
  - la place restante est partagée équitablement entre les autres fichiers, du plus petit au plus grand, pour qu'aucun fichier n'en cache un autre.
- **Limite par minute** : quand le fournisseur en annonce une, la revue s'y ajuste (réponse d'environ 1600 tokens, contexte réduit, diff à la place restante). Le premier refus de Groq apprend la limite ; la revue repart aussitôt, réduite.
- **Consignes du relecteur** :
  - un critère de comportement se prouve par un test ;
  - un critère sur le code lui-même (langage, style, commentaires, absence de build) se prouve par le fichier qui le montre ;
  - le relecteur n'exige pas d'outils absents de la tâche, ne répète pas un commentaire déjà traité et ne commente pas une partie qu'il n'a pas vue.
- **Désaccord : le CTO tranche** (remplace le blocage après 3 tours). Constaté sur T-17 : 3 critères sur 4 étaient validés, mais le Lead exigeait toujours un test jsdom pour « Runs in latest browsers », et l'agent s'est bloqué lui-même faute de pouvoir le satisfaire.
  - Dès le 3e tour refusé par un agent relecteur, la tâche ne bloque plus : la MR revient au CTO (`reviewer_agent_id` vidé, run `SUBMITTED`), et une carte `merge_request.arbitrate` arrive dans l'Inbox avec les remarques du relecteur.
  - « Accept as is and merge » accepte les critères tels quels et merge dans la cible.
  - « Side with the reviewer » renvoie les remarques à l'auteur.
  - Le CTO peut aussi décider depuis la page de la MR ; la carte de l'Inbox est alors réglée avec la même décision.
  - Une tâche déjà bloquée sur une revue refusée (MR `CHANGES_REQUESTED`, travail `BLOCKED` ou `PAUSED`) peut aussi être jugée depuis la page de la MR, si la branche n'a pas bougé depuis la dernière soumission.
  - Test : `ExecutionIntegrationTest.aReviewDisagreementGoesToTheCto`.
- **Correction de JSON** : seuls les 2000 premiers caractères de la réponse fautive sont renvoyés au modèle.

## Suppression des branches de tâche (2026-11-12)

Une branche par tâche : sans ménage, elles s'accumulent (16 sur « To do list »). Exception validée par le CTO à la règle « supprimer une branche demande une approbation humaine » :

- **Après le merge** (`TaskExecution.merge` → `BranchFlow.deleteMergedTaskBranch`), la branche de tâche est supprimée avec son worktree.
  - Elle n'est supprimée que si tous ses commits sont dans la cible (`git merge-base --is-ancestor`) et qu'aucune tâche ouverte ne la réutilise : une sous-tâche issue d'un découpage continue sur la branche de son parent.
  - Rien n'est perdu : le merge `--no-ff` garde les commits. La MR garde le nom de la branche et son commit de merge, et ses fichiers et son diff sont lus depuis ce commit de merge.
  - Événement `branch.deleted`.
- **Branches mergées avant ce changement** : le nettoyage périodique (toutes les 5 minutes) les supprime de la même façon.
- **Toute autre branche** (tâche annulée, travail abandonné) : le CTO la supprime depuis la page Branches.
  - Bouton « Delete », avec confirmation dans la page et avertissement si la branche n'est pas mergée.
  - Le clic vaut approbation explicite : il est audité (`branch.delete`, APPROVED, avec le commit de tête).
  - La MR non mergée de cette branche passe à `CLOSED`.
  - Refusé pour `main` et `develop`, et pour une branche qu'une tâche ouverte utilise encore.
- **Test** : `ExecutionIntegrationTest` vérifie que la branche est supprimée après le merge, que la MR mergée montre encore ses fichiers et son diff, et que `main` et `develop` sont refusées (409).
