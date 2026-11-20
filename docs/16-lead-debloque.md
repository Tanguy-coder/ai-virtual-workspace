# 16 — Le Lead décide en premier sur une tâche bloquée

Demande du CTO du 2026-11-18. Sur « To do list », T-15 s'est bloquée sur une question technique : « le test attend `undefined` de `execSync`, Node renvoie `null` ; faut-il changer le test ou le script ? ». La question attendait le CTO, alors que le Lead pouvait y répondre. En mode A, le Lead dirige l'équipe : il doit trancher ce qui relève de lui, et ne remonter au CTO que le reste.

## Fonctionnement

Quand un agent bloque sa tâche (`task.block`, le chien de garde, des réponses inutilisables, un budget épuisé), `TaskExecution.block` met en file un travail `LEAD_UNBLOCK` pour le Lead. Trois conditions : l'agent n'est pas le Lead, le Lead est actif, et il a un modèle. `LeadUnblocker` fait **un appel court** au modèle de raisonnement du Lead. Le prompt contient seulement :

- la tâche (titre, description, critères d'acceptation) et la raison du blocage ;
- les 12 dernières étapes du travail, et la dernière sortie en échec (commande, ou tests lancés par la plateforme à la soumission) ;
- le code déjà commité sur la branche (diff limité à 5 000 caractères) ;
- les fichiers cités dans la raison (`tests/lint.test.js`…), dans la limite de 6 000 caractères.

Le Lead répond par l'une de quatre décisions :

| Décision | Effet |
|---|---|
| `answer` | Sa consigne est remise à l'agent (étape « Message from the Lead ») et le travail reprend tout seul. |
| `rewrite` | La tâche est réécrite (titre, description, critères), la consigne est remise à l'agent, et le travail reprend sur la nouvelle version. |
| `split` | La tâche est découpée par le travail `LEAD_SPLIT` existant (`docs/11`). |
| `escalate` | Le Lead écrit au CTO dans son chat : la raison, la question, les options et sa recommandation. La tâche reste bloquée, et le CTO répond dans le chat ; le Lead exécute ensuite ses ordres. |

Le prompt interdit d'escalader une question technique à laquelle le code permet de répondre. Sont escaladés : un choix de produit ou de périmètre que le brief ne tranche pas, une approbation, un accès, de l'argent.

**Garde-fous**

- Échelle (`docs/17`) : 2 corrections ou consignes, puis 2 réécritures, découpages ou escalades ; au-delà, le blocage suivant va au CTO sans appel de modèle. Le compte repart de zéro à chaque intervention du CTO sur la tâche (« Ask the Lead », reprise, déblocage, réécriture), et une escalade ne compte pas comme une décision. Avant ce correctif, le compte cumulait toute l'histoire de la tâche : sur T-16, chaque blocage revenait au CTO dès la décision suivante.
- Si le travail ne peut pas reprendre (budget épuisé, agent occupé ailleurs), la consigne reste remise à l'agent et la question va au CTO.
- Si le CTO a déjà agi (reprise, annulation, découpage) avant que le Lead ne décide, le Lead ne fait rien.

**Traçabilité** : l'événement `task.lead_decided` (décision, raison, consigne ou question) et le journal d'audit. Le fil d'activité les affiche ; une escalade apparaît comme un point d'attention.

**Rendre la main au Lead** : le bouton « Ask the Lead » sur la page du travail d'une tâche bloquée et dans le tiroir de l'agent bloqué (`POST /api/v1/tasks/{id}/lead-decision`). Il sert pour un blocage antérieur à cette version, ou après avoir répondu dans le chat. Le bouton du tiroir s'appelle désormais « Retry the failed step » : il relance l'étape telle quelle, sans réponse au blocage. Sur T-15, ce bouton a relancé l'agent, qui attendait toujours la réponse.

**Réponse sans action** : un agent qui répond sans action mais avec une raison (« Waiting for the Lead's clarification », vu sur T-15) demande en fait à bloquer. Sa réponse est lue comme un `task.block` portant cette raison, et elle va au Lead au lieu d'échouer comme réponse inutilisable.

Le mode B reste possible à tout moment : le CTO peut reprendre, réécrire, découper ou annuler la tâche lui-même avant ou après la décision du Lead.

## Vérifié

- `RealignmentIntegrationTest.theLeadSettlesTechnicalBlocksAndEscalatesProductChoices` : une question technique est répondue et le travail reprend tout seul ; le choix de produit qui suit est escaladé au CTO dans le chat du Lead, et la tâche reste bloquée. Le test vérifie aussi ce que le Lead a reçu.
- Suite complète : 49/49.

## Limites

- Le Lead décide sur ce qu'il voit : si la cause est dans un fichier que la raison ne cite pas et qui n'est pas encore commité, il ne le voit pas, et il peut escalader ou répondre à côté. L'agent signale alors un nouveau blocage, compté dans la limite de 3.
- Aucun réglage par projet ne permet encore de désactiver cette délégation (mode B strict).
