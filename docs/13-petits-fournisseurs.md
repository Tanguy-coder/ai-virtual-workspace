# 13 — Petits fournisseurs (Groq, OpenRouter)

Demande du CTO du 2026-11-10 : un gros projet doit pouvoir avancer avec un petit fournisseur. Les journaux du projet « To do list » montraient des étapes perdues pour des raisons de limites, pas d'erreurs du modèle. Toutes ces réponses sont maintenant traitées dans `ModelGateway` ; les messages d'erreur ci-dessous sont ceux qui ont été réellement reçus.

| Réponse du fournisseur | Avant | Maintenant |
|---|---|---|
| 429 « Rate limit reached … Please try again in 7.9s » (tokens par minute, Groq) | étape en échec | attente du délai indiqué (ou de l'en-tête `Retry-After`), puis nouvel essai ; jusqu'à 4 fois, 60 s d'attente au plus |
| 429 « Request too large … (OTPM): Limit 1000, Requested 1516 » | échec à chaque essai | nouvel essai avec `max_tokens` = la limite ; le plafond est retenu pour ce modèle, les appels suivants partent directement à la bonne taille |
| 429 « Request too large … (TPM): Limit L, Requested R » | échec | `max_tokens` réduit de l'excédent ; si la réponse ne peut plus être utile (< 512 tokens), message clair : choisir un modèle aux limites plus hautes |
| 400 « `max_tokens` must be less than or equal to `512` » | échec | plafond du modèle retenu, nouvel essai |
| 402 « can only afford 2395 » (crédits OpenRouter) | échec | nouvel essai à 90 % de ce que les crédits permettent ; sous 512, message « crédits presque épuisés » |
| 402 `in_flight_budget_exhausted` | échec | attente puis nouvel essai, comme une limite de débit |
| 400 `tool_use_failed` « Tool choice is none, but model called a tool » (Groq) | étape en échec | la génération du modèle (`failed_generation`) est gardée ; l'appel natif `{"name", "arguments"}` est lu comme l'action correspondante |

| 413 « Request too large … (TPM): Limit 8000, Requested 9388 », et toujours 9388 après réduction de `max_tokens` | 4 essais inutiles puis échec brut | c'est le prompt lui-même qui dépasse : l'essai s'arrête avec une raison claire, la limite par minute est retenue, et l'essai suivant du worker construit un prompt allégé |

**Prompt allégé.** Quand la limite par minute d'un modèle est connue, une étape de développement doit tenir dedans en une requête : sortie à 1/5 de la limite (8 000 → 1 600 tokens), et prompt construit au premier des trois niveaux qui tient (environ 3 caractères par token) : moins de spécification et de mémoire, historique réduit aux 20, 10 puis 6 dernières entrées (les plus anciennes sont résumées par l'état du dépôt), sorties d'outils tronquées. Sans limite connue, rien ne change.

Quand un plafond de sortie est connu et bas (< 4 000), le prompt de l'agent le lui indique : réponses courtes, fichiers créés en plusieurs fois (squelette puis `file.edit`). Les plafonds et limites appris sont en mémoire : après un redémarrage, un aller-retour (ou un essai de job) suffit à les réapprendre.

Les erreurs qui ne relèvent pas d'une limite (clé invalide, modèle inconnu, réseau) ne sont pas réessayées par la passerelle ; le worker garde ses propres reprises.

## Sandboxes et réponses mal formées

- **Réseau des installations.** Une sandbox démarre sur le réseau `none`, que Docker refuse de combiner avec un autre : le `docker network connect bridge` échouait sans bruit, et `npm install` tournait hors ligne (`EAI_AGAIN`). La sandbox quitte maintenant `none` pour la durée de l'installation, puis y revient. `SandboxNetworkTest` le vérifie (en ligne pendant `npm install`, hors ligne après) et échoue sur l'ancien code.
- **Montage du dépôt quand la plateforme tourne dans Docker.** Voir le commit `1ab3204` : les sandboxes montent le sous-dossier du volume `aiw_aiwdata`.
- **JSON des petits modèles.** La lecture accepte les retours à la ligne bruts dans les chaînes, les virgules finales, les guillemets simples et les commentaires. Une réponse encore inutilisable après sa correction ne bloque plus la tâche : l'agent reçoit l'erreur et le début de sa réponse à l'étape suivante, avec la consigne de rester court. La tâche n'est bloquée qu'après 3 réponses inutilisables d'affilée. Le message d'erreur n'affiche plus le bruit « at [Source: REDACTED… ».

## Limite connue

Un modèle à petite fenêtre de contexte (par exemple `allam-2-7b`, 4 096 tokens) ne peut pas tenir le prompt d'une étape de développement (outils, règles, tâche, état du dépôt). Pour les agents qui écrivent du code, choisir un modèle d'au moins 32k de contexte.

## Vérification

`RealignmentIntegrationTest.smallProvidersRateLimitsAndNativeToolCallsAreHandled` rejoue ces réponses avec les corps réels : le message au Lead aboutit après un 429 « too large » puis un 429 d'attente (`max_tokens` 4000 → 1000), le message suivant part directement à 1000, le plafond du modèle puis la limite de crédits ramènent la requête à 800 puis 630, et un appel d'outil natif bloque bien la tâche avec la raison donnée par le modèle. `ModelGatewayRateLimitTest` lit les réponses réelles sans réseau, dont le 413 au « Requested » inchangé. Suite backend complète : 29 tests au vert.

## Appels d'outils natifs dans une réponse normale (gpt-oss via OpenRouter, 2026-11-11)

T-14 était bloquée sur « No JSON object was found in the reply ». gpt-oss renvoyait une réponse 200 avec `content` vide et des `tool_calls` natifs, alors qu'aucun outil n'était proposé. De plus, l'extrait montré à l'agent était vide, si bien qu'il ne voyait que la consigne de la plateforme.

- **Lecture des appels natifs :** l'adaptateur OpenAI-compatible les lit (`{"tool_calls": [{"name","arguments"}]}`). L'étape les convertit en actions, et les noms à underscore (`file_write`) deviennent nos noms (`file.write`).
- **Réponses coupées :** `finish_reason: "length"` est traité comme une coupure par `max_tokens`, avec le message « cut off by the token limit ».
- **Réponse vide :** elle est signalée comme telle (« The reply was empty »).
- **Trop d'actions :** une réponse de plus de 5 actions n'est plus rejetée. Les 5 premières s'exécutent, et l'agent est informé que les suivantes n'ont pas été lancées.
- **Compteur de réponses inutilisables :** il repart à zéro après un blocage. Une reprise donne donc à nouveau 3 essais, au lieu d'un seul.

Test : `RealignmentIntegrationTest.nativeToolCallsInAnOkReplyAndTooManyActionsAreRead`.

## Quota quotidien : attendre au lieu de bloquer (2026-11-12)

Constaté sur « To do list » : T-24, T-29 et T-30 se sont bloquées sur « Provider error 429 … tokens per day (TPD): Limit 200000, Used 198681 … try again in 7m45s ». Sur Groq, l'offre gratuite donne 200 000 tokens par jour et par modèle, partagés par tous les agents qui l'utilisent.

- **La passerelle** (`ModelGateway.afterRateLimit`) lit le délai de Groq, y compris en heures (« 1h2m3s »).
  - Délai de 60 s ou moins : elle attend et rappelle, comme avant.
  - Délai plus long : elle lève une erreur 429 claire, qui porte le délai (`retryAfterSeconds`), par exemple « Groq daily limit reached for … : the work waits 8 min and resumes by itself ».
- **Le worker** (`AgentWorker.defer`) remet alors le job en file jusqu'à la fin du délai, plafonnée à 24 h.
  - La tentative ne compte pas.
  - Il publie l'événement `job.deferred`.
  - La tâche n'est pas bloquée : le run reste `RUNNING`, avec une étape « Waiting: … ».
- **Pour éviter l'attente** : répartir les agents sur plusieurs modèles, chacun ayant son propre quota chez Groq, ou sur un autre fournisseur.
- **Tests** :
  - `ModelGatewayRateLimitTest.aDailyLimitTellsHowLongToWait` vérifie la lecture du délai ;
  - `RealignmentIntegrationTest` vérifie l'étape « Waiting: » et que la tâche n'est pas bloquée.
