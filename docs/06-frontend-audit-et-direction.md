# AI Engineering Workspace — Audit du frontend et direction artistique

Étapes 1 et 2 de `Frontend.md`. Aucun code de l'application n'est modifié avant la validation de la direction et des cinq maquettes.

Planche visuelle de la direction : `docs/images/direction-board.png` (source : `docs/design/direction-board.html`).

## 1. Audit du frontend existant

Captures de référence : Dashboard, Virtual Office, Team, Recruitment, Org Chart, Projects (1440×900, thème sombre).

### 1.1 Ce qui est sain et sera conservé

- **Stack** : React 18, TypeScript, Vite, TanStack Query, zustand, react-router, Tailwind 4 avec tokens CSS. Elle convient et ne justifie pas de refonte.
- **Couche données** : `api/client.ts`, `api/types.ts`, `realtime/realtime.ts` (STOMP, rejeu par seq, invalidation par type d'événement). Les contrats API et les routes restent identiques.
- **Logique métier côté client** : `lib/events.ts`, `lib/tasks.ts` (ordonnancement testé), `office/layout.ts` (géométrie testée), les mutations des pages. La refonte change la présentation, pas ces modules.
- **Principe « aucune fausse activité »** : déjà appliqué partout et à garder strictement.

### 1.2 Problèmes constatés

| # | Problème | Où | Effet |
| --- | --- | --- | --- |
| A1 | Identité générique : fond quasi noir, accent violet, dégradés sur les boutons et avatars, halo en fond | Partout | L'application ressemble à un template « AI SaaS » ; rien n'est reconnaissable |
| A2 | Tout est une carte bordée et arrondie : KPI, activité, agents, rôles, panneaux | Dashboard, Team, Recruitment | Pas de hiérarchie : tout a le même poids ; l'écran est un damier |
| A3 | KPI en rangée de 5 grandes cartes avec icône colorée | Dashboard | Beaucoup de surface pour 5 chiffres ; la seule information urgente (budget sans prix, agents bloqués) est un bandeau perdu entre deux blocs |
| A4 | Même gabarit de page partout (eyebrow coloré, gros titre, sous-titre, grille de cartes) | Toutes les pages | Les espaces Office, Project et Code ne se distinguent pas ; on ne sait pas où l'on est |
| A5 | Navigation à deux niveaux redondante : 3 espaces en haut + rail à gauche qui change selon l'espace | Shell | Le rail change de contenu quand on change d'onglet en haut, la position n'est jamais stable |
| A6 | Le Virtual Office est une page dans le cadre du dashboard : sidebar, en-tête de page et légende en bas lui prennent ~35 % de l'écran | Office | L'expérience signature est réduite à une illustration au centre d'une page |
| A7 | Le bureau lui-même : sol noir, zones délimitées par des contours néon, personnages-pions sans visage ni posture, étiquettes minuscules (9 px) en capitales | Office | Lecture difficile ; on ne distingue pas un agent qui travaille d'un agent disponible sans légende |
| A8 | Annuaire des agents en grille de cartes identiques, avec le même badge d'alerte (« No model configured ») répété 9 fois | Team | Le message utile (« 9 agents n'ont pas de modèle ») devient du bruit visuel |
| A9 | Catalogue de recrutement en 3 colonnes de cartes et formulaire vide à droite tant qu'aucun rôle n'est choisi | Recruitment | Le panneau de droite occupe un tiers de l'écran pour dire « choisissez un rôle » |
| A10 | Densité incohérente : textes de 11 à 12 px gris sur gris pour les données, titres de 24 px | Partout | Les données (le cœur d'un outil d'ingénierie) sont les moins lisibles |
| A11 | Icônes décoratives devant chaque KPI, chaque item de menu, chaque carte | Partout | Bruit ; les icônes n'aident pas à décider |
| A12 | Aucun état de chargement soigné (texte « Loading… »), pas de palette de commandes, pas de notifications | Partout | Finition insuffisante pour un produit premium |
| A13 | Thème clair non travaillé (simple inversion des tokens) | Partout | Le thème clair n'est pas un vrai thème |
| A14 | Mobile : le rail disparaît, aucune navigation de remplacement | Shell | L'application est inutilisable sur téléphone |

### 1.3 Problèmes structurels

- `Shell.tsx` mélange navigation, temps réel, budget, thème et tiroirs : à découper.
- `components/ui.tsx` impose un seul style de bloc (`Card`) ; il manque les primitives d'un outil dense : table, liste, barre d'outils, inspecteur, menu, popover, tooltip, toast, palette de commandes, squelettes de chargement.
- Le Virtual Office (`OfficeScene.ts`) est un seul fichier de 500 lignes qui dessine sol, bureaux, personnages, étiquettes et caméra. Il faut en faire un module à part (voir §2.7).

## 2. Direction artistique — « Studio Model »

### 2.1 Idée

Le produit est une entreprise d'ingénierie qu'on observe et qu'on dirige. La métaphore retenue est celle de la **maquette d'architecte et de la salle de contrôle d'ingénierie** :

- les espaces de travail (direction, projet, code) ont la précision calme d'un instrument : papier, graphite, encre, données en monospace ;
- le Virtual Office est une **maquette d'agence à l'échelle** : un plateau mat posé sur la table, avec des figurines sobres. On regarde une vraie entreprise en réduction, pas un jeu vidéo ni un écran néon.

Une seule couleur vive porte la marque : un **orange signal**, celui des repères de chantier et des crayons de relecture. Elle désigne ce qui est vivant ou demande ton attention. Tout le reste est minéral.

### 2.2 Palette

| Rôle | Clair | Sombre | Usage |
| --- | --- | --- | --- |
| Papier (surface principale) | `#F5F3EF` | `#141518` | Fond des espaces de travail |
| Feuille (surface élevée) | `#FFFFFF` | `#1B1C20` | Inspecteur, menus, éditeur |
| Graphite (structure) | `#1C1D21` | `#0E0F11` | Rail de navigation, barre de statut, en-tête du bureau |
| Minéral 1 / 2 / 3 | `#E6E3DD` `#CFCBC3` `#8F8B84` | `#26272C` `#34363C` `#7C7F88` | Séparateurs, bordures utiles, texte secondaire |
| Encre (texte) | `#18191C` | `#ECEBE8` | Texte principal |
| **Orange signal** (accent) | `#E8552B` | `#F06A3F` | Sélection, focus, éléments « live », marque. Jamais en aplat large |

Couleurs sémantiques des états opérationnels (mêmes teintes dans les deux thèmes, luminosité ajustée) :

| État | Couleur | Code |
| --- | --- | --- |
| Disponible | Sauge | `#4F8F67` |
| En réflexion / en développement | Cobalt | `#3462D8` |
| En revue | Indigo ardoise | `#5B5FC7` |
| En test | Sarcelle | `#1F8A8F` |
| En réunion | Ocre | `#B7832A` |
| En attente de validation | Orange signal, contour pointillé | `#E8552B` |
| Bloqué | Carmin | `#C5303B` |
| En pause | Pierre | `#8F8B84` |

Le carmin des blocages est volontairement plus sombre et plus froid que l'orange de marque, et il est toujours accompagné d'une forme (losange) : on ne confond jamais « attention » et « erreur ».

Départements : teintes désaturées, utilisées seulement comme identité discrète (liseré, sol du département dans le bureau) : Direction `#6E6A86`, Architecture `#4D7A99`, Produit `#9A6A7E`, Backend `#4E6FA8`, Frontend `#4F8C7A`, QA `#A08A4A`, Sécurité `#9C5A52`, DevOps `#A8744A`, Spécialistes `#7A7F86`.

### 2.3 Typographie

- **IBM Plex Sans** pour l'interface : héritage d'ingénierie, très lisible en petite taille, plus de caractère qu'Inter.
- **IBM Plex Mono** pour tout ce qui est technique : identifiants (T-12), modèles, coûts, tokens, logs, code, horodatages.

Échelle (px, interligne) : 11/16 libellés · 12/18 données denses · 13/20 texte courant · 15/22 titres de section · 20/26 titre d'écran · 28/32 chiffre clé (un seul par écran). Pas de titre géant. Graisses : 400, 500, 600 uniquement. Chiffres tabulaires partout où l'on compare des valeurs.

### 2.4 Design system

- **Espacements** : grille de 4 px ; rythme 4, 8, 12, 16, 24, 32, 48.
- **Rayons** : 4 px (contrôles), 6 px (panneaux flottants), 10 px (fenêtres modales). Pas de « pilule » sauf les badges d'état.
- **Bordures** : 1 px minéral, seulement là où elles séparent deux zones de nature différente. À l'intérieur d'une zone, on sépare par l'espace et par des filets horizontaux, pas par des boîtes.
- **Ombres** : deux niveaux seulement, pour ce qui flotte réellement (menus, inspecteur détaché, modales). Rien n'a d'ombre au repos.
- **Surfaces** : aucun dégradé, aucun flou décoratif, aucun halo.
- **États interactifs** : survol = fond minéral 1 ; actif = encre ; focus clavier = anneau orange 2 px décalé ; désactivé = 40 % d'opacité.
- **Boutons** : primaire = aplat encre (graphite) et texte papier ; secondaire = texte encre et filet minéral ; tertiaire = texte seul. L'orange n'est utilisé pour un bouton que pour l'action du CTO qui débloque un flux (« Approuver », « Recruter 4 »).
- **Mouvement** : 120 ms pour le survol, 180 ms pour les panneaux, courbe `cubic-bezier(0.2, 0, 0, 1)`. Dans le bureau, les déplacements suivent les événements réels. Avec `prefers-reduced-motion`, tout devient instantané.
- **Composants fondamentaux** :
  - navigation et structure : rail, panneau contextuel, barre d'outils ;
  - données : table dense, liste structurée, badge d'état, chiffre clé ;
  - panneaux et superpositions : inspecteur, menu contextuel, popover, tooltip, palette de commandes, toast ;
  - saisie et états : formulaire, squelette de chargement, état vide, confirmation.

### 2.5 Principes de composition

1. **Une décision par écran.** Chaque écran a un sujet principal, placé en haut à gauche, et tout le reste est subordonné.
2. **Lister avant d'empiler des cartes.** Agents, tâches, rôles, événements, coûts se lisent en tables et listes denses ; les cartes sont réservées aux objets qu'on manipule (carte Kanban).
3. **Trois panneaux, comme un outil de création** : navigation à gauche, sujet au centre, inspecteur contextuel à droite qui s'ouvre sur sélection (agent, tâche, réunion) sans quitter l'écran.
4. **Le graphite structure, le papier accueille le travail.** Le rail et la barre de statut sont graphite dans les deux thèmes ; le contenu est sur papier (clair) ou ardoise (sombre).
5. **L'urgence remonte.** Blocages, décisions en attente et dépassements de budget forment une file en tête de l'Executive Workspace, au lieu de bandeaux dispersés.

### 2.6 Navigation et interaction

- **Rail graphite** (56 px) avec les espaces de travail : Executive, Office, Agents, Projet, Engineering, puis Inbox et Réglages en bas. Le rail ne change jamais.
- **Panneau contextuel** (240 px, repliable) propre à chaque espace : filtres, vues, sous-sections. C'est lui qui change d'un espace à l'autre.
- **Palette de commandes** `Ctrl/⌘ K` : aller à un agent, une tâche, une réunion ; lancer une action (« Briefer le Lead », « Convoquer une réunion »).
- **Inspecteur à droite** (400 px) : agent, tâche, réunion, approbation. Il remplace les tiroirs actuels.
- **Barre de statut graphite** : connexion temps réel, dernier événement, agents actifs, coût du jour, éléments en attente.
- **Mobile** : le rail devient une barre d'onglets en bas ; l'inspecteur devient une feuille plein écran ; le Virtual Office passe en vue « plan » 2D lisible au doigt.

### 2.7 Identité du Virtual Office

Le bureau est traité comme un produit autonome avec son propre cadre :

- **Plein écran**, sans le panneau contextuel ni l'en-tête de page. Seuls le rail et la barre de statut restent. Le HUD (affichage superposé) flotte sur la maquette :
  - en haut à gauche : nom du projet et horloge temps réel ;
  - en haut à droite : caméra (départements, zoom, recentrer) ;
  - en bas à gauche : mini-plan cliquable ;
  - en bas : bande « En ce moment », la liste des activités réelles en cours (réunions, agents qui réfléchissent, blocages) ;
  - à droite : l'inspecteur d'agent.
- **Matière** : plateau mat blanc cassé (clair) ou anthracite (sombre, « équipe de nuit »). Les cloisons sont basses, couleur papier épais, et chaque département se reconnaît à son mobilier et à un sol légèrement teinté :
  - Direction : grand bureau et paperboard ;
  - Backend : postes à double écran ;
  - QA : banc de test avec appareils ;
  - Sécurité : local vitré ;
  - DevOps : baie de serveurs ;
  - salles de réunion : table ovale et écran mural.
- **Figurines** : proportions de personnages de maquette d'architecte (silhouette adulte, environ 1:7), sans visage cartoon. Corps neutre graphite, vêtement teinté de la couleur du département. La lisibilité vient de la **posture et des accessoires**, pas d'icônes :

  | État réel | Représentation |
  | --- | --- |
  | Disponible | Assis, dossier droit, écran en veille |
  | En réflexion | Penché vers l'écran allumé, lanterne cobalt au-dessus de la tête qui pulse lentement |
  | En développement (incrément 4) | Écran avec lignes de code qui défilent, lanterne cobalt fixe |
  | En revue (incrément 4) | Second écran avec un diff, lanterne indigo |
  | En test (incrément 4) | Debout au banc de test, lanterne sarcelle |
  | En réunion | A marché jusqu'à la salle, assis à la table ; celui qui parle a la lanterne ocre |
  | Bloqué | Losange carmin au-dessus de la tête, posture bras croisés |
  | En pause | Figurine translucide, chaise reculée |

- **Collaboration** : un vrai message entre deux agents trace un arc fin, de la couleur du type de message, entre leurs postes pendant 4 secondes, avec l'aperçu du message au survol.
- **Caméra** :
  - zoom continu à trois paliers de lecture : étage (couleurs et activité), département (noms et tâches en cours), poste (détails de l'agent) ;
  - double-clic sur un département pour s'y centrer ;
  - touches 1 à 9 pour sauter aux départements.
- **Règle d'animation** : rien ne bouge sans événement. Les seules animations continues sont la pulsation lente de la lanterne pendant un appel de modèle réel et le défilement d'écran pendant une écriture de fichier réelle.

**Choix technique recommandé : rester en 2.5D avec PixiJS (WebGL), réorganisé en module dédié.**

| Option | Pour | Contre |
| --- | --- | --- |
| **PixiJS 2.5D (recommandé)** | Rendu net du texte et des étiquettes ; 60 fps avec des centaines d'agents ; lisibilité isométrique constante ; déjà en place | Pas de rotation libre de la caméra |
| React Three Fiber / Three.js | Vraie 3D, rotation, éclairage | Lecture des étiquettes et des états plus difficile selon l'angle ; coût GPU sur portable ; modélisation de personnages 3D nécessaire ; la rotation n'aide pas à comprendre l'activité |

La 3D n'apporte pas de compréhension supplémentaire ici ; une vue isométrique fixe, comme dans les jeux de gestion, est plus lisible.

Le module `office/` sera découpé ainsi :
- `scene/` : couches sol, mobilier, figurines, liens, étiquettes ;
- `camera/` : zoom, panoramique, paliers, mini-plan ;
- `sim/` : projection pure de l'état réel vers la représentation (posture, lanterne, position), testée unitairement ;
- `hud/` : composants React superposés.

Les figurines et le mobilier seront dessinés en vectoriel, en un jeu de sprites isométriques maison.

### 2.8 Ce qui change écran par écran (aperçu des maquettes à venir)

1. **Executive Workspace** :
   - en tête, une file « À décider » (blocages, questions du Lead, propositions, approbations) ;
   - en dessous, un tableau des projets (avancement, équipe, coût, risques) ;
   - à droite, un journal des décisions et la consommation IA en sparkline ;
   - un seul chiffre clé : coût du jour par rapport au budget.
2. **Virtual Office** : comme décrit au §2.7.
3. **Agent Management** :
   - annuaire en table dense (agent, rôle, département, état, mission, modèle, coût 7 jours) ;
   - basculement vers l'organigramme ;
   - profil détaillé dans l'inspecteur ;
   - recrutement en panneau latéral à deux étapes (catalogue filtrable en liste, puis configuration) ;
   - les problèmes de configuration (agents sans modèle) sont regroupés en une seule alerte actionnable.
4. **Engineering Workspace** :
   - arborescence, éditeur à onglets, terminal et logs en panneau bas ;
   - branches, diffs par agent et revues à droite.
   
   Cette maquette montre la cible de l'incrément 4 ; à l'écran réel, les zones non encore livrées seront marquées comme indisponibles, jamais remplies de fausses données.
5. **Project Management** : vues Kanban, Liste, Timeline, Dépendances, Backlog et Roadmap dans un même cadre, avec une barre de filtres commune et l'inspecteur de tâche à droite.

## 3. Ce que je te demande de valider

1. La direction « Studio Model » : maquette d'architecte et instrument d'ingénierie, papier et graphite, un seul accent orange signal.
2. La palette, IBM Plex Sans et Mono, et les principes du design system.
3. La structure de navigation : rail fixe, panneau contextuel, inspecteur, palette de commandes.
4. Pour le Virtual Office : 2.5D PixiJS en module dédié, figurines de maquette, états lus par posture et lanterne.

Après ta validation (ou tes corrections), je produis les cinq maquettes détaillées, avec les données réelles du projet de test et leurs états importants, toujours en fichiers locaux, avant d'écrire le moindre composant définitif.
