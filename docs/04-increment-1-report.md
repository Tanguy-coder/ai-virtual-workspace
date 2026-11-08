# Incrément 1 — Socle : rapport

8 novembre 2026

## Critère de passage

> Un appel de modèle réel, compté et visible en direct.

**Atteint, avec une réserve.** Le parcours complet fonctionne contre l'application lancée (PostgreSQL et Redis réels) :
message au Lead → état `THINKING` → appel via la passerelle → réponse → coût enregistré → événements poussés en direct.
L'appel a été fait contre un faux serveur compatible OpenAI, pas contre l'API Anthropic : aucune clé Anthropic
n'est configurée dans cet environnement. L'adaptateur Anthropic est testé contre un faux serveur qui imite l'API
Messages (en-têtes, corps, lecture de l'usage). **Un premier appel avec ta propre clé reste à faire** (Settings →
AI providers → Test).

## Ce qui est livré

| Domaine | Livré |
| --- | --- |
| Identité | Inscription, connexion par session (Argon2, cookie HttpOnly, CSRF), workspaces, rôles OWNER/ADMIN/MEMBER/VIEWER |
| Projets | Création, import du cahier des charges (texte collé, fichiers .md/.txt jusqu'à 2 Mo) |
| Organisation | Catalogue de 37 profils (§3.1 du brief), recrutement manuel, Lead créé automatiquement pour chaque projet, fiche agent (profil, modèle, instructions modifiables, pause, reprise, renvoi) |
| Conversation | Discussion directe avec un agent ; le Lead reçoit le cahier des charges en contexte ; réponses traitées en arrière-plan, une à la fois par agent, reprises après redémarrage |
| Passerelle IA | Adaptateur Anthropic (SDK Java officiel, `claude-opus-5-5` par défaut, effort explicite, refus détectés, *server-side fallbacks* activés) et adaptateur compatible OpenAI (Ollama, vLLM…) ; tarifs intégrés et personnalisables ; budgets journalier et total par projet ; événement à 80 % et à 100 % |
| Événements | Journal d'événements transactionnel (outbox) → Redis Streams → WebSocket STOMP ; reprise côté client par numéro de séquence |
| Gouvernance | Coffre de secrets AES-256-GCM, journal d'audit chaîné par hachage et protégé contre la modification, file d'approbations (Inbox) |
| Interface | Navigation entre trois espaces, Inbox, jauge de budget, barre d'état temps réel, Dashboard CTO, Équipe et conversation, Documents, Activité, Réglages ; les écrans des incréments suivants affichent « Not available yet » |

## Vérification

- 9 tests d'intégration (Testcontainers : PostgreSQL et Redis réels) : parcours complet Lead + coût + événements + budget + audit, seuil de budget, adaptateur Anthropic, isolation entre workspaces, CSRF, relais Redis, audit en ajout seul, WebSocket (réception en direct, refus d'un non-membre), frontières des modules (Spring Modulith).
- Parcours manuel en HTTP via le proxy du frontend sur l'application lancée, plus la poignée de main WebSocket : 101 avec session, 401 sans session, 403 depuis une autre origine.
- **Pas de vérification visuelle dans le navigateur** : l'extension Chrome n'était pas connectée. Les écrans compilent et leurs appels API sont vérifiés, mais leur rendu n'a pas été vu.

## Défauts trouvés et corrigés

1. Le relais Redis → WebSocket perdait des événements arrivés entre deux lectures (`ReadOffset.latest`). La lecture reprend maintenant depuis l'identifiant du dernier message consommé.
2. Les budgets étaient arrondis au centime (0,008 $ → 0,01 $). Ils sont maintenant stockés avec 6 décimales.
3. L'adaptateur compatible OpenAI envoyait un corps « chunked » avec une tentative de passage en HTTP/2, que certains serveurs locaux ne lisent pas. Il envoie maintenant du HTTP/1.1 avec `Content-Length`.

## Limites connues

- Les sessions sont en mémoire : un redémarrage du backend déconnecte les utilisateurs (Spring Session JDBC à ajouter).
- Le contrôle de budget précède l'appel : des appels simultanés peuvent légèrement dépasser une limite.
- Le cahier des charges est injecté en entier dans le contexte du Lead, sans découpage. Les documents très longs coûtent cher, et au-delà de la fenêtre de contexte l'appel échoue avec un message d'erreur.
- Import limité aux formats Markdown et texte (pas de PDF ni de Word).
- Un seul humain par workspace en pratique : les invitations ne sont pas encore construites (incrément 6).
- Pas de Docker Compose pour l'application elle-même (seulement pour l'infrastructure).

## Suite

Incrément 2 : Virtual Office (PixiJS), avatars pilotés par les événements, organigramme, recrutement depuis le bureau.
