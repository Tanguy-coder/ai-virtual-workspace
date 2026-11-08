# AI Engineering Workspace — UX/UI

8 novembre 2026 · Étape 3 de la méthode du brief (§15). Ce document s'appuie sur `01-product-discovery.md` et `02-architecture.md`.

Le Virtual Office et le recrutement sont conçus en détail. Les autres écrans sont spécifiés au niveau nécessaire pour l'implémentation. Ce document est rédigé en français ; les libellés de l'interface sont en anglais par défaut (D11), avec une traduction française prévue.

## 1. Principes

1. **L'écran dit la vérité.** Tout ce qui bouge, change de couleur ou affiche un chiffre provient d'un événement ou d'une projection réelle. Sans donnée, l'écran affiche un état vide explicite. Une fonctionnalité pas encore livrée porte le badge « Not available yet ».
2. **Trois espaces, un seul contexte.** Le Bureau (Virtual Office), le Projet (gestion) et le Code (IDE) partagent le projet courant, la sélection et le flux d'événements. Passer de l'un à l'autre garde l'objet sélectionné : un agent sélectionné au Bureau reste sélectionné dans le Code, avec ses diffs filtrés.
3. **L'humain décide, l'interface le rend évident.** Toute action en attente de l'humain (question du Lead, proposition de recrutement, approbation) apparaît dans une seule boîte : l'**Inbox**, avec un compteur toujours visible.
4. **Un clic vers le détail, un clic vers la preuve.** Chaque statut mène à sa source : un « Testing » mène au run de tests, un « Done » mène à la validation et à ses preuves.
5. **Immersif mais sobre.** Le Bureau est le seul espace illustré. Le Projet et le Code restent des outils professionnels denses et calmes, sans animation décorative.

## 2. Navigation globale

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ◆ Workspace ▾  Projet: Banking Core ▾ │ [Office] [Project] [Code] │ ⌘K  Inbox ③  $ 12.40/50 ▮▮▮▯  ◐ │
├────┬─────────────────────────────────────────────────────────────────────────┤
│ ▤  │                                                                         │
│ ▤  │                     contenu de l'espace actif                           │
│ ▤  │                                                                         │
│ ▤  │                                                     ┌─────────────────┐ │
│    │                                                     │ Drawer agent /  │ │
│    │                                                     │ tâche (contexte)│ │
│ ⚙  │                                                     └─────────────────┘ │
├────┴─────────────────────────────────────────────────────────────────────────┤
│ ● live · seq 18452 · 7 agents actifs · 2 en attente de toi          Activity ▸ │
└──────────────────────────────────────────────────────────────────────────────┘
```

| Zone | Contenu |
| --- | --- |
| Barre du haut | Sélecteurs de workspace et de projet ; les trois espaces ; palette de commandes (`Ctrl/⌘+K`) ; Inbox avec compteur ; jauge de budget du projet (coût réel / plafond) ; thème |
| Rail gauche | Écrans de l'espace actif (voir §3) ; réglages en bas |
| Drawer droit | Détail contextuel (agent, tâche, réunion, approbation), ouvert depuis n'importe quel espace, sans changer d'écran |
| Barre d'état | État de la connexion temps réel et dernier numéro de séquence reçu, agents actifs, actions en attente, accès à la timeline d'activité |

La barre d'état affiche « reconnecting… » et grise les indicateurs live dès que la connexion WebSocket tombe. Ainsi, une animation figée n'est jamais présentée comme une activité en cours.

## 3. Inventaire des écrans

| Écran (libellé) | Espace | Rôle | Incrément |
| --- | --- | --- | --- |
| Virtual Office | Office | Bureau isométrique, agents, départements, réunions en direct | 2 |
| Org Chart | Office | Organigramme : départements, postes, rattachements | 2 |
| Recruitment | Office | Catalogue des profils, propositions du Lead, embauche | 2–3 |
| Agent Profile (drawer + page) | Tous | Profil, mission, historique, livrables, conversation, réglages | 1–2 |
| Meeting Room | Office | Réunion en cours ou passée : participants, tours, compte rendu | 3 |
| CTO Dashboard | Project | Indicateurs réels, alertes, coût, décisions récentes | 1 |
| Projects | Project | Liste et création de projets, import du cahier des charges | 1 |
| Lead Briefing | Project | Analyse du Lead, questions-réponses, note d'architecture | 3 |
| Tasks | Project | Kanban, liste, timeline (Gantt), graphe de dépendances | 3 |
| Inbox | Global | Questions, propositions, approbations en attente | 1 |
| Activity Timeline | Project | Flux chronologique filtrable de tous les événements | 1 |
| Code Workspace | Code | Arborescence, éditeur, diffs par agent, terminal en lecture des sandboxes | 4 |
| Git | Code | Branches, commits, merge requests, conflits | 4 |
| Testing Center | Code | Runs de tests, résultats, couverture, tendances | 4–5 |
| Security Center | Code | Scans, constats, criticité, preuves, corrections | 5 |
| Deployment Center | Code | Environnements, releases, déploiements, approbations | 6 |
| Settings | Global | Fournisseurs IA et clés, budgets, politiques, intégrations Git, membres | 1 |

## 4. Virtual Office

### 4.1 Objectif

En moins de cinq secondes, le développeur doit voir qui travaille sur quoi, qui est bloqué, quelle réunion se tient et où son intervention est attendue.

### 4.2 Plan d'étage

Un étage isométrique unique, organisé en zones fixes. Chaque département occupe une zone dont la taille suit son nombre de postes (de 2 à 12 bureaux). Les zones vides restent visibles mais grisées, avec un bouton « Hire » qui ouvre le recrutement filtré sur ce département.

```
            ┌───────────────────────────── ÉTAGE ─────────────────────────────┐
            │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
            │  │ CTO OFFICE   │  │ ARCHITECTURE │  │ PRODUCT MGMT │           │
            │  │   (Lead)     │  │  ▣ ▣         │  │  ▣           │           │
            │  └──────────────┘  └──────────────┘  └──────────────┘           │
            │  ┌──────────────────────┐  ┌──────────────────────┐  ┌────────┐ │
            │  │ BACKEND              │  │ FRONTEND             │  │MEETING │ │
            │  │  ▣ ▣ ▣ ▣             │  │  ▣ ▣                 │  │ ROOM A │ │
            │  └──────────────────────┘  └──────────────────────┘  ├────────┤ │
            │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐│MEETING │ │
            │  │ QA & TESTING │  │ SECURITY     │  │ DEVOPS       ││ ROOM B │ │
            │  │  ▣ ▣         │  │  ▣           │  │  ▣           │└────────┘ │
            │  └──────────────┘  └──────────────┘  └──────────────┘           │
            │  [ENTRÉE / HR]                                                  │
            └─────────────────────────────────────────────────────────────────┘
```

- **CTO Office** : le bureau du Lead, au plus près de l'entrée de la vue. Le bureau du développeur humain y figure aussi, comme siège vide « You » qui s'allume quand on participe à une réunion.
- **Meeting Rooms** : deux salles par défaut ; une troisième apparaît si trois réunions se tiennent en même temps.
- **Entrée / HR** : point d'arrivée des agents recrutés et de départ des agents retirés.

### 4.3 Avatars

Un avatar est un personnage isométrique stylisé, sans visage réaliste, identifiable par trois éléments :

1. **Couleur du département** : vêtement et liseré du bureau (palette au §8).
2. **Badge de poste** : pictogramme sur l'épaule (accolades pour backend, fenêtre pour frontend, bouclier pour sécurité, fiole pour QA, engrenage pour DevOps, plan pour architecture, boussole pour produit, étoile pour Lead).
3. **Étiquette nom** : visible au survol, toujours visible au zoom « département ».

Le même agent porte le même avatar dans toute l'application (pastille dans le Kanban, auteur d'un commit, participant d'une réunion).

### 4.4 États visibles

Chaque état correspond exactement à l'événement défini dans l'architecture (§ États visibles d'un agent). Les animations sont des boucles courtes ; elles s'arrêtent dès que l'état change.

| État | Où est l'avatar | Animation | Indicateur au-dessus de la tête |
| --- | --- | --- | --- |
| Available | À son bureau | Immobile, écran éteint | Aucun |
| Thinking | À son bureau | Main au menton, écran qui pulse lentement | Bulle « … » |
| Developing | À son bureau | Frappe au clavier, lignes de code sur l'écran | Icône `</>` + nom du fichier en cours au survol |
| Testing | À son bureau | Écran avec barre de progression | Icône fiole, compteur `42/57` réel |
| Reviewing | À son bureau | Écran en deux colonnes (diff) | Icône loupe |
| In meeting | Dans la salle de réunion | Assis autour de la table ; celui qui parle est surligné | Icône bulle double |
| Blocked | À son bureau, debout | Immobile | Pastille rouge « ! » + raison en une ligne au survol |
| Awaiting validation | À son bureau | Immobile, dossier posé sur le bureau | Pastille sablier |
| Paused | À son bureau | Désaturé à 40 % | Icône pause |
| Done | À son bureau | Brève coche (1 s) puis Available | Coche verte, 3 s |

Les déplacements (vers une salle de réunion, depuis l'entrée) durent 1,2 s au maximum, puis l'avatar est en place : l'animation ne retarde jamais l'affichage de l'état.

### 4.5 Interactions

| Geste | Résultat |
| --- | --- |
| Survol d'un avatar | Carte : nom, poste, état, tâche en cours, durée dans l'état |
| Clic sur un avatar | Drawer Agent (§5), l'agent reste surligné |
| Double-clic | Ouvre la tâche en cours dans le Code Workspace, filtrée sur ses changements |
| Clic sur une zone de département | Liste de ses agents et de leurs tâches ; bouton « Hire » |
| Clic sur une salle de réunion | Meeting Room de la réunion en cours |
| Glisser une tâche (depuis le panneau Tasks) sur un avatar | Proposition d'affectation (Mode B) ; confirmation dans le drawer |
| Sélection multiple (Maj + clic) puis « Convene meeting » | Création d'une réunion avec ces participants |
| Molette / pincement | Trois niveaux de zoom : étage, département, bureau |
| `F` | Filtre : afficher seulement les agents bloqués, en attente de moi, ou d'un département |

### 4.6 Liens de collaboration

Un échange réel entre deux agents (message, demande de revue, dépendance) dessine un trait animé entre leurs bureaux pendant 4 s, de la couleur du type de message. Le panneau « Conversations » liste les mêmes échanges en texte. Le trait est donc toujours lié à un message consultable.

### 4.7 Replay

Une réglette temporelle en bas du Bureau permet de rejouer la journée à partir du journal d'événements (×1, ×10, ×60). En mode replay, un bandeau « Replay — 14:32 » remplace l'indicateur live pour qu'il n'y ait aucune confusion avec le temps réel.

### 4.8 États particuliers

- **Projet sans agent** : étage vide, Lead seul dans son bureau, carte centrale « Brief your Lead » ou « Hire manually ».
- **Connexion perdue** : avatars figés et désaturés, bandeau « Live updates paused ».
- **Agent supprimé ou remplacé** : il sort par l'entrée, son successeur entre ; l'historique reste attaché à l'ancien profil.

### 4.9 Accessibilité et performance

- Vue alternative « List » (bouton en haut à droite) : un tableau des agents avec les mêmes états, accessible au clavier et aux lecteurs d'écran.
- `prefers-reduced-motion` : les déplacements deviennent instantanés et les boucles d'animation sont remplacées par les icônes seules.
- Les états ne reposent jamais sur la couleur seule : chaque état a son icône.
- Cible : 60 images/s avec 60 agents sur un portable récent ; au-delà, les animations des agents hors écran sont suspendues.

## 5. Fiche agent (drawer)

```
┌──────────────────────────────────────┐
│ [avatar]  Backend Architect 01       │
│ Senior Backend Engineer · Backend    │
│ ● Developing — T-142 Accounts API    │
│ [Message] [Assign task] [Pause] [⋯]  │
├──────────────────────────────────────┤
│ Profile | Mission | History | Output │
│ Chat | Settings                      │
├──────────────────────────────────────┤
│ Simulated experience: 12 years ⓘ     │
│ Skills: Java · Spring Boot · Kafka … │
│ Responsibilities: …                  │
│ Model: claude-… · $3.12 today        │
└──────────────────────────────────────┘
```

| Onglet | Contenu |
| --- | --- |
| Profile | Identité, poste, département, compétences, niveau, responsabilités, mention « expérience simulée » avec infobulle explicative |
| Mission | Tâche en cours, critères d'acceptation cochés ou non, branche, dernier pas de la boucle (outil appelé, durée) |
| History | Tâches passées, résultats de validation, revues reçues et données |
| Output | Livrables : commits, fichiers, rapports, décisions enregistrées en mémoire |
| Chat | Conversation directe avec l'agent ; les messages arrivent dans sa boîte et sont traités au prochain pas |
| Settings | Modèle et fournisseur, outils autorisés, politiques, budget, instructions système (lecture et modification avec historique) |

Les actions « Pause », « Replace » et « Dismiss » demandent une confirmation qui précise l'effet sur la tâche en cours (suspendue, réaffectée, ou remise à l'état prêt).

## 6. Recrutement

Deux entrées mènent au même écran : la proposition du Lead (Mode A) et le catalogue (Mode B).

### 6.1 Revue de la proposition du Lead

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Team proposal for "Banking Core" — by Lead · 8 roles · est. $38/day          │
│ Why this team: microservices + high load + regulated data → …               │
├─────────────────────────────────────────────────────────────────────────────┤
│ ☑ Software Architect   ×1  Architecture   "Define service boundaries…"  [✎] │
│ ☑ Backend Engineer     ×2  Backend        "Accounts, Wallet, Txn svc…"  [✎] │
│ ☑ Frontend Engineer    ×1  Frontend       "Customer & back-office UI"   [✎] │
│ ☑ Database Engineer    ×1  Backend        "Schemas, migrations, perf"   [✎] │
│ ☑ DevOps Engineer      ×1  DevOps         "Containers, CI, envs"        [✎] │
│ ☑ QA Engineer          ×1  QA             "Test strategy, E2E"          [✎] │
│ ☐ Security Engineer    ×1  Security       "Threat model, scans"         [✎] │
├─────────────────────────────────────────────────────────────────────────────┤
│ + Add role                     [Ask Lead to revise]  [Reject]  [Hire 7 ▸]   │
└─────────────────────────────────────────────────────────────────────────────┘
```

- Chaque ligne affiche la justification du Lead, reliée au passage du cahier des charges qui la motive (clic pour voir la citation).
- Le crayon ouvre la configuration du poste (§6.3), préremplie par le Lead.
- Le coût affiché est une **estimation** étiquetée comme telle, calculée à partir des tarifs des modèles choisis et d'un volume supposé. Le coût réel apparaîtra ensuite dans le dashboard.
- « Ask Lead to revise » ouvre un champ de commentaire ; la nouvelle proposition remplace l'ancienne, avec un diff visible entre versions.
- « Hire » crée les agents. Chaque création émet `agent.hired`, et l'avatar entre par l'entrée du Bureau pour rejoindre son bureau.

### 6.2 Catalogue

- Grille de cartes par famille de métiers (Management, Backend, Frontend & Mobile, Quality & Security, Infrastructure, Other), avec recherche et filtres par compétence.
- Une carte affiche : poste, pictogramme, compétences clés, modèle par défaut, outils par défaut.
- « Create role template » permet d'étendre le catalogue (le catalogue doit être extensible, §3.1 du brief).

### 6.3 Configuration d'un poste avant embauche

Formulaire en une colonne, avec un aperçu de l'avatar à droite :

1. Nom d'agent (suggestion modifiable) et département.
2. Niveau (Junior, Mid, Senior, Principal) et expérience simulée.
3. Compétences (étiquettes) et responsabilités.
4. Modèle IA : fournisseur, modèle, avec coût par million de tokens affiché.
5. Outils et permissions : cases préremplies par le profil type ; toute permission sensible est signalée.
6. Budget quotidien et nombre de tâches simultanées.
7. Instructions système : générées depuis le profil type, modifiables, avec un aperçu du rendu final.

## 7. Autres écrans

| Écran | Contenu clé | Interaction principale |
| --- | --- | --- |
| CTO Dashboard | Ligne de KPI : projets actifs, agents (disponibles / en mission / bloqués), tâches (faites / en cours / bloquées), tests (réussis / échoués), vulnérabilités par criticité, coût du jour et cumulé vs budget. Ensuite : progression par projet, alertes, décisions récentes, mini-timeline | Chaque KPI ouvre la liste filtrée qui le compose |
| Org Chart | Arbre : CTO humain → Lead → responsables → agents, regroupé par département | Glisser un agent pour changer son rattachement (proposition confirmée) |
| Lead Briefing | Colonne gauche : le cahier des charges ; colonne droite : analyse du Lead, questions numérotées avec champs de réponse, note d'architecture | Répondre question par question ; « Approve architecture » |
| Tasks | Kanban dont les colonnes suivent le cycle de vie de la tâche ; vues List, Timeline (Gantt avec dépendances) et Graph | Une carte ouvre le drawer tâche : critères, agent, branche, revues, tests, validation, historique |
| Meeting Room | Ordre du jour, participants (avatars), tours de parole en fil, champ « Speak as CTO » ; à la fin : compte rendu, décisions, désaccords, actions avec responsable et échéance | Transformer une action en tâche en un clic |
| Inbox | Éléments en attente de l'humain, triés par impact : approbations, questions, propositions de recrutement, demandes de revue humaine | Approuver / refuser avec motif, sans quitter l'Inbox |
| Activity Timeline | Flux d'événements groupés par minute, filtres par agent, type, tâche | Chaque ligne mène à l'objet source |
| Code Workspace | Arborescence, éditeur Monaco (lecture et écriture humaine sur une branche dédiée), sélecteur « Changes by agent / by task », terminal en lecture des sandboxes, problèmes et logs | Basculer d'un diff à la revue correspondante |
| Git | Graphe des branches, merge requests avec statut de revue, de tests et de validation, conflits détectés | Merge seulement si la validation est verte, avec approbation si la politique l'exige |
| Testing Center | Runs par branche, résultats par suite, tests instables, couverture, durée | Relancer un run ; ouvrir la sortie brute |
| Security Center | Constats par criticité avec preuve (fichier et ligne, requête, dépendance et CVE), statut, recommandation, tâche de correction liée | Créer la tâche de correction ; accepter un risque avec motif (journalisé) |
| Deployment Center | Environnements, version déployée, historique, pipeline en cours | Lancer un déploiement, qui passe toujours par une approbation |
| Settings | Fournisseurs et clés (saisie seule, jamais réaffichées), budgets, politiques par poste, intégrations GitHub et GitLab, membres | Tester une clé ; simuler une politique sur un outil |

## 8. Système visuel

| Élément | Choix |
| --- | --- |
| Thème | Sombre par défaut (usage prolongé, proche d'un IDE), thème clair complet |
| Typographie | Inter pour l'interface, JetBrains Mono pour le code, les identifiants et les chiffres alignés |
| Échelle | 12 / 13 / 14 / 16 / 20 / 24 px ; espacements par pas de 4 px |
| Couleurs de département | 9 teintes distinctes, à contraste égal, utilisées uniquement pour l'identité (avatar, liseré, pastille) : Direction, Architecture, Product, Backend, Frontend, QA, Security, DevOps, Other |
| Couleurs d'état | Sémantiques et réservées : vert = validé, ambre = en attente, rouge = bloqué ou critique, bleu = actif. Jamais réutilisées pour les départements |
| Composants | Bibliothèque unique (boutons, cartes, drawer, tableaux, badges d'état, jauges) en React + Tailwind, documentée dans Storybook |
| Mouvement | 150 ms pour l'interface, 1,2 s maximum pour les déplacements d'avatars, courbes douces ; aucun mouvement sans événement |
| Illustration du Bureau | Isométrique plat, formes simples, ombres légères ; sprites produits en SVG puis rastérisés en atlas pour PixiJS |

## 9. Responsive

| Largeur | Comportement |
| --- | --- |
| ≥ 1280 px | Expérience complète : rail, contenu, drawer côte à côte |
| 768–1279 px (tablette) | Drawer en superposition ; Bureau zoomable au toucher ; Code Workspace en lecture et revue |
| < 768 px (téléphone) | Inbox, Dashboard, Tasks (liste), Meeting Room en lecture, approbations. Le Bureau devient la vue « List » des agents |

## 10. Points à valider

| # | Question | Recommandation |
| --- | --- | --- |
| U1 | Style des avatars : personnages stylisés, silhouettes abstraites (pions) ou pixel-art | Personnages stylisés isométriques, sans visage réaliste |
| U2 | Thème par défaut | Sombre |
| U3 | Langue de l'interface au lancement | Anglais, avec la traduction française dès l'incrément 2 |
| U4 | Le développeur humain a-t-il un avatar au Bureau ? | Oui, un siège « You » dans le CTO Office, présent en réunion quand il intervient |
| U5 | Prototype interactif | **Refusé** par le CTO le 8 novembre 2026 : passage direct à l'implémentation. U1 à U4 appliqués selon la recommandation. |
