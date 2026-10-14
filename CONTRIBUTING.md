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

Rivell is licensed under the GNU AGPL-3.0, and the maintainer also offers it under separate commercial licenses. So that this stays possible, every contribution needs this statement in the pull request description:

> I wrote this contribution and I have the right to submit it. I license it under the AGPL-3.0 and I grant Shridhar600 a perpetual, worldwide, irrevocable, royalty-free license to use, change and relicense it under any terms, including commercial terms.

Pull requests without this statement are not merged.
