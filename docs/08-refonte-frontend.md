# Refonte du frontend — étape 1 : le site

Application de la direction validée « Studio Model » (`docs/06`) à l'ensemble de l'application web. Le Virtual Office 3D (`docs/07`) et l'Engineering Workspace sont les étapes suivantes.

## Fait

- **Socle**
  - Tokens papier (thème clair, par défaut) et ardoise (thème sombre).
  - Graphite pour la structure, gris minéraux, accent orange signal, couleurs d'état distinctes.
  - IBM Plex Sans et Mono hébergées localement, sans appel à Google.
- **Composants**
  - Boutons encre, champs, tables denses, titres de section à filet, contrôles segmentés, onglets soulignés, points et badges d'état (losange pour « bloqué »), squelettes de chargement, animation d'entrée des panneaux.
  - Aucun dégradé, aucune ombre au repos.
- **Structure**
  - Rail graphite fixe avec les cinq espaces (Executive, Virtual Office, Agents, Project, Engineering) ; Inbox, réglages, thème et compte en bas du rail.
  - Panneau contextuel par espace, fil d'Ariane, barre de statut graphite (temps réel, seq, agents au travail, blocages, attentes).
  - Palette de commandes Ctrl/⌘ K (pages, agents, tâches) et barre d'onglets en bas sur mobile.
- **Écrans**
  - **Executive** : un chiffre clé, la file « What needs you » (blocages, questions du Lead, propositions, approbations, appels sans prix), les indicateurs de livraison en ligne, l'activité regroupée par minute, l'équipe au travail.
  - **Agents** : annuaire en table filtrable, catalogue de recrutement en liste avec configurateur latéral, organigramme allégé.
  - **Project** : Tasks (Kanban, liste, timeline, graphe) ; Lead briefing, mémoire partagée, réunions et documents repris au nouveau style.
  - **Workspace** : projets en table, Inbox, réglages, connexion et création d'espace avec panneau de marque graphite.
  - **Panneaux** : panneaux agent et tâche repris au nouveau style.
  - **Virtual Office** : nouvel en-tête et nouvelle légende. La scène elle-même attend la refonte 3D.
- **Vérifié à l'écran** (Chrome headless, 1440×900 et 390×844) :
  - tous les écrans en thème clair, le dashboard en thème sombre et sur mobile ;
  - Kanban, timeline et graphe avec 6 tâches réelles créées à la main ;
  - les panneaux agent et tâche, la palette de commandes ;
  - aucune erreur d'exécution.
- **Tests et build** : typecheck, 8 tests unitaires et build de production verts. Contrats d'API et routes inchangés.

## Reste

1. **Virtual Office en React Three Fiber**, selon `docs/07` : décor procédural, personnages articulés en glTF, trois niveaux de navigation. La scène PixiJS actuelle reste en place d'ici là.
2. **Engineering Workspace** (incrément 4) : maquette à valider avant implémentation.
3. **Finitions** : notifications discrètes (toasts), menus contextuels, vérification tablette détaillée.
