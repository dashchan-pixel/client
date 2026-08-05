# Example commands

Importable `UseIn.APP` commands, one per proxy service. They are examples, not part of the app: the
app knows nothing about any proxy service, and changing the address a forum is seen at is whatever the
service in question asks for (see `content/net/VisibleAddressCommand.kt`).

- `asocks.json` — [Asocks](https://docs.asocks.com). A port of the account keeps its address until the
  script asks for another one, so the endpoint written into the forum's proxy settings stays put and
  only the address behind it moves. Environment: `ASOCKS_API_KEY`, and `ASOCKS_SOCKS=1` to write the
  port as SOCKS rather than HTTP.
- `dataimpulse.json` — [DataImpulse](https://docs.dataimpulse.com). A plan comes with sticky ports on
  one shared gateway; the script puts the forum on one and rotates the address behind it through the
  [user API](https://documenter.getpostman.com/view/7041120/2sAY4rGRZC). Environment:
  `DATAIMPULSE_LOGIN`, `DATAIMPULSE_PASSWORD`, and optionally `DATAIMPULSE_COUNTRY` (a two-letter
  code) and `DATAIMPULSE_PORT`. It wants a 50 USD top-up once the trial plan is spent, which is why
  it is here as an alternative rather than a recommendation.

## Installing one

1. Save the `.json` to the device, then **Settings → Commands → ⋮ → Add command** and pick it.
2. Open the imported command and give it the **Visible IP** grant. An imported command arrives with
   none of them on purpose — a grant is something you give here, not something a document brings with
   it — and the script fails naming the switch until it has this one.
3. Put the credentials in **Settings → Commands → ⋮ → Environment**, one `NAME=value` per line. They
   live there rather than in the script so that a command can be exported and shared without them.
4. Scope the command to the forums it should serve, and leave **Run when another visible IP is
   needed** ticked. That flag is what the forum's settings row and the toast on a banned post look
   for; with several flagged for one forum, the first in the list wins, and the list is drag-ordered.

The forum's settings screen then shows a **Change visible IP** row naming the command, and running it
either writes a proxy for a forum that has none yet or changes the address behind the one it has.
