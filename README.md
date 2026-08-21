# openllmetry-akka

Records a call to a language model as it happens and turns it into one finished
observation record, in the shape the OpenTelemetry standard defines.

A port of [traceloop/openllmetry](https://github.com/traceloop/openllmetry) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

traceloop/openllmetry is a set of Python packages that watch calls to language models and
report what happened to a monitoring system. It was ported to derive a specification format
precise enough to regenerate a system on a different stack — the port is the vehicle, the
specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness) under
`openllmetry-port/`.

---

## traceloop/openllmetry → this port

📉 1,210 Python lines → **589 Java lines**<br>
📁 4 files → **16 files**<br>
⚡ 552 → **3.8** microseconds to turn one recorded call into a finished record<br>
🎯 delivery orders of three answers survived: 1 of 4 → **4 of 4**<br>
🖼️ pieces of a question kept when one of them is a picture: 0 of 3 → **3 of 3**<br>
🔌 pieces of a partly-arrived answer that survive a restart: 0 → **all of them**<br>
🚀 cold start: not measured

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/openllmetry-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.6 hours** from the first command to the published repository, **1.2** of them active<br>
💬 **296** exchanges with the model<br>
✍️ **312,181** tokens written by the model, **61,533,967** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **64** tests

```bash
python toolkit/tokens.py --port openllmetry    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A question and its answer become one record, each written once.** Everything sent to
  the model and everything it sent back is carried in two fields, so a reader parses two
  values rather than hunting through numbered ones.
- **An answer that arrives in pieces has no record until the last piece is in.** A partly
  arrived answer is never reported as a finished one.
- **Every piece is written down as it arrives, not held in memory.** The machine can stop
  between two pieces of an answer and the record that is finished afterwards is the same
  record it would have been.
- **The same recorded call always gives the same record.** Reading the record twice, or on
  two different machines, cannot give two answers.
- **A caller that sends the same batch of pieces twice adds it once.** Pieces are joined
  end to end, so counting a batch twice would quietly double the answer.
- **Nothing a caller sends can grow the stored record without limit.** Long text is cut at
  a fixed size and the cut is visible in the record.
- **Turning off the recording of questions and answers removes those two fields and
  nothing else.** Which model was used and how much it cost are still there.
- **A call marked as not to be recorded produces no record at all.**

---

## Design decisions

**Tagged values.** Every value in a record says what kind of thing it is — a word, a whole
number, a decimal, a yes-or-no, or a list of words — instead of being stored loosely. Stored
loosely, a large whole number comes back as a small one after a restart, and nothing says it
changed.

**Event sourcing.** Each piece of an arriving answer is written down the moment it turns up,
and the finished record is worked out from the written pieces rather than from anything held
in memory. If the machine stops halfway through an answer, the pieces already written are
still there when it starts again.

**Answers held by number, not by position.** The model can send several answers to one
question, each carrying its own number, and this port files each one under its number. Pieces
can then turn up in any order and still end up in the right answer.

**A limit on how much of a question is kept.** Any single piece of text is cut at the same
fixed size wherever it comes from. A question with a photograph in it can otherwise be larger
than one stored record is allowed to be, and the failure would show up much later as a
refusal to copy the record between machines.

**A number on every piece.** Each piece of an arriving answer is stored with a counter, and a
reader can ask for everything written so far. A reader whose connection drops can come back
and say which pieces it already has, instead of starting again or missing the ones sent while
it was away.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/openllmetry-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9034.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

No key for any model provider is needed. This port does not call a model; it records calls
that something else made.

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9034**.

### Record a call

```bash
curl -X POST localhost:9034/calls/demo/open -H 'content-type: application/json' -d '{
  "request": {"model": "gpt-4o", "baseUrl": "https://api.openai.com/v1", "streaming": true},
  "inputMessages": [{"role": "user", "parts": [{"k": "text", "content": "weather in Oslo?"}]}]
}'

curl -X POST localhost:9034/calls/demo/chunks -H 'content-type: application/json' -d '{
  "deliveryId": "1", "responseId": "chatcmpl-1", "responseModel": "gpt-4o-2024-08-06",
  "chunks": [{"index": 0, "role": "assistant", "content": "Rainy.", "finishReason": "stop"}]
}'

curl -X POST localhost:9034/calls/demo/close
curl localhost:9034/calls/demo/span
```

| Request | What it does |
|---|---|
| `POST /calls/{id}/open` | Starts a record for one call and stores the question |
| `POST /calls/{id}/chunks` | Adds pieces of the answer as they arrive |
| `POST /calls/{id}/close` | Says the answer is complete |
| `POST /calls/{id}/fail` | Says the call failed, and why |
| `POST /calls/{id}/suppress` | Says this call is not to be recorded |
| `GET /calls/{id}/span` | The finished record, or nothing while the answer is still arriving |
| `GET /calls/{id}/progress` | Every piece written so far, each with its number |

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `TRACELOOP_TRACE_CONTENT` | unset, which means on | Set to `false` to leave questions and answers out of the record. Everything else is still recorded. |

---

## Where it differs from traceloop/openllmetry

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **A question with a picture inside it.** traceloop/openllmetry records nothing at all
  about the question when one of its pieces is a picture sent as text rather than as a web
  address — not the picture, and not the words around it either. This port records all of
  it, because it has no step that could fail there and no reason to lose the words along
  with the picture.
- **Answers arriving out of order.** When the model sends several answers at once and a
  piece of the third arrives before any piece of the first, traceloop/openllmetry stops with
  an error, and in two of the four orders tried it still produced a record naming one empty
  answer where three were sent. This port files each answer under its own number, so every
  order gives the same three answers. Whether the model service can actually send them in
  that order was **not checked** — the rule was chosen because it costs nothing if it never
  happens, and on the one order the original survives, the two agree exactly.
- **The same batch of pieces sent twice.** traceloop/openllmetry reads an answer straight
  off a live connection, so there is no such thing as sending the same pieces again. Here a
  caller can retry, and pieces are joined end to end, so a retry would double the answer.
  This port asks the caller to name each batch and adds a named batch once.
- **A cut on long text.** traceloop/openllmetry keeps whatever it is given. This port cuts
  any single piece of text at 128 kilobytes and marks the record as cut, because a stored
  record has a size beyond which it stops being copied between machines, and the size was
  **not measured** — cutting at a size that is certainly under it is cheaper than finding
  the real one by exceeding it.
- **The order of the fields inside a question or answer.** traceloop/openllmetry writes the
  same information with its fields in two different orders depending on how the caller
  supplied the text. This port always writes one order. Anything reading the values sees no
  difference; anything comparing the raw text does.
- **What a reader sees after losing its connection.** traceloop/openllmetry hands finished
  records to a collector and has no idea whether anything is listening, so it has no answer
  here. This port numbers every piece as it is written and will replay them, so a reader
  that comes back gets everything written while it was away.
- **Where the record goes.** traceloop/openllmetry sends finished records straight to a
  monitoring system over the network. This port stores them and serves them when asked, and
  sends them nowhere. Anything that wants them must fetch them.
- **What the original does that this port does not attempt.** Counters and timings
  alongside the records, the twenty-nine other model providers, embeddings, image
  generation, the assistants and live-audio interfaces, and attaching itself automatically
  to the model library at start-up. This port is told about a call rather than noticing one.
- **Everything else was compared and agreed**, across thirteen sets of inputs, thirty-seven
  steps and four hundred and eighty-one field-by-field checks. What was not compared: any
  call to a real model service. Every input was written by hand from the shapes the model
  service documents and the original was observed to produce.

---

## Licence

traceloop/openllmetry is Apache 2.0, © Traceloop. This port reimplements the behaviour
without copied source; see `ACKNOWLEDGEMENTS.md`.
