# Nova Raids
Check out the [Wiki](https://github.com/Unariginal/NovaRaids/wiki)!

## Mega Showdown / Accessories lag (raids)

With many concurrent raid battles, Mega Showdown can dominate the server thread by
rescanning Accessories + power-spot blocks on **every** Showdown action sanitize
(`GimmickTurnCheck.check`).

### Recommended Mega server config (does not disable gimmicks)

In `config/mega_showdown/config.json`:

- Set **`powerSpotRange` to `1`** (default is often `20`). High ranges cause heavy
  battle lag; lowering this keeps Mega/Dynamax/Z/Tera working.
- Prefer **`dynamaxAnywhere`: `false`** unless you need world-wide Dynamax.
- Keep NovaRaids **`automatic_battles`: `false`** unless you intentionally want
  auto-requeue (each new battle multiplies Mega hooks).

NovaRaids does **not** currently cancel Mega gimmick checks in raids (that would
disable Mega/Dynamax/Z/Tera during raid battles). Ask before enabling that tradeoff.
