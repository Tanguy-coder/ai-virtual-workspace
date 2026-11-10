# Engineering Workspace et incrément 4 — proposition

L'incrément 4 tient la promesse centrale du produit : des agents qui produisent du vrai code, testé, revu et validé. Ce document propose l'espace Engineering où l'on suit ce travail, et la mécanique qui le produit. À valider avant toute implémentation.

**Maquette interactive** : `docs/design/engineering/index.html`, à ouvrir dans Chrome. Les vues sont accessibles par l'ancre : `#run`, `#code`, `#review`, `#git`, `#tests` (ajouter `-clean` pour masquer les repères numérotés).

Rendus : `docs/images/engineering-*.png`.

La maquette joue un **scénario de démonstration** sur ton projet « To do list » ; il est signalé dans l'interface. Dans le produit, chaque ligne viendra d'un pas enregistré.

## Ce qui manque aujourd'hui

Après le plan du Lead, les tâches restent « Assigned » : aucun processus ne fait travailler l'agent. L'incrément 4 ajoute :
- l'exécution d'une tâche par son agent : boucle d'outils, dans un conteneur isolé, sur sa branche ;
- les tests lancés par la plateforme ;
- la revue par un autre agent ;
- le moteur de validation, seul à pouvoir clore une tâche ;
- la fusion dans `main`, avec ton approbation.

## Les cinq vues

| # | Vue | Image | Ce qu'on y voit |
| --- | --- | --- | --- |
| 1 | Exécution d'une tâche | `engineering-run.png` | La vue principale : regarder un agent travailler.<br>• Budget de tokens de la tâche, avec la part lue en cache (T8).<br>• Chaque pas enregistré : outil, heure, tokens ; la ligne en cours correspond à un appel de modèle réel.<br>• Critères d'acceptation cochés seulement avec une preuve (un test, un run).<br>• Les cinq conditions du moteur de validation.<br>• Garde-fous : fichiers et commandes autorisés, pas maximum, surveillance des boucles.<br>• Actions : envoyer un message, mettre en pause, reprendre la main. |
| 2 | Code | `engineering-code.png` | Arborescence filtrée par tâche ou par agent, diff contre `main`, et pour chaque ligne le pas et le commit qui l'ont écrite. Terminal du conteneur en lecture seule, avec ses ressources réelles. Tu peux éditer, mais sur ta propre branche `cto/…`, jamais sous un agent. |
| 3 | Merge request | `engineering-review.png` | Diff commenté par l'agent relecteur, avec les réponses. Les contrôles portent sur le **résultat de la fusion**, pas sur la branche seule : tests, lint, secrets, conflits, revue, critères. La fusion dans `main` demande ton approbation. |
| 4 | Branches | `engineering-git.png` | Une branche par tâche. Les conflits sont détectés au rebase, avant toute fusion, et signalés aux deux agents et au Lead : rien n'est écrasé. Dépôt local, avec un miroir GitHub optionnel. |
| 5 | Tests | `engineering-tests.png` | Les runs sont exécutés par la plateforme dans le conteneur, jamais déclarés par l'agent. L'agent ne reçoit que les tests en échec (4 lignes au lieu d'un log de 214). La sortie complète reste consultable. |

## Mécanique proposée

### Exécution d'une tâche

1. **Démarrage** : quand une tâche passe « Assigned » et que l'agent est libre, l'ordonnanceur la met en file.
2. **Mise en place** : un agent-worker prend la tâche, avec un bail renouvelé par heartbeat. La plateforme crée la branche `agent/<agent>/t-<n>-<slug>` depuis `main` et démarre un conteneur Docker éphémère :
   - image choisie selon la pile du projet (node, java, python…) ;
   - réseau coupé sauf vers le registre de paquets ;
   - CPU, mémoire et durée plafonnés.
3. **Boucle** : modèle → appels d'outils → résultat, chaque pas étant persisté et diffusé en événement. La boucle s'arrête quand :
   - l'agent soumet son travail ;
   - il déclare un blocage ;
   - il attend une réponse ;
   - le budget ou le nombre de pas est atteint ;
   - tu le mets en pause.
4. **Soumission** : la plateforme lance les tests sur le résultat de la fusion, ouvre la merge request et demande une revue. Le relecteur est choisi par le Lead, sinon par la règle « même département, pas l'auteur ».
5. **Validation** : le moteur vérifie les cinq conditions. Si tout est vert et que tu approuves la fusion, la tâche passe « Done » et ses dépendantes deviennent prêtes.

### Outils sobres en tokens (T7)

| Outil | Ce qu'il renvoie au modèle |
| --- | --- |
| `repo.tree` | La structure du dépôt jusqu'à une profondeur donnée, sans contenu |
| `code.search` | Les lignes qui correspondent, avec fichier et numéro |
| `file.read` | Une plage de lignes, jamais un fichier entier par défaut ; un fichier lu sans changement n'est pas renvoyé |
| `file.patch` | Applique un diff ; le modèle n'écrit que les lignes changées |
| `exec` | Commandes de la liste blanche ; la sortie est réduite aux erreurs et aux tests en échec, la sortie complète est conservée |
| `git.commit` | Commit sur la branche de l'agent uniquement |
| `task.submit`, `task.block`, `message.send`, `memory.record` | Collaboration et cycle de vie |

En plus des règles de `docs/09` (cache, extraits, résumés) :
- l'historique de la boucle est compacté au-delà de N pas : le résultat des anciens outils est remplacé par une ligne ;
- chaque tâche a un budget de tokens.

### Sécurité

- Le code des agents ne s'exécute jamais dans le processus de l'API.
- Les secrets ne sont jamais placés dans le contexte du modèle ; ils sont injectés dans le conteneur seulement si la tâche en a besoin et que la politique l'autorise.
- Actions qui demandent ton approbation : fusion dans `main`, suppression de branche, modification d'un fichier sensible (configuration, CI, dépendances majeures), accès réseau hors du registre.

## Critère de passage de l'incrément 4

Sur le projet « To do list » :
- le Lead planifie, puis trois tâches au moins sont exécutées par leurs agents dans des conteneurs ;
- les tests sont lancés par la plateforme, chaque merge request est revue par un autre agent et validée par le moteur ;
- tu approuves les fusions ;
- un conflit réel entre deux branches est détecté et résolu sans écrasement ;
- une boucle d'erreurs est arrêtée par le watchdog et escaladée ;
- `main` contient une application qui fonctionne ;
- tout cela reste visible en direct dans le bureau virtuel et dans ces vues, et le coût en tokens par tâche est mesuré et sous son budget.

## À valider

1. Les cinq vues, avec « Exécution d'une tâche » comme vue principale de l'espace Engineering.
2. **Fusion dans `main`** : ton approbation par défaut. On peut la passer en automatique quand la validation est verte, projet par projet.
3. **Dépôt** : local sur la plateforme pour l'incrément 4, avec un miroir GitHub optionnel. GitLab reste prévu pour l'incrément 6.
4. **Conteneurs** : une image par pile détectée, réseau coupé sauf le registre de paquets. Docker doit être disponible sur la machine qui héberge la plateforme ; c'est déjà le cas chez toi.
5. **Relecteur** : un autre agent choisi par le Lead, sinon de la même équipe. Si un projet n'a qu'un seul agent pour un domaine, la revue te revient.
6. **Édition humaine** : sur ta propre branche `cto/…`, soumise aux mêmes contrôles.
7. **Clôture manuelle** d'une tâche par toi : non par défaut. On peut ajouter un « fermer comme CTO », tracé dans l'audit, qui contourne la validation.
