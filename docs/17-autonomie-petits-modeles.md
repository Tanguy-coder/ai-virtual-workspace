# 17 — Aller au bout d'un projet avec un petit fournisseur, sur du code existant

Demande du CTO du 2026-11-18 :

- Avec un petit fournisseur comme Groq, le système doit terminer un projet entier sans s'interrompre.
- Les tâches doivent être les plus petites possible, avec de très petits contextes.
- Le système doit savoir travailler sur un dépôt ou un projet existant.
- Des tests ne doivent pas échouer plus de deux fois.

Point de départ : sur « To do list », un agent a passé 40 étapes sur une accolade mal placée dans `app.js`. Sur ce run, ses `file.edit` ont échoué 20 fois avec « old text not found ». Le budget de 400 000 tokens a été dépassé, puis la limite d'étapes a été atteinte.

## 1. Des modifications qu'un petit modèle réussit

| Avant | Maintenant |
|---|---|
| `file.edit` exigeait de recopier le passage caractère pour caractère | `file.edit {"path", "from", "to", "new"}` remplace des lignes par leurs numéros (ceux de `file.read`). La réponse montre les nouvelles lignes, numérotées. `"from": 0` est lu comme la ligne 1. |
| Un passage qui ne différait que par l'indentation échouait | Si les lignes correspondent sans les espaces, et à un seul endroit, la modification est appliquée. |
| Un échec répondait « old text not found. Read the exact lines first. » | Le retour montre les lignes les plus proches du passage cherché, numérotées, et la forme par numéros de ligne. |
| Une erreur de syntaxe n'apparaissait qu'au `npm test` suivant, noyée dans la sortie | Chaque fichier `.js`/`.mjs`/`.cjs` écrit ou modifié passe par `node --check` dans la sandbox, chaque `.json` par un parseur. Une erreur donne « SYNTAX ERROR at line N », avec les lignes autour, et arrête les actions suivantes de la réponse. |
| Douze `file.edit` d'affilée sur `tests/app.test.js` (2026-11-19), chacun posé sur la version cassée du précédent | Une modification qui casse un fichier jusque-là valide **n'est pas appliquée** : le fichier reste intact, et l'agent reçoit l'erreur avec sa version des lignes concernées. Si un fichier déjà cassé le reste après 3 modifications d'affilée, la plateforme remet sa dernière version qui passe : celle du run, celle du dernier commit, ou celle de develop. |

## 2. Des tests qui n'échouent pas plus de deux fois

Deux exécutions de tests en échec d'affilée arrêtent l'agent (`TaskExecution.testsFailedTwice`). Cela vaut pour les tests que l'agent lance lui-même (`npm test`, `node --test`, `pytest`, `mvn test`…) comme pour ceux que la plateforme lance à la soumission. Le compteur repart à zéro après chaque intervention du Lead ou du CTO. Le blocage va au Lead (`docs/16`), avec la sortie en échec.

Le Lead reçoit, **numérotés**, les fichiers cités dans l'échec et ceux que la branche a modifiés. Il a une décision de plus, **`fix`** : il corrige lui-même par numéros de ligne. La plateforme applique et commite (« Fix by the Lead »), puis l'agent relance les tests. Le Lead a souvent plus de chances de trouver la cause : il part d'un contexte neuf, avec uniquement l'échec et le code.

**Échelle** :
- Les 2 premières décisions du Lead sur une tâche peuvent être `fix` ou `answer`.
- Les 2 suivantes doivent changer la tâche (`rewrite` vers un périmètre que les tests peuvent prouver avec ce qui est installé, ou `split`) ou escalader. C'est le cas de T-28 : trois correctifs, alors que les tests utilisaient Mocha et jsdom, absents du projet.
- Au-delà, la question va au CTO.
- « Ask the Lead », demandé par le CTO, n'est pas limité.

**Fichiers de test qui ne peuvent pas tourner** (vu le 2026-11-19 sur « To do list ») : trois agents avaient écrit leurs tests avec trois frameworks différents. On y trouvait `expect` importé de `node:test` (Jest), les globales `setup` et `suite` (Mocha), et jsdom, jamais installé. Comme chaque soumission lance tous les tests, toutes les tâches échouaient ensuite, quel que soit leur contenu. Désormais :

- **À l'écriture** : chaque fichier de test écrit ou modifié est vérifié (`TestFileLint`). Seuls `node:test` et `node:assert` sont acceptés, plus les paquets déclarés dans `package.json`. Les noms que `node:test` n'exporte pas, les globales d'autres frameworks et les paquets absents donnent « TEST FILE PROBLEM », comme une erreur de syntaxe.
- **À l'échec d'une exécution de tests** (lancée par l'agent ou à la soumission) : la plateforme vérifie tous les fichiers de test du dépôt et place la liste des fichiers fautifs en tête de l'échec. Le Lead la reçoit aussi (`<test_file_problems>`), avec ces fichiers numérotés pour pouvoir les corriger. Le coupable est souvent un fichier de test écrit par un autre agent, pas le code de la tâche.

**Un Lead qui tourne en rond** (vu le 2026-11-19 sur T-16) : le Lead a « corrigé » quatre fois un id que `src/index.html` avait déjà, puis réécrit la tâche en interdisant le `<script>` dont le test avait besoin. Le vrai coupable était le test, réécrit sur la branche : jsdom ouvert depuis un fichier, sans `url`, n'a pas de `localStorage`. Désormais :

- le Lead voit ses décisions précédentes sur la tâche, avec la consigne de ne pas les répéter ;
- un `fix` qui ne change rien est refusé, et le Lead doit chercher ailleurs ;
- chaque fichier de test montré au Lead indique s'il a été écrit sur la branche (il peut alors être faux) ou s'il vient de develop (c'est alors une exigence) ;
- `TestFileLint` signale jsdom sans `url` quand le test utilise `localStorage`.

**Tests de develop protégés** : la vraie cause de T-16 est que son agent, chargé d'un squelette HTML, avait réécrit `tests/app.test.js`. Ce test, qui vient de develop, était devenu impossible à faire passer. Avec la version de develop, la branche passait 30/30. Désormais, c'est le code qui décide, sans appel de modèle :

- **Avant toute décision du Lead** : chaque fichier de test cité dans l'échec, qui existait quand la branche est partie de develop et que la branche a modifié, reprend sa version de develop. La plateforme le commite (« Restore … from develop »), dit à l'agent de corriger son code plutôt que ce test, et reprend le travail (décision `restore_tests`). Un test ajouté par la branche reste au Lead, car il peut être la preuve de la tâche.
- **À l'écriture** : un agent qui modifie un test venant de develop reçoit aussitôt une note. Ce test est une exigence : il ne le change que si sa tâche porte sur ce test, et il sera restauré s'il échoue.

**Imports impossibles et agent qui attend** (vu le 2026-11-19 sur T-21) : le test importait `addTask` depuis `src/app.js`, qui utilisait `module.exports` alors que le projet est en `"type": "module"`. Il utilisait aussi `JSDOM` sans l'importer. Entre deux échecs, l'agent se bloquait avec « attends les instructions » alors que son travail était commité. Le Lead a dépensé deux de ses quatre décisions à lui répondre « continue ». Désormais :

- `TestFileLint` vérifie qu'un nom importé d'un fichier du projet est bien exporté par ce fichier. Il signale aussi `module.exports` dans un projet ESM, un fichier importé qui n'existe pas, un import par défaut sans `export default`, et `JSDOM` non importé. Comme les autres problèmes de fichiers de test, c'est signalé à l'écriture et en tête de chaque échec.
- Un blocage qui ne demande rien (« wait », « next feature », « tests pass »…, sans question, accès ni erreur), alors que la branche contient du travail, n'est pas un blocage : la plateforme soumet le travail à la place de l'agent. Les tests puis la revue décident, et le Lead n'est pas sollicité.

**Fichier cassé invisible et correctifs du Lead non vérifiés** (vu le 2026-11-19 sur T-28) : tous les tests échouaient avec « Unexpected token '}' », signalé à l'emplacement de chaque test. Le fichier cassé était `app.js`, ligne 149, que ces tests importent. Personne ne l'avait vu. Il avait été cassé par deux « Fix by the Lead », que rien ne vérifiait. Un autre correctif du Lead avait remplacé trois lignes de code de `src/app.js` par des commentaires (« // Fix unfinished template literal here »). Désormais :

- À chaque échec de tests (lancés par l'agent ou à la soumission), la plateforme passe `node --check` sur tous les fichiers JS du dépôt, hors `node_modules`, `dist`, etc. Elle place « Files with a SYNTAX ERROR: - app.js line 149: … » en tête de l'échec et du motif de blocage. Le Lead reçoit cette liste (`<syntax_errors>`) et ces fichiers numérotés, en premier.
- Un `fix` du Lead passe la même vérification que les modifications des agents. S'il laisse une erreur de syntaxe ou un JSON invalide, il est refusé, et le Lead le corrige en voyant l'erreur et les lignes concernées.
- Un `fix` qui remplace du code par des commentaires est refusé.
- Le Lead n'a pas trouvé l'accolade en trop en trois essais (gpt-4.1), et cet échec a bloqué le Lead lui-même. Désormais, avant toute décision du Lead, la plateforme ramène chaque fichier qui ne passe pas `node --check` à la version la plus récente de son historique qui passe : sur la branche, sinon celle de develop. Elle la commite, dit à l'agent de refaire ce qui manque, et reprend (décision `restore_parsing`). Sur T-28, `app.js` ne passait plus depuis 6 commits, dont 4 « Fix by the Lead » ; la version de `e5ee851` passe.
- Une décision du Lead qui échoue ne bloque plus le Lead : il reste disponible, et la question va au CTO dans son chat.

**Agent occupé** : quand le Lead règle un blocage pendant que l'agent travaille sur une autre tâche, la reprise se met en file (`task_runs.resume_when_free`). La plateforme la relance dès que l'agent est libre, avant de lui donner une nouvelle tâche. Avant, ce cas remontait au CTO (vu sur T-28).

**Interventions du CTO supprimées** (2026-11-19 au soir) :

- Une décision du Lead qui échoue (réponse inutilisable, correctif refusé) est retentée une fois automatiquement, comme le faisait le clic « Ask the Lead ». Elle ne va au CTO qu'après deux échecs.
- Les restaurations faites par le code (`restore_tests`, `restore_parsing`) ne comptent plus dans les 4 décisions du Lead.
- Un `fix` sans aucun fichier montré est refusé tout de suite : « answer », « rewrite » ou « split » à la place (vu sur T-23).
- Les modèles OpenAI qui refusent `max_tokens` (o-series, gpt-5) sont rappelés avec `max_completion_tokens`, et la plateforme le retient pour ce modèle (vu sur T-27).
- Une réponse JSON écrite en morceaux de chaîne JavaScript (`"a" + "b"`) est réparée sans appel au modèle (vu sur T-28).
- Les runs restés ouverts sur une tâche terminée ou annulée sont fermés par la plateforme, et ne peuvent plus être relancés (vu sur T-8 : terminée le 16, son run était encore relancé le 19, dans un worktree supprimé).

## 3. De petites tâches, de petits contextes

- **Contexte par défaut** pour tout fournisseur : les 12 dernières entrées du travail, les 2 dernières sorties d'outils en entier (2 000 caractères chacune), 1 200 caractères de spécification, 800 de mémoire. Avant, sans limite par minute connue, le prompt gardait tout l'historique et grossissait à chaque étape (8 600 tokens observés). Les niveaux plus maigres restent appliqués quand la limite par minute du fournisseur l'exige.
- **Découpage** (`TaskSizing`) : une tâche est un seul comportement, au plus 2 fichiers source plus leurs tests, environ 100 lignes modifiées, 1 à 3 critères prouvés par un test, environ 10 étapes. Une tâche qui modifie un fichier existant le nomme.

## 4. Travailler sur un projet existant

À la création d'un projet, la section « Code » propose trois départs :

- **New project** : un dépôt vide, comme avant.
- **Git repository** : URL HTTPS, branche facultative, jeton d'accès facultatif pour un dépôt privé. Le jeton est passé à git dans un en-tête pour le clone seulement. Il est conservé chiffré dans le coffre de secrets (`repositories.remote_secret_id`), jamais dans la configuration du dépôt ni dans un journal. Les URL autres que HTTPS (`file://`, ssh) sont refusées.
- **Local folder** : un dossier placé dans `./imports`, monté en lecture seule dans le conteneur backend. Un dossier qui est un dépôt Git est cloné avec son historique. Sinon, il est importé en un commit. Dans les deux cas, `node_modules`, `.venv`, `target`, `dist`, `build` et `.env` sont laissés de côté.

Le code importé devient `main` ; les agents partent de `develop`, comme pour un nouveau projet. Les dossiers de dépendances et de build sont exclus des commits des agents (`.git/info/exclude`), sans modifier le dépôt.

**Carte du code** : la plateforme l'écrit sans appel de modèle et la range comme spécification (« Existing code (repository map) »). Elle donne les langages, la structure, les commandes de build et de test, les dépendances et le début du README. Le Lead la lit pour planifier des modifications du code existant plutôt qu'une réécriture, et les agents la lisent au lieu d'explorer le dépôt.

Un dépôt déjà importé, ou dans lequel des agents ont travaillé, n'est jamais remplacé par un nouvel import (409).

## 5. Le Lead prend la main au quatrième échec (2026-11-20)

**Constat sur T-28.** La tâche a tourné deux jours : 300 étapes, 615 000 tokens, plus de 100 modifications refusées d'un fichier de 90 lignes et 24 décisions du Lead. Ce n'était pas un bug isolé, le mécanisme ne pouvait pas aboutir :

- Le Lead ne pouvait que poser un patch en un seul coup, sans relancer les tests. Chaque patch révélait l'échec suivant.
- L'agent (gpt-4.1-mini) gardait dans son contexte ses 12 dernières tentatives ratées, de part et d'autre du message du Lead. Le texte de ce message, lui, disparaissait dès l'étape suivante : seul le titre « Message from the Lead » restait. L'agent refaisait donc les mêmes modifications cassées.
- Un pas en échec (watchdog de 15 minutes, trois réponses inutilisables) bloquait la tâche sans prévenir le Lead : il fallait le CTO.

**Ce qui change** (exigence du CTO : « si une tâche échoue 4 fois, le Lead doit prendre la main et réparer pour que l'agent continue ») :

- **Échelle** (`LeadUnblocker`). Chaque échec compte, y compris les réparations de la plateforme (`restore_tests`, `restore_parsing`).
  - Aux 1er et 2e échecs, le Lead corrige ou répond.
  - Au 3e, il réécrit, découpe, prend la main ou escalade.
  - **Au 4e, la plateforme lui fait prendre la main sans appel de modèle** (`TAKEOVER_AFTER = 3`).
  - Le Lead peut aussi décider `takeover` plus tôt.
  - Au plus 2 prises en main par tâche (`MAX_TAKEOVERS`), puis le CTO.
  - Une intervention du CTO, ou une restitution du Lead, remet le compte à zéro.
- **Prise en main** (`TaskExecution.takeOver`, colonnes `task_runs.takeover_*`, V13). Le Lead travaille sur la branche de l'agent avec les mêmes outils et les tests, dans le même run. Ses pas sont préfixés « Lead: » et ses commits portent son nom.
  - Il n'a ni l'arrêt après deux échecs de tests, ni le plafond de 40 pas.
  - Il a ses propres limites : 30 pas et 150 000 tokens (`aiw.execution.takeover-steps`, `aiw.execution.takeover-token-budget`), dans la limite de ce qui reste au budget de la tâche (section 6).
  - Il voit pourquoi l'agent s'est arrêté. On lui dit qu'un test écrit sur la branche peut être faux, qu'un test de develop est une exigence, et qu'un petit fichier abîmé se réécrit en entier.
- **Fin de la prise en main**, de trois façons :
  - `task.submit` : les tests de la plateforme passent et la revue commence. La demande de fusion reste celle de l'agent.
  - `task.handback {note}` : l'agent reprend. La restitution est refusée s'il reste à la tâche moins d'un cinquième de son budget : le Lead doit alors finir lui-même.
  - Échec (`task.block`, limites atteintes, même appel raté 3 fois, pas en échec) : le Lead écrit au CTO dans son chat, sans nouvelle série de décisions.
- **L'agent ne revient pas sur le problème** (`TaskExecution.contextStart`). Son contexte commence au dernier message (Lead, CTO, réunion). Ce message reste affiché en entier dans `<latest_instruction>` tant qu'aucun autre ne le remplace. Ses tentatives d'avant sont laissées de côté et l'état du dépôt les résume.
- **Modifications refusées en série.**
  - Au 2e refus de suite sur un fichier de moins de 250 lignes, l'agent doit le relire en entier et l'envoyer complet en un `file.write`.
  - Au 6e refus depuis la dernière instruction, l'agent est arrêté (`TaskExecution.editsKeepFailing`) et l'échec compte pour le Lead.
- **Un pas en échec va au Lead** comme tout blocage (`TaskExecution.failed`).
- **Budget épuisé** : la tâche est découpée (section 6).

## 6. Aucune tâche au-delà de 200 000 tokens (2026-11-20)

Exigence du CTO : « une tâche n'excède jamais 200k tokens ; elle doit être la plus minimale possible ». C'est le code qui fait respecter ce plafond, pas le modèle :

- **Plafond dur** `aiw.execution.max-task-tokens` (200 000) :
  - le budget d'un run ne le dépasse jamais (`TaskExecution.taskTokenBudget`) ;
  - le réglage du projet et le budget d'un run sont refusés au-dessus (400 `over_task_ceiling`) ;
  - au démarrage, les runs ouverts au-dessus sont ramenés au plafond (`capOpenBudgets`).
- **La prise en main du Lead compte dans le budget de la tâche.** Elle n'ajoute plus ses tokens au budget : sa limite est ce qui reste à la tâche. La restitution est refusée s'il reste moins d'un cinquième.
- **Budget atteint, la tâche est découpée par le Lead, sans le CTO ni relèvement** (`LeadUnblocker.splitForBudget`) :
  - le premier morceau finit le travail commité sur la même branche ;
  - les suivants doivent chacun tenir dans la moitié du budget ;
  - même chose au 4ᵉ échec quand il reste moins d'un quart du budget pour une prise en main ;
  - une tâche déjà issue de deux découpages successifs va au CTO (`MAX_SPLIT_DEPTH`) : redécouper n'aide plus.
- **Tâches minimales dès le plan** :
  - `TaskSizing.rules` annonce le plafond et demande de viser la moitié (environ 12 pas), puis de couper toute tâche divisible en deux tâches testables ;
  - `LeadService.validateTasks` refuse une tâche à plus de 3 critères (`MAX_CRITERIA`), et la réécriture d'une tâche par le Lead est bornée de même.
- **Interface** : le panneau « budget épuisé » ne propose plus de doubler le budget ; les réglages affichent le plafond.

## 7. Régressions : un test de develop cassé par la branche (2026-11-20)

**Constat sur T-29.** La prise en main du Lead a échoué après ses 30 pas :

- Les 16 fichiers de tests de la branche étaient identiques à ceux de develop, où ils passent tous (37/37, vérifié). Seuls `src/app.js` et le nouveau test du toggle avaient changé.
- En réécrivant `src/app.js`, l'agent avait perdu des exports dont les tests de develop ont besoin. Le Lead les a ensuite cherchés pendant 30 pas : il a relu `app.js` dix fois et réécrit le fichier six fois.
- Personne ne lui disait que la version de develop passait.

**Ce qui change :**

- **Détection par le code** (`Regressions.find`), à partir de git seul. Un test qui échoue et qui est identique à sa version de develop est une régression causée par la branche. La plateforme nomme les fichiers modifiés qu'il importe et les exports qu'ils ont perdus. Elle n'a besoin d'aucun appel de modèle, puisqu'une tâche n'entre dans develop qu'avec ses tests au vert.
- **Message affiché en tête de chaque exécution de tests ratée**, à l'agent comme à la soumission : « REGRESSION (fix this first) … develop's src/app.js exports: … Keep everything develop's version does and add the task's change on top ».
- **`file.read` avec `"version": "develop"`** lit le fichier tel que develop l'a.
- **Le Lead voit la version de develop numérotée**, dans sa décision (`<regressions>`) et au début d'une prise en main.
- **Après une prise en main ratée sur une régression**, la plateforme remet la version de develop de ces fichiers et l'agent refait seulement son changement par-dessus (décision `restore_regressed`, qui compte comme un échec). Le CTO n'est pas sollicité.
- **Historique plus long pendant la prise en main** (`TaskExecution.TAKEOVER` : 24 entrées, 4 sorties complètes au lieu de 12 et 2), pour qu'il ne relise pas sans cesse les mêmes fichiers.

## Vérifié

- `LineEditsTest` : remplacement, insertion et suppression de lignes ; correspondance sans indentation ; lignes proches sur un échec ; lecture des erreurs de `node --check` ; fichiers cités dans un échec.
- `ActionShapesTest` : formes d'action des petits modèles ; réponse sans action lue comme un blocage.
- `ExecutionIntegrationTest.testsFailingTwiceGoToTheLeadWhoFixesTheCode`, dans une vraie sandbox Node : deux échecs, puis arrêt ; le Lead voit `src/sum.js` numéroté et le corrige ; les tests passent ; revue ; fusion dans develop. Le test vérifie qu'il n'y a jamais eu de troisième échec.
- `ImportIntegrationTest` : dossier simple (sans `node_modules` ni `.env`), dossier Git sur `master` avec son historique, carte du code en spécification, second import refusé, URL `file://` et chemin `../` refusés.
- `RealignmentIntegrationTest.afterFourFailuresTheLeadTakesOverRepairsAndHandsBack`, dans une vraie sandbox Node : réponse, réponse, réécriture, puis prise en main par le code (pas de 4e appel de décision). Le Lead écrit le code et le test, puis rend la main. L'agent reprend avec un contexte qui commence au message du Lead (son blocage d'avant n'y est plus) et soumet ; les tests passent.
- `RegressionsTest` : sur un vrai dépôt git, le test de develop cassé est trouvé (pas le test propre à la branche), avec le fichier en cause et ses exports perdus ; la restauration remet la version de develop.
- `ActionShapesTest.refusedEditsStopTheAgentAfterSix`, `theContextStartsAtTheLatestIntervention`.
- En production (T-28) : la vérification de syntaxe a signalé « syntax error line 57 » dès l'écriture ; « Tests failed twice in a row » a arrêté l'agent ; le Lead a corrigé lui-même (`fix`).

## Ce qui interrompt encore un projet

- **Par conception**, l'approbation du CTO pour fusionner develop dans main, ainsi que pour toute action sensible (CI, Dockerfile, `.env`, suppression de branche).
- Les questions que le Lead juge être celles du CTO, une prise en main que le Lead n'a pas pu finir, la 3e prise en main d'une tâche, et une tâche issue de deux découpages qui atteint encore son budget.
- Un fournisseur dont tous les modèles utilisables sont épuisés : le travail attend la remise à zéro du quota et reprend tout seul (`docs/15`).
- **Pas encore fait** : pousser le travail vers le dépôt distant d'origine (incrément 6, avec l'approbation du CTO) ; l'authentification SSH pour le clone.
