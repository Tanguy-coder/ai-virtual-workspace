# 15 — Choix automatique des modèles

Demande du CTO du 2026-11-14 : quand le quota d'un modèle est atteint (« Groq daily limit reached for openai/gpt-oss-120b: the work waits 27 min »), le système doit changer de modèle tout seul. Le changement doit être intelligent : le modèle choisi dépend du travail à faire. Et dès le recrutement, le Lead donne à chaque agent le modèle qui convient à sa mission.

Principe (sobriété, `docs/09`) : **le code décide, aucun appel de modèle n'est dépensé pour choisir un modèle.**

## Ce que le système sait d'un modèle

`ModelProfiles` (pur, testé) donne à chaque identifiant de modèle une note de 0 à 10 pour le **code** et une pour le **raisonnement**, et dit si c'est un modèle de conversation.

1. **Famille connue** (la correspondance la plus longue l'emporte) : `kimi-k2` 8 / 7,5, `gpt-oss-120b` 7,5 / 8, `llama-3.3-70b` 6 / 6,5, `claude-opus` 10 / 10… Ce sont des a priori de réputation publique, pas des mesures.
2. **Variante réduite** : `nano` −4, `lite` −2,5, `mini` / `small` / `instant` −2, `flash` / `air` −1, sauf si la famille trouvée est déjà cette variante. `gpt-5.4-nano` n'est donc pas noté comme `gpt-5`. Ce cas a été vu en production.
3. **Modèle inconnu** : taille lue dans l'identifiant (`72b` → 6), sinon 3,5 ; `coder` +1,5 pour le code, `r1` / `reasoning` +1 pour le raisonnement.
4. **Jamais choisis** : audio (`whisper`, `tts`), embeddings, modération (`guard`), images, routeurs (`groq/compound`, `openrouter/auto`), variantes `:batch` (réponse différée).

## Ce que le système observe

`ModelRouter` corrige ces a priori avec des données réelles :

| Signal | Source | Effet sur la note |
|---|---|---|
| Réponses inutilisables (JSON cassé, réponse invalide) | `model_reputation`, alimentée par chaque `completeJson` | −5 × taux, à partir de 5 observations |
| Fenêtre de contexte | `/models` (`context_window` chez Groq, `context_length` chez OpenRouter) | modèle exclu si le prompt n'y tient pas ; −1 sous 32k |
| Prix | `/models` d'OpenRouter, sinon la liste de prix de la plateforme | −0,6 × log₂(prix mixte en $ par million de tokens), au-delà de 1 $ : Opus ≈ −3,5, Sonnet ≈ −2,2 |
| Agents déjà sur ce modèle | table `agents` | −0,75 par agent : les quotas sont comptés par modèle, l'équipe se répartit |
| Modèle par défaut du workspace | `providers.default_model` | +0,5 |
| Modèle ou fournisseur hors service | mémoire de la passerelle | exclu jusqu'à la remise à zéro |

Le **besoin** vient du rôle de l'agent : raisonnement pour le Lead, l'architecture, le produit et la sécurité, code pour les autres. Les appels `LIGHT` (résumés, comptes rendus) ont leur propre classement, qui préfère les modèles moyens.

## Au recrutement : le Lead attribue les modèles

Un agent recruté sans modèle imposé par le CTO reçoit le meilleur modèle pour sa mission parmi **tous** les modèles des fournisseurs du workspace (`AgentService.assignModel`). L'événement `agent.model_bound` et le journal d'audit portent l'acteur (le Lead) et la raison, par exemple « coding 8.0/10 (known family kimi-k2) ». Le tiroir de l'agent affiche « Chosen by the Lead: … ».

- Recruter plusieurs agents d'un coup (proposition du Lead acceptée) répartit l'équipe sur plusieurs modèles, donc sur plusieurs quotas journaliers.
- Le CTO garde la main : « Apply model » fixe un modèle (`model_source = CTO`), et « Let the Lead choose » rend le choix au Lead (`POST /api/v1/agents/{id}/model/auto`).
- Les agents qui avaient déjà un modèle avant cette version sont marqués comme choisis par le CTO et gardent leur modèle.

## Pendant le travail : la bascule

Quand un appel échoue pour une raison qui rend le modèle indisponible, `ModelGateway.complete` le marque hors service. L'appel passe alors au premier modèle du classement pour le même besoin, jusqu'à 3 bascules par appel :

| Réponse | Hors service pour |
|---|---|
| 429 avec une attente de plus de 60 s (quota journalier) | le modèle, pendant l'attente annoncée |
| 402 (crédits épuisés), 401, 403 (clé refusée) | tout le fournisseur, 1 h |
| 404, 400 « decommissioned » / « model_not_found » | le modèle, 24 h |
| 413 / 429 « trop gros pour la limite par minute » | cet appel seulement |

- Les appels suivants partent directement sur le remplaçant, sans redemander au modèle épuisé.
- L'agent garde son modèle et y revient quand son quota se remet à zéro.
- Chaque bascule publie `model.rerouted` (de quel modèle vers quel modèle, pourquoi, avec le détail du classement), visible dans le fil d'activité.
- **Plancher** : le remplaçant doit avoir au moins 4,5, et pas plus de 3 points de moins que le modèle d'origine. En dessous, mieux vaut attendre qu'avoir du JSON cassé ou du code faux. Si rien ne convient, le travail attend et reprend tout seul, comme avant.

Les erreurs réseau (timeout, connexion coupée) sont réessayées sur le même modèle : 5, 10, 20, puis 40 s.

## Vérifié

- `ModelRoutingTest` : profils, classement par besoin avec la vraie liste de Groq, répartition, réputation, épuisement, prix et formats d'OpenRouter.
- `ProvidersIntegrationTest` : quota journalier → bascule, puis appel direct au remplaçant ; quota Groq puis OpenRouter sans crédits → modèle suivant chez Groq.
- En production, sur « To do list » : T-7 a quitté gpt-oss-120b épuisé sans attendre ; OpenRouter (aucun crédit acheté) a été écarté pour 1 h.

## Limites connues

- Les notes des familles sont des a priori écrits à la main ; un modèle sorti après cette liste est noté par sa taille et ses variantes, puis par sa réputation observée. Les avis de review (approuvé / refusé) ne nourrissent pas encore la réputation.
- Les marques « hors service » sont en mémoire : après un redémarrage, un premier appel au modèle épuisé est perdu pour les réapprendre.
- Un fournisseur ajouté plus tard ne déclenche pas de réattribution des agents existants ; « Let the Lead choose » le fait à la demande.
