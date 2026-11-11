# 11 — Budget des tâches, découpage fin, réunion de recadrage

Retours du CTO du 2026-11-10. Livré dans la migration `V7__realignment_and_small_tasks.sql`.

## 1. Budget d'une tâche modifiable

Avant, quand une exécution atteignait son budget de tokens, l'agent s'arrêtait (run `BLOCKED`) et rien ne permettait de changer ce budget. « Reprendre » relançait une étape qui se rebloquait aussitôt.

- `PUT /api/v1/tasks/{taskId}/run/budget {tokenBudget}` : change le budget de l'exécution ouverte. Il doit dépasser ce qui est déjà consommé. Si l'exécution était arrêtée par son budget, elle reprend là où l'agent s'était arrêté. Le changement est enregistré dans le journal d'audit, dans l'événement `task_run.budget_changed` et dans une étape PLATFORM.
- `resume` refuse désormais (409 `budget_exhausted`) une exécution dont le budget est épuisé, au lieu de la relancer pour rien.
- Budget par projet : `budgets.task_token_budget`, réglable dans Settings → Project budgets → « Tokens per task ». Sans réglage, c'est la valeur par défaut de la plateforme (`aiw.execution.token-budget`, 200 000).
- À 80 % du budget, le prompt de l'agent lui demande de soumettre ce qui marche ou de se bloquer en décrivant ce qui reste, pour que le Lead puisse découper.
- Correction au passage : reprendre une exécution en pause échouait toujours avec « agent busy », parce que l'exécution se comptait elle-même comme travail en cours (`agentBusyElsewhere`).

Interface (page Run) : « Change » sur la carte budget. Quand le budget est épuisé, un encadré propose « Double it and resume », « Other amount » et « Ask the Lead to split ».

## 2. Tâches aussi petites que possible

Objectif : une tâche tient dans une seule exécution, même sur un petit modèle (Groq, OpenRouter…), pour qu'un gros projet avance une petite tâche à la fois.

- Les prompts de planification (`LEAD_PLAN`) et de découpage s'appuient sur le budget réel par tâche (`TaskSizing.rules`). Chaque tâche doit tenir dans N tokens (environ N / 8 000 étapes), porter sur un seul changement ciblé (4 fichiers au plus, ses propres tests, 1 à 4 critères), et une fonctionnalité devient plusieurs tâches reliées par `dependsOn`. Les textes restent courts pour que la sortie tienne dans les limites d'un petit modèle. Le plan peut aller jusqu'à 60 tâches (30 avant).
- Découpage a posteriori : `POST /api/v1/tasks/{taskId}/split {guidance?}`, accepté quand le travail est en pause ou bloqué. Le Lead écrit 2 à 8 tâches (`source_kind = SPLIT`) qui couvrent tous les critères. Elles reprennent les dépendances de la tâche d'origine, et les tâches qui dépendaient de l'originale dépendent maintenant d'elles. La première continue sur la branche d'origine (`tasks.base_branch`) si du travail y est déjà commité. L'originale est annulée, sa branche conservée. Le tout coûte un appel modèle.

## 3. Réunion de recadrage en cours de projet

- Le CTO convoque lui-même une réunion (titre, ordre du jour, organisateur, participants), ou ordonne au Lead de l'organiser à partir de sa préoccupation : `POST /api/v1/projects/{projectId}/realignment {concern, delegate}`.
- Délégation (`LEAD_REALIGN`, un appel) : le Lead lit l'état du projet enregistré par la plateforme (tâches par statut, tâches bloquées et leurs raisons, activité de l'équipe), puis choisit l'ordre du jour et les participants. Le code ajoute d'office toute personne qui a une tâche bloquée. La préoccupation du CTO ouvre toujours l'ordre du jour, mot pour mot.
- Pendant la réunion (purpose `REALIGNMENT`), le travail en cours des participants est mis en pause (`task_runs.paused_for_meeting`), et aucune nouvelle tâche ne démarre pour un agent en réunion. À son tour de parole, chaque participant reçoit son état de travail réel (`<your_work recorded_by="platform">` : tâche, état, raison du blocage, budget, dernière réflexion). Il expose ses problèmes, et les autres proposent des solutions.
- Le compte rendu ajoute `problems[]` (qui, quelle tâche, problème, solution convenue). À la fin de la réunion :
  - chaque solution est envoyée comme message à l'exécution de la tâche concernée. Une exécution que l'agent avait lui-même bloquée reprend avec cette solution, sauf si son budget est épuisé, ce qui reste la décision du CTO ;
  - les exécutions mises en pause reprennent avec le résumé et les décisions de la réunion ;
  - l'événement `realignment.applied` indique le nombre de solutions remises et d'exécutions reprises.
- Si la réunion est annulée (« End », désormais disponible aussi pour une réunion en échec), le travail reprend sans compte rendu.

## Vérification

- `RealignmentIntegrationTest` (vraie sandbox Docker, modèle scripté) :
  - budget épuisé → reprise refusée → budget trop bas refusé → budget relevé, l'exécution reprend → découpage refusé tant que le travail tourne → pause → découpage : l'originale est annulée, les dépendances sont transférées, la première sous-tâche reprend la branche d'origine ;
  - le CTO délègue au Lead → réunion `REALIGNMENT` avec le développeur bloqué ajouté d'office → le travail de QA passe en pause → le développeur voit son état réel → la solution du compte rendu est remise, l'exécution bloquée reprend → l'exécution en pause reprend avec les décisions.
- Suite backend complète : 23 tests, tous au vert. Frontend : typecheck, vitest et build OK.
- Contrôle visuel du panneau « Realign the project » et du formulaire de convocation. La page Run en état « budget épuisé » n'a pas été photographiée : le compte de test local n'avait aucune exécution.

## Limites connues

- Le recadrage n'enchaîne pas de replanification automatique. Les éléments d'action restent à transformer en tâches (« Make it a task »).
- Le découpage est déclenché par le CTO, pas automatiquement à l'épuisement du budget, pour éviter des appels modèle non demandés.
