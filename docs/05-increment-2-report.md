# Incrément 2 — Organisation virtuelle : rapport

8 novembre 2026

## Critère de passage

> État d'avatar piloté par un événement réel.

**Atteint et vu à l'écran.** Un vrai message envoyé au Lead (traité par un faux serveur de modèle local, lent exprès) a
produit l'événement `agent.state_changed` → THINKING. Dans le Virtual Office, le Lead affiche alors sa bulle animée et son
écran allumé ; le dashboard le liste dans « Working right now ». Les deux reviennent à l'état disponible à la réponse.
Capture prise avec Chrome sans interface (Playwright), l'extension Chrome n'étant pas disponible.

## Ce qui est livré

| Domaine | Livré |
| --- | --- |
| Virtual Office | Étage isométrique PixiJS : 9 départements, bureau du CTO, 2 salles de réunion, entrée ; zones colorées par département avec effectif ; bureaux et postes de travail ; zoom à la molette, déplacement, recentrage |
| Avatars | Couleur du département, étoile pour le Lead, nom affiché au survol, à la sélection ou de près (UX §4.3) ; états : disponible (écran éteint), en réflexion (bulle animée, écran allumé), en pause (estompé, icône) ; un agent recruté pendant qu'on regarde entre par la porte |
| Accessibilité | Vue « List » du bureau (tableau des agents et de leurs états), légende des états, `prefers-reduced-motion` respecté (aucun mouvement, icônes seules) |
| Recrutement | Catalogue filtrable (recherche, famille, département), configuration complète du poste avant embauche (nom, département, niveau, expérience simulée, compétences, responsabilités, modèle), création de profils personnalisés propres au workspace ; « + Hire » sur une zone vide ouvre le catalogue filtré sur ce département |
| Organigramme | CTO humain → Lead → départements et agents, avec état en direct ; changement de département depuis la fiche agent (événement + audit) |
| Fiche agent | Tiroir global ouvert depuis le bureau, l'équipe, l'organigramme ou le dashboard : conversation, profil, réglages (modèle, département, instructions, pause, renvoi avec confirmation) |
| Design | Nouveau système visuel : surfaces en couches, halo discret, accent indigo, icônes Lucide, chiffres tabulaires, navigation latérale avec sélecteur de projet, sélecteur d'espaces, jauge de budget, Inbox ; dashboard, activité, projets et équipe redessinés |

## Vérification

- Backend : 10 tests d'intégration verts (nouveau : recrutement configuré, profils personnalisés isolés par workspace, changement de département et ses événements).
- Frontend : 5 tests unitaires sur la géométrie du bureau (projection, zones sans chevauchement, bureaux dans leur zone, placement stable, aucun agent caché au-delà de la capacité), vérification de types, build de production.
- Captures de tous les écrans principaux et contrôle d'absence d'erreur à l'exécution sur chacun.

## Défauts trouvés et corrigés

1. La page Équipe plantait entièrement (écran vide) : un effet React renvoyait la valeur de `scrollIntoView`, qui est une Promise dans Chrome récent.
2. Étiquettes de zones illisibles et superposées aux avatars : déplacées au-dessus des murs, dans des pastilles.
3. Noms d'agents qui se chevauchaient en vue d'ensemble : affichés au survol et à partir du zoom « département ».
4. Filtres du recrutement empilés et liaisons flottantes de l'organigramme sur deux lignes : corrigés.

## Limites connues

- Pas de rejeu de la journée (UX §4.7) ni de traits de collaboration entre agents (§4.6) : ils dépendent des échanges entre agents (incrément 3).
- Les salles de réunion sont affichées vides (« no meeting ») : les réunions arrivent à l'incrément 3.
- La fiche agent n'a été vue qu'en mode sombre ; le mode clair compile, mais je ne l'ai pas vérifié en capture.
- Le recrutement se fait à la main ; la proposition d'équipe justifiée par le Lead fait partie de l'incrément 3.
- Les sessions restent en mémoire : un redémarrage du backend déconnecte.

## Suite

Incrément 3 : analyse du cahier des charges par le Lead, proposition d'équipe, tâches et Kanban, messagerie entre agents, mémoire partagée, réunions.
