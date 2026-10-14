# Contributing

Thank you for your interest in Rivell.

## Before you start

Open an issue first and describe the change. Small fixes are welcome. For larger changes, wait for a reply before you write code.

## Pull requests

- Branch from `dev` and open the pull request against `dev`.
- Keep each pull request to one change.
- Add or update tests. CI must pass: backend `./mvnw test`, frontend `npm run lint`, `npm run typecheck` and `npm test`.
- Never commit secrets, API keys or real contracts. Use the synthetic fixtures in `backend/src/test/resources/fixtures`.

## License of your contribution

By opening a pull request, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE), the same license as the project.
