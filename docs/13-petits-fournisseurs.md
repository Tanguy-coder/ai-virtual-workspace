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

Quand un plafond de sortie est connu et bas (< 4 000), le prompt de l'agent le lui indique : réponses courtes, fichiers créés en plusieurs fois (squelette puis `file.edit`). Les plafonds appris sont en mémoire : après un redémarrage, un seul aller-retour suffit à les réapprendre.

Les erreurs qui ne relèvent pas d'une limite (clé invalide, modèle inconnu, réseau) ne sont pas réessayées par la passerelle ; le worker garde ses propres reprises.

## Limite connue

Un modèle à petite fenêtre de contexte (par exemple `allam-2-7b`, 4 096 tokens) ne peut pas tenir le prompt d'une étape de développement (outils, règles, tâche, état du dépôt). Pour les agents qui écrivent du code, choisir un modèle d'au moins 32k de contexte.

## Vérification

`RealignmentIntegrationTest.smallProvidersRateLimitsAndNativeToolCallsAreHandled` rejoue ces réponses avec les corps réels : le message au Lead aboutit après un 429 « too large » puis un 429 d'attente (`max_tokens` 4000 → 1000), le message suivant part directement à 1000, le plafond du modèle puis la limite de crédits ramènent la requête à 800 puis 630, et un appel d'outil natif bloque bien la tâche avec la raison donnée par le modèle. Suite backend complète : 25 tests au vert.
