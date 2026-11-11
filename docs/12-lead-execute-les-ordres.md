# 12 — Le Lead exécute les ordres du CTO

Retour du CTO du 2026-11-11 : dans le chat, le Lead répondait « je ne peux pas ouvrir l'étape moi-même » alors que le plan existait déjà. Le chat était une conversation sans aucune action ni état réel ; le Lead inventait des étapes. Livré dans `V8__lead_chat_orders.sql`.

## Ce qui change

- **Le Lead voit l'état réel du projet.** Chaque message du CTO lui arrive précédé de `<project_state recorded_by="platform">` : tâches par statut, tâches ouvertes (statut, assigné, dépendances, travail en cours et budget consommé, raison de blocage), équipe et activité. Le texte est construit par le code (`LeadChatCommands.state`), pas par le modèle.
- **Le Lead exécute les ordres.** Il termine sa réponse par un bloc `<orders>[...]</orders>`. La plateforme exécute chaque ordre avec les droits du CTO qui a écrit le dernier message (rôle MEMBER minimum, revérifié à l'exécution), via les mêmes services que les boutons de l'interface. Chaque ordre est inscrit au journal d'audit (`lead.order`, ALLOWED ou DENIED).
- Ordres disponibles : `create_task`, `assign`, `start`, `pause`, `resume` (débloque aussi), `message` (consigne lue par l'agent à sa prochaine étape), `budget`, `split`, `cancel`, `realign` (le Lead organise une réunion de recadrage). 10 au plus par réponse.
- **Résultat réel, pas déclaré.** Le bloc est retiré du texte affiché. Sous la réponse, le chat montre pour chaque ordre le résultat enregistré par la plateforme (✓ ou ✗ et la raison, par exemple « No task T-99 in this project »). Au message suivant, le Lead reçoit ces résultats dans son historique.
- Garde-fous dans le prompt : un ordre seulement si le dernier message du CTO le demande ou approuve une proposition ; ne jamais écrire « c'est fait » ; ne jamais inventer d'étape ou d'approbation. Les actions sensibles (déploiement, suppression de branche, release) ne font pas partie des ordres.
- Les instructions par défaut du Lead ne disent plus « en conversation vous pouvez seulement converser ». Pour les Leads déjà recrutés, dont les instructions sont enregistrées, le manuel des ordres le remplace explicitement.

## Sobriété

Le manuel des ordres est dans la partie stable du prompt système, donc mis en cache. L'état du projet (40 lignes au plus) n'est ajouté qu'au dernier message du CTO. Aucun appel modèle supplémentaire : les ordres sont dans la même réponse.

## Vérification

- `RealignmentIntegrationTest.theLeadCarriesOutTheCtosOrdersFromItsChat` : le CTO demande au Lead d'assigner T-1. La réponse scriptée contient trois ordres. T-1 est assigné, T-2 est créé et dépend de T-1, et l'ordre sur T-99 est refusé avec sa raison. Le bloc n'apparaît pas dans le texte, l'état du projet figurait dans la requête, et le résultat revient au Lead au message suivant.
- Suite backend complète (24 tests) au vert. Frontend : typecheck, vitest, build OK.
- Pas de contrôle visuel du chat : la stack Docker en cours n'a pas la migration V8.

## Réécrire une tâche, voir les tâches fermées (2026-11-11)

Constat sur le projet du CTO :

- **Recadrage par message seulement :** le Lead a voulu ramener T-3 au périmètre vanilla, mais par un simple message. Le découpage de T-3 a ensuite relu la description d'origine (Vite) et recréé des tâches Vite.
- **Tâches fermées invisibles :** `<project_state>` ne listait pas les tâches fermées. Le Lead a donc affirmé que T-1, T-3 et T-11 étaient encore ouvertes, alors qu'elles étaient annulées.

Corrections :

- **Nouvel ordre `update_task`** `{"task":3,"title","description","criteria"}` : il réécrit une tâche ouverte, et l'agent qui y travaille est prévenu. Le manuel du Lead précise qu'un changement de périmètre se fait ici, pas par message.
- **Tâches fermées dans l'état :** `<project_state>` ajoute la ligne « Closed (no longer to do): T-1 cancelled, … ».
