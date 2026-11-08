# Virtual Office — nouvelle proposition (itération 2)

Réponse à `DESIGN REVIEW — AI ENGINEERING WORKSPACE.md`. Le design system validé (palette, IBM Plex, tables, sobriété) est conservé ; seul le Virtual Office est repensé. Aucun code de l'application n'a été modifié.

**Maquette interactive** : `docs/design/office/index.html`. Ouvre-la dans Chrome : c'est une vraie scène 3D, tu peux zoomer, déplacer la caméra, double-cliquer un département et cliquer un collaborateur.

Les vues de revue sont accessibles par l'ancre de l'URL : `#company`, `#backend`, `#meeting`, `#activity`, `#agent`, `#nav`.

Images rendues : `docs/images/office-*.png`.

La maquette joue un **scénario de démonstration**, signalé dans l'interface : réunion de lancement, développement, revue, blocage. Dans le produit, chaque état viendra uniquement des événements réels.

## Les six vues demandées

| # | Demande | Image | Ce qu'on y voit |
| --- | --- | --- | --- |
| 1 | Vue globale | `office-company.png` | L'étage entier en coupe façon maquette d'architecte (cloisons coupées à 1 m), avec 13 espaces aménagés : <br>• CTO et Architecture ; <br>• Product, Backend, Frontend et Mobile ; <br>• QA, Cybersécurité (vitrée) et DevOps ; <br>• deux salles de réunion vitrées, l'espace collaboratif et le lounge-accueil. <br>Les panneaux affichent l'effectif et l'activité en direct. |
| 2 | Département Backend | `office-backend.png` | La caméra est entrée dans le département : les murs du fond montent à pleine hauteur et les noms apparaissent. <br>On y voit des postes à double ou triple écran, l'écran de l'agent qui code, celui du Technical Lead en revue et les postes en veille de ceux partis en réunion. |
| 3 | Salle de réunion | `office-meeting.png` | Le kickoff réel : cinq participants autour de la table, le Lead debout devant l'écran mural (ordre du jour, tour 3/4). Chaque participant a son témoin ocre. |
| 4 | Plusieurs collaborateurs en activité | `office-activity.png` | QA bloqué (losange carmin, bras croisés, raison enregistrée), sécurité disponible, SRE devant le mur de monitoring, revue en cours dans le Backend. |
| 5 | Interaction avec un agent | `office-agent.png` | Un clic sur Java Engineer 01 : la caméra cadre son poste (trois écrans de code) et l'inspecteur s'ouvre. Il montre la mission, les pas enregistrés et les livrables, avec un accès direct à l'espace de travail. |
| 6 | Navigation globale ↔ immersive | `office-nav.png` | Les trois niveaux (entreprise, département, poste), annotés sur l'interface elle-même. |

## Principes retenus

- **Architecture intérieure** :
  - sols différenciés (moquette teintée par département, parquet pour les espaces communs, sol sombre pour les salles vitrées) ;
  - cloisons pleines et vitrées, portes, baies vitrées en façade, circulations ;
  - mobilier métier : baies de serveurs et mur de monitoring en DevOps, banc de test avec appareils en QA, mur d'architecture, roadmap en Product, écran mural en salle de réunion, comptoir et canapés au lounge.
- **Collaborateurs** :
  - silhouettes adultes en tenue professionnelle (veste, chemise, pantalon), carnations et coiffures variées ;
  - la couleur du département ne figure que sur le cordon de badge ;
  - la posture dit l'activité : tape au clavier, penché sur une revue, debout au tableau, présente à l'écran, bras croisés quand il est bloqué, assis en arrière devant le monitoring.
- **Synchronisation** :
  - le contenu d'un écran (code, diff, diagramme, tests, monitoring) n'apparaît que si une activité réelle correspondante est en cours ; sinon l'écran est en veille ;
  - personne ne « se repose » au lounge : les agents n'ont pas de pause, le lounge reste un espace vide d'accueil.
- **Interactions entre agents** : un vrai message trace un arc entre les deux postes, avec l'aperçu du message ; c'est le même mécanisme que celui déjà livré dans l'incrément 3.
- **Navigation** :
  - **Entreprise** : zoom à la molette, déplacement en glissant, rotation par quarts de tour (Q/E).
  - **Département** : double-clic sur la pièce, touches 1 à 9, plan d'étage cliquable ou sélecteur ; la caméra s'y rend en vol fluide.
  - **Poste** : un clic sur un collaborateur ouvre l'inspecteur.
  - **Retour** : Échap ou le fil d'Ariane remontent d'un niveau.
  - Le plan d'étage montre la zone visible. Aucun avatar à piloter, aucune commande de jeu.

## Choix technique

| Critère | 2.5D sprites (PixiJS) | Three.js « brut » | **React Three Fiber + drei** |
| --- | --- | --- | --- |
| Qualité graphique (lumière, ombres, profondeur, vitrages) | Faible : tout doit être dessiné à la main, pour chaque pose et chaque orientation | Élevée | Élevée (c'est Three.js) |
| Passage vue globale → vue immersive | Impossible sans changer d'échelle de sprites | Natif (caméra continue) | Natif, plus des contrôles de caméra prêts à l'emploi (`CameraControls`) |
| Personnages animés crédibles | Feuilles de sprites par pose et par angle, coûteuses à produire | Modèles articulés (glTF) et animations | Idem, plus `useAnimations` et une gestion déclarative des modèles |
| Intégration au frontend React existant | Pont impératif (actuel) | Pont impératif à maintenir | **Composants React** branchés directement sur TanStack Query et le flux temps réel |
| Étiquettes nettes et HUD au design system | HTML superposé | HTML superposé fait à la main | `Html` de drei, ou superposition React |
| Performances | Excellentes | Excellentes si on instancie | Excellentes : `Instances` pour le mobilier, rendu à la demande (`frameloop="demand"`) quand rien ne bouge, niveaux de détail |
| Maintenabilité | Bonne pour de la 2D, mauvaise pour cette ambition | Code impératif volumineux | **Meilleure** : la scène est une fonction de l'état, testable comme le reste |

**Recommandation : React Three Fiber (Three.js) avec drei**, à la place de la piste PixiJS du premier tour. Elle était adaptée à un plan schématique, pas au niveau d'immersion que tu demandes. La maquette est écrite en Three.js direct pour être autonome ; son code se transpose tel quel en composants R3F.

Mise en œuvre prévue :
1. **Décor** : géométrie procédurale (murs, cloisons, sols, mobilier) générée depuis le plan d'étage, avec un matériau mat « maquette » cohérent avec la palette. Le mobilier répété est instancié.
2. **Personnages** : remplacer les mannequins procéduraux de la maquette par un **modèle humain articulé en glTF**. Un seul maillage de base, avec des variantes de tenue par matériaux et des clips d'animation : assis-tape, assis-idle, penché-revue, debout-présente, debout-tableau, bras croisés, marche. Ce sont eux qui apporteront le naturel demandé : les figurines actuelles ne servent qu'à valider la mise en scène.
3. **Moteur de représentation** : une fonction pure `état réel → (lieu, posture, contenu d'écran, témoin)`, testée unitairement, pour qu'aucune animation ne puisse exister sans événement.
4. **Caméra** : trois paliers (entreprise, département, poste), vols animés, rotation par quarts de tour ; avec `prefers-reduced-motion`, les transitions deviennent instantanées.
5. **Performances** :
   - ombres calculées une fois pour le décor statique ;
   - rendu à la demande ;
   - personnages simplifiés en vue globale ;
   - cible : 60 images/s avec 50 agents sur un portable.
6. **Accessibilité et repli** :
   - vue liste conservée ;
   - sur mobile, plan 2D du même étage plutôt que la 3D.

## À valider

1. L'architecture intérieure et le niveau de détail (vue globale, Backend, salle de réunion).
2. La mise en scène des activités (postures, écrans, témoins, arcs de message).
3. La navigation à trois niveaux et l'inspecteur.
4. Le passage à React Three Fiber, avec des personnages articulés en glTF à la place des mannequins de la maquette.
