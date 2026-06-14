# PhraseLog backend systemd startup

This directory contains the systemd unit for issue #9. It is deploy-ready
material only; installing or restarting it on EC2 is a deploy action and needs
owner approval under `SECURITY.md`.

## Expected artifact

CI or the deploy workflow should build:

```bash
./gradlew -p backend clean bootJar
```

and place the resulting JAR at:

```text
/opt/phraselog/backend/phraselog-backend.jar
```

## Environment file

Create `/etc/phraselog/backend.env` on the EC2 instance. Do not commit this file.

```bash
JAVA_OPTS=-Xms256m -Xmx512m
AWS_REGION=us-east-1
PHRASELOG_SECRETS_IMPORT=optional:aws-secretsmanager:/phraselog/prod/backend/
```

The EC2 instance role must be allowed to read only the PhraseLog backend secret
path that E01.2 creates, for example `secretsmanager:GetSecretValue` on the
specific backend secret ARN.

## Logging

The app writes structured JSON logs to stdout/stderr. systemd captures them in
journald; CloudWatch Logs collection is configured outside this repo by the
CloudWatch agent or instance bootstrap.

## Restart behavior

`Restart=on-failure` and `RestartSec=5` provide automatic process recovery. This
does not provide zero-downtime deployment; that is explicitly out of v1 scope in
`docs/architecture.md`.
