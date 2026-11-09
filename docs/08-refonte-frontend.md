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

1. **Personnages** : validés par l'utilisateur le 2026-11-09 sous leur forme actuelle (figures articulées procédurales). Le passage au glTF prévu dans `docs/07` est abandonné.
2. **Engineering Workspace** (incrément 4) : maquette à valider avant implémentation.
3. **Finitions** : notifications discrètes (toasts), menus contextuels, vérification tablette détaillée.

## Étape 2 : Virtual Office en React Three Fiber (2026-11-09)

- **Fait**
  - La scène PixiJS est remplacée par une scène Three.js (React Three Fiber + drei), chargée à la demande.
  - Étage de la maquette validée : 13 espaces, cloisons pleines et vitrées, sols différenciés, mobilier par métier. « Mobile Engineering » devient « Specialists » (département `OTHER`).
  - Moteur de représentation pur et testé (`src/office3d/represent.ts`) : état enregistré → lieu, posture, écran, témoin.
    - Disponible : assis, écran en veille. Appel de modèle en cours : tape au clavier, écran « model call running ». Bloqué : bras croisés, losange, raison enregistrée à l'écran. En pause : estompé.
    - Réunion ouverte : les participants marchent jusqu'à la salle A ou B ; l'organisateur se tient à l'écran mural, qui affiche le vrai titre, le tour et l'orateur.
  - Écrans muraux DevOps et Sécurité, bancs de test et baies : éteints, rien de réel ne les alimente encore.
  - Navigation : entreprise → département (double-clic, touches 1–9, plan, sélecteur) → poste (clic, ouvre le panneau agent global). Q/E pour tourner, Échap pour remonter, `prefers-reduced-motion` respecté.
  - Plan 2D cliquable (minimap avec cadre de vue), bandeau « Right now » issu de l'état réel, vue liste conservée, plan 2D seul sur mobile.
- **Vérifié** : 10 tests unitaires du plan et du moteur ; captures en Chrome headless des vues entreprise, Backend et poste du Lead, puis d'une vraie réunion convoquée par l'API (marche, placement, écran mural) ; aucune erreur d'exécution.
- **Limites connues**
  - Thème sombre et rendu mobile non vérifiés à l'écran.
  - Pas encore de mesure de performance à 50 agents.
