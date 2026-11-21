# Incréments 5 et 6 : qualité, sécurité, livraison

21 novembre 2026 · Conception, puis rapport (sections « Vérifié » en fin de document).

Décisions du CTO (21/11) : environnement de test en Docker local ; chart Helm fourni sans être exécuté ; test de bout en bout sur un projet « API de réservation + front » avec un plafond de 5 $ ; commit et push après chaque incrément.

## Principe

Tout ce qui suit est décidé par le code et ne coûte **aucun token** : les outils d'analyse sont des programmes reconnus, exécutés dans des conteneurs jetables. Le modèle n'intervient que pour corriger un constat, comme pour n'importe quelle tâche, et cette tâche reste soumise au plafond de 200 000 tokens.

## Incrément 5 : qualité et sécurité

### Analyses

Chaque analyse s'exécute sur un instantané figé du commit analysé (`git archive`), monté en lecture seule dans le conteneur de l'outil. Un merge pendant l'analyse ne change donc pas la preuve.

| Type | Outil (image épinglée) | Cible | Preuve |
|---|---|---|---|
| SECRETS | Gitleaks 8.21 | fichiers de develop | fichier, ligne, règle (valeur masquée) |
| SAST | Semgrep 1.99, règles `p/default` | fichiers de develop | fichier, ligne, extrait, CWE |
| DEPENDENCIES | OSV-Scanner 1.9 | fichiers de verrouillage | paquet, version, identifiant CVE/GHSA, version corrigée |
| DAST | OWASP ZAP 2.15 (baseline, passif) | application déployée en test | URL, méthode, paramètre, réponse observée |
| LOAD | k6 0.55 | application déployée en test | p95, taux d'erreur, requêtes/s ; seuils p95 < 500 ms et erreurs < 1 % |
| E2E | Playwright (si le projet a un script `test:e2e`) | application déployée en test | test en échec, sortie |

**Quand une analyse se lance :**
- SECRETS, SAST et DEPENDENCIES se lancent après chaque merge dans develop. Il n'y a jamais deux analyses en même temps pour un projet : une demande reçue pendant une analyse relance une seule analyse à la fin.
- DAST, LOAD et E2E se lancent après chaque déploiement sain en test.
- Le CTO peut aussi lancer une analyse depuis le Security Center.

### Constats

Un constat a :
- une criticité : CRITICAL, HIGH, MEDIUM, LOW ou INFO ;
- une preuve ;
- une recommandation ;
- un statut : OPEN, FIX_TASK, RISK_ACCEPTED ou FIXED.

Chaque constat a une empreinte stable, indépendante du numéro de ligne, qui sert à le reconnaître d'une analyse à l'autre. Une analyse terminée marque FIXED les constats de son type qu'elle ne voit plus.

**Correction.** Pour un constat CRITICAL ou HIGH, la plateforme crée elle-même une tâche de correction, sans appel de modèle. Cette tâche contient :
- la preuve ;
- la recommandation ;
- un ou deux critères.

Elle est confiée à l'agent du projet le plus adapté (Security, puis Backend, puis le moins chargé). Le CTO peut aussi créer la tâche d'un constat plus faible, ou accepter le risque avec un motif, qui est journalisé.

**Validation objective.** Une MR de correction ajoute une porte « finding » : l'outil est relancé sur la branche, et le constat doit avoir disparu. Pour un constat DAST ou LOAD, la porte est vérifiée au prochain déploiement.

**Porte vers main.** develop n'est pas proposé pour un merge dans main tant qu'il reste un constat CRITICAL ou HIGH ouvert (ni corrigé, ni accepté). Les tâches de correction ferment la boucle.

### Écran

Le Security Center se trouve dans l'espace Engineering. Il montre :
- les compteurs par criticité ;
- la liste des constats, avec leur preuve et leur recommandation ;
- les actions « Créer la tâche de correction » et « Accepter le risque » ;
- l'historique des analyses ;
- les métriques de charge.

**Critère de passage :** des constats avec preuve, criticité et correctif, sur un projet réel.

## Incrément 6 : livraison

### Environnements et déploiements

- **Environnement de test.** Chaque projet a un environnement `test` créé à la demande. Il tourne en Docker local, sur un port de l'hôte compris entre 18000 et 18999, avec l'URL `http://localhost:<port>`.
- **Déroulement d'un déploiement :**
  1. instantané du commit ;
  2. `docker build` du Dockerfile du projet ;
  3. arrêt de l'ancien conteneur ;
  4. démarrage du nouveau (réseau `aiw-apps`, 512 Mo, 1 CPU) ;
  5. contrôle de santé sur `/health`, puis `/`.

  Un déploiement sain lance DAST, LOAD et E2E.
- **Approbation.** Un déploiement passe toujours par une approbation :
  - le Lead le demande après chaque merge de develop dans main, et le CTO l'approuve dans l'Inbox ;
  - un clic du CTO sur « Déployer » vaut approbation explicite, et il est journalisé.
- **Rollback.** Revenir en arrière, c'est redéployer le commit d'un déploiement sain précédent, avec le même circuit.

### Versions et dépôt distant

- **Versions.** Le numéro de version est calculé par le code (patch, minor ou major). Les notes de version sont générées à partir des tâches fusionnées depuis la dernière version, sans appel de modèle. Créer une version demande une approbation (« release ») ; une fois approuvée, elle pose un tag annoté sur main.
- **Dépôt distant GitHub ou GitLab.**
  - L'URL et le token sont stockés chiffrés dans le coffre (`SecretVault`).
  - Le push envoie main, develop et les tags, avec le token passé en en-tête HTTP : il n'est jamais écrit dans la config git ni dans les logs.
  - Pousser est une action externe : elle demande l'approbation du CTO (un clic du CTO, ou l'approbation de la version qui l'inclut).

### Observabilité

- **Métriques.** Micrometer expose les métriques au format Prometheus sur le port de gestion 8091, qui n'est pas publié sur l'hôte. Elles couvrent :
  - les tokens et le coût par modèle ;
  - la durée des appels ;
  - les étapes d'agent ;
  - les jobs en attente ;
  - les déploiements ;
  - les constats ouverts.
- **Traces.** Les traces OpenTelemetry sont envoyées en OTLP si `AIW_OTLP_ENDPOINT` est défini. Elles couvrent un appel de modèle et une étape d'agent de bout en bout.
- **Profil Compose `observability`.** Il démarre Prometheus, Grafana (avec un tableau de bord fourni) et Jaeger.

### Plusieurs humains

- **Invitation.** Un owner ou un admin invite quelqu'un en lui donnant un rôle. Il obtient un lien d'invitation, valable 7 jours ; il n'y a pas d'envoi d'e-mail. La personne invitée crée son compte (ou se connecte) depuis ce lien.
- **Rôles :**
  - owner et admin : approbations, membres, fournisseurs ;
  - member : travail sur les projets ;
  - viewer : lecture seule.
- **Limites :**
  - il reste toujours au moins un owner ;
  - un admin ne peut pas modifier un owner.

### Helm

`deploy/helm/aiw` contient le chart de la plateforme : backend, frontend, Postgres, Redis, secrets et ingress optionnel. Il est validé par `helm lint` et `helm template` dans un conteneur, mais pas installé, conformément à la décision du CTO. Les sandboxes ont besoin d'un démon Docker, exposé par la valeur `sandbox.dockerHost`.

**Critère de passage :** un déploiement approuvé sur l'environnement de test, sain, suivi de ses analyses DAST et de charge.
