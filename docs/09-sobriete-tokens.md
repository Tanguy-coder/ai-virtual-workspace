# Sobriété en tokens — principe d'architecture (proposition)

Exigence de l'utilisateur (2026-11-09) : le système doit consommer le moins de tokens possible, sans perdre en efficacité. Ce document la traduit en règles de conception et en chantiers. À valider avant toute implémentation.

## Constat sur le code actuel (incréments 1 à 3)

Mesuré en lisant le code, pas encore sur des appels réels :

| Point | Où | Coût |
| --- | --- | --- |
| Chaque appel embarque toute la spécification (jusqu'à 120 000 caractères, ~30 k tokens) et 20 000 caractères de mémoire | `AgentRunner.context` | ~35 k tokens d'entrée **par appel**, même pour répondre « d'accord » dans le chat |
| Chaque tour de réunion renvoie la transcription complète | `MeetingService.transcript` | Croissance quadratique : 5 participants × 3 tours = 15 appels, chacun plus long que le précédent |
| Le chat renvoie les 40 derniers messages en entier | `AgentChatService` (`HISTORY_LIMIT`) | Historique intégral à chaque message |
| Aucune mise en cache du préfixe chez le fournisseur | `AnthropicAdapter`, `OpenAiCompatibleAdapter` | Le même contexte stable est facturé plein tarif à chaque appel |
| Un seul modèle par agent, quelle que soit la difficulté | `ModelBinding` | Le gros modèle rédige aussi les résumés et les classements |

Ordre de grandeur : une réunion de lancement de 5 agents sur 3 tours consomme environ 500 k tokens d'entrée, dont plus de 90 % sont le même contexte répété.

## Règles de conception

1. **Le code décide, le modèle juge.** L'orchestration, l'affectation, les dépendances, la détection de blocage, l'exécution des tests et la validation objective sont du code déterministe. On n'appelle un modèle que lorsqu'il faut du jugement ou de la rédaction. Un agent qui attend ne consomme rien (déjà le cas).
2. **Contexte minimal et ciblé.** Le context builder ne pousse plus de documents entiers :
   - un **résumé structuré** de la spécification, produit une seule fois à l'import ;
   - les **extraits pertinents**, retrouvés par recherche (pgvector), avec un budget en tokens par section ;
   - la tâche, ses critères d'acceptation et l'état de sa branche ;
   - l'agent peut demander plus via un outil (`read_spec_section`, `memory.search`) plutôt que de tout recevoir d'avance.
3. **Préfixe stable, mis en cache.** Ordre fixe du prompt, du plus stable au plus volatil : instructions du poste → résumé du projet → mémoire → tâche → messages récents. Le préfixe stable est marqué pour le cache du fournisseur (Anthropic `cache_control` ; cache automatique côté compatibles OpenAI). Une lecture en cache coûte environ 10 % du tarif normal.
4. **Résumés glissants plutôt qu'historiques intégraux.**
   - Réunions : chaque participant reçoit le résumé des tours précédents plus le dernier tour complet, au lieu de toute la transcription. Un participant qui n'a rien à ajouter répond `PASS`, ce qui coûte quelques tokens.
   - Chat et fils de discussion : résumé de la conversation, plus les N derniers messages.
   - Les résumés sont produits par un petit modèle et réutilisés.
5. **Le bon modèle pour chaque appel.** Chaque type d'appel a une classe de modèle :
   - petit modèle : résumés, classement, extraction, compte rendu, routage ;
   - modèle intermédiaire : contributions en réunion, revue simple ;
   - grand modèle : analyse de spécification, architecture, code difficile.

   Le binding par poste reste, avec un plafond de classe par type d'appel. L'effort de raisonnement suit la même logique.
6. **Sorties courtes et structurées.** `max_tokens` serré par type d'appel, JSON compact, pas de préambule ni de reformulation. Pour le code (incrément 4) : modifications par patch ou diff, lecture de fichiers par plages, recherche (grep, symboles) au lieu de déverser le dépôt, sorties d'outils tronquées (logs : erreurs seulement ; tests : échecs seulement).
7. **Pas de travail payé deux fois.** Les appels sont mémorisés par empreinte de leur entrée : une reprise ou un retry relit le résultat enregistré au lieu de rappeler le modèle (prolongement de l'idempotence existante). Les artefacts produits une fois (analyse de spec, plan, décisions) sont référencés, pas régénérés.
8. **Budgets et visibilité.**
   - Budget de tokens par tâche, en plus des budgets existants par espace, projet et agent.
   - Le tableau de bord montre le coût par livrable et la part lue depuis le cache.
   - Alerte quand une tâche dépasse son estimation.

## Chantiers proposés

| # | Chantier | Gain attendu | Moment |
| --- | --- | --- | --- |
| T1 | Mesurer une ligne de base : coût réel du parcours UC-1 et d'une réunion de lancement (`usage_records`) | Point de comparaison | Tout de suite |
| T2 | Cache du préfixe stable + ordre fixe du prompt dans les deux adaptateurs | Fort, sans changer le comportement | Tout de suite |
| T3 | Résumé de spécification à l'import + extraits par recherche dans le context builder | Très fort (supprime ~30 k tokens par appel) | Tout de suite |
| T4 | Résumés glissants pour réunions et chat, `PASS` en réunion | Fort sur les réunions | Tout de suite |
| T5 | Classe de modèle par type d'appel (petit modèle pour résumés et comptes rendus) | Moyen à fort en coût | Tout de suite |
| T6 | Mémorisation des appels par empreinte | Retries gratuits | Avec T2 |
| T7 | Outils de code sobres (patch, lecture par plages, recherche, sorties tronquées) | Décisif pour le code | Conception de l'incrément 4 |
| T8 | Budget par tâche et vue « coût par livrable » | Pilotage | Incrément 4 |

Critère de réussite : rejouer T1 après T2 à T5 et montrer la baisse sur les mêmes parcours, avec des résultats de qualité équivalente (mêmes tâches produites, comptes rendus complets). Pas d'objectif chiffré avant d'avoir la ligne de base.

## Résultats (2026-11-09) : T1 à T6 livrés

Mesure : `TokenFootprintIntegrationTest`. Il rejoue trois parcours avec une vraie spécification (`Prompt.md`, 25 000 caractères) et compte ce que la plateforme envoie au modèle : tokens estimés à 4 caractères, cache du fournisseur simulé (écriture ×1,25, lecture ×0,1, minimum 1024 tokens). Rapport dans `backend/target/token-footprint.md`.

| Parcours | Avant (tokens d'entrée) | Après (équivalent facturé) | Baisse |
| --- | ---: | ---: | ---: |
| UC-1 : spécification → tâches (8 appels) | 57 730 | 25 252 | −56 % |
| Chat : 3 messages | 19 577 | 2 186 | −89 % |
| Réunion : 4 agents, 3 tours | 98 292 | 24 373 | −75 % |
| **Total** | **175 599** | **51 811** | **−70 %** |

Ce qui a changé :
- **Contexte** (`SpecExcerpts`, `AgentRunner.context`) : seule l'analyse du Lead lit la spécification entière. Les autres appels reçoivent les passages pertinents, avec un budget par appel : 6 000 caractères par défaut, 16 000 pour la proposition d'équipe, 8 000 pour le plan, 5 000 pour le chat, aucun pour les comptes rendus. Mémoire partagée limitée à 6 000 caractères, notes personnelles à 1 500.
- **Cache du fournisseur** : le système est découpé en partie stable et partie variable, avec des points de cache sur les deux. Le chat met en cache la conversation entière. Les appels uniques (étapes du Lead, comptes rendus, réunion d'un seul tour) ne sont pas marqués : une écriture en cache coûte 25 % de plus et ne serait jamais relue.
- **Réunions** : seul le dernier tour est transmis en entier, les contributions plus anciennes sont coupées à 300 caractères. Réponse `PASS` possible à partir du 2e tour, effort de raisonnement « low », réponses limitées à 200 mots.
- **Chat** : 12 derniers messages au lieu de 40, réponse plafonnée à 4 000 tokens au lieu de 16 000, consigne de concision.
- **Modèle économique** : les comptes rendus partent sur le modèle économique du fournisseur (`claude-haiku-5-5` par défaut pour Anthropic, réglable dans Settings).
- **Mémorisation** : une requête identique dans les 24 h est resservie sans appel. Une réponse rejetée par la validation est oubliée, pour qu'une relance interroge à nouveau le modèle.
- **Suivi** : `usage_records` enregistre les tokens écrits et lus en cache, ainsi que le résultat `REUSED`. Le coût tient compte des tarifs de cache.

Le test échoue si un parcours dépasse son budget : 30 000 pour UC-1, 4 000 pour le chat, 30 000 pour la réunion.

Limites :
- Les chiffres sont des estimations : le fournisseur de test est un bouchon. Une mesure sur la facturation réelle demande une vraie clé API.
- La baisse en tokens de sortie (effort « low », réponses plus courtes) n'est pas mesurée par ce banc.
- T7 (outils de code sobres) et T8 (budget par tâche, coût par livrable) restent pour l'incrément 4.
