# Deployment

Running nadia somewhere that is not the laptop it was written on: in a container, on
Kubernetes, on AWS, on Google Cloud — and pointing it at a model that may or may not be
local.

Two questions decide every configuration below, and they are independent:

| | |
|---|---|
| **Where the agent runs** | a container on your machine · a Kubernetes Job · ECS · Cloud Run |
| **Where the model runs** | a gateway you run (`local`) · any OpenAI-compatible endpoint · Bedrock · Vertex |

The default answer to the second is still *your own gateway, no credential, no network* —
that property is the reason this project exists and moving into a container does not spend
it.

## The image

```bash
docker build -t nadia:dev .
docker run --rm -it -v "$PWD:/workspace" \
  -e OPENAI_BASE_URL=http://host.docker.internal:8080/v1 \
  --add-host host.docker.internal:host-gateway \
  nadia:dev run "add a --json flag to the CLI and a test for it"
```

It packages the **Scala 3** implementation. Of the three (`SPEC.md` §0) it is the one whose
runtime is already portable — a JVM and one library, no native toolchain — so the image is
a base image and a jar rather than a cross-compilation problem. The Rust implementation
remains the reference, and its build needs the whole rozum workspace, so its image belongs
in that repository.

`docker compose run --rm nadia …` does the same with the containment flags already set;
`compose.yaml` documents each one.

### Extending it for a real project

The image carries `bash`, `git` and a JRE. That is enough to work on itself and nothing
else — an agent asked to fix a Rust project inside it will find no `cargo`. Add the
toolchain in an image of your own:

```dockerfile
FROM ghcr.io/sergey-scherbina/nadia:latest
USER root
RUN apt-get update && apt-get install -y --no-install-recommends cargo && rm -rf /var/lib/apt/lists/*
USER nadia
```

Keeping the base thin is deliberate: everything installed here is reachable by a model with
a shell, and the six tools do not get safer as the image gets fatter.

## Where the model comes from

```
--provider local     an OpenAI-compatible gateway you run. No credential. The default.
--provider openai    any hosted OpenAI-compatible endpoint, with a bearer token.
--provider bedrock   AWS. --region, plus a Bedrock API key.
--provider vertex    Google. --project and --region; on GCP, no key at all.
```

The credential is read, in order, from `--api-key-file`, then `NADIA_API_KEY`, then
`OPENAI_API_KEY`. **There is no `--api-key` flag taking the value inline**, because that
would put the key in `ps` output and in shell history, and both outlive the run.

Prefer the *file* wherever the platform can project one. Kubernetes and ECS can both hand a
secret to a container, but a file is not inherited by child processes — and this program
starts `bash` on a model's say-so.

### AWS — Bedrock

```bash
export AWS_BEARER_TOKEN_BEDROCK=…
nadia --provider bedrock --region us-east-1 \
      --model us.anthropic.claude-sonnet-4-6 \
      run "…"
```

Resolves to `https://bedrock-mantle.{region}.api.aws/v1/chat/completions`, the endpoint AWS
documents as the recommended OpenAI-compatible one.

**nadia does not sign with SigV4.** It authenticates with a Bedrock API key, which is a
bearer token. The consequence is concrete: on ECS or EKS the *task role* does not grant
model access, and the key has to exist as a secret. SigV4 against the ambient role is the
native AWS answer and it is not implemented — `BACKLOG.md` NAD-12.

### Google — Vertex AI

```bash
nadia --provider vertex --project my-project --region europe-west4 \
      --model google/gemini-3-flash \
      run "…"
```

Resolves to
`https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/endpoints/openapi`
(`--region global` drops the host prefix).

Here the ambient identity *does* work, and it is the reason to prefer this path: on GKE,
Cloud Run and GCE the workload's service account is the credential. nadia asks the metadata
server for a token before each request and caches it until a minute before it expires. No
key material exists to rotate or leak. On a laptop with no metadata server it falls back to
`gcloud auth print-access-token`.

That per-request renewal is not decoration. Google's tokens last an hour, which is shorter
than a long agent run, so a token fetched once at startup dies mid-task.

### A gotcha that cost a debugging session

nadia completes a bare origin to `/v1`, because rozum hands its gateway URL out in two
spellings. It does **not** complete a base URL that already has a path — Vertex's ends in
`…/endpoints/openapi`, and the earlier rule turned it into `…/openapi/v1/chat/completions`,
a 404 indistinguishable from a wrong project or a bad key. Pinned by a test.

## Kubernetes

```bash
kubectl apply -k deploy/k8s
kubectl logs -f job/nadia
```

Four objects: a ConfigMap (where the model is), a Secret (the credential, mounted as a
file), a **Job**, and a NetworkPolicy.

A Job rather than a Deployment, because the exit codes are already the right shape — `0`
finished, `1` budget exhausted, `2` gateway failure — and `backoffLimit: 0` means a run that
exhausted its budget is recorded as failed rather than restarted into the same wall. A
Deployment would restart it forever.

The pod's `securityContext` is not boilerplate; it is the confinement (below). Read it as
the load-bearing part of the manifest.

## AWS and Google, hosting the agent

| | |
|---|---|
| `deploy/aws/ecs-task-definition.json` | Fargate task; the task text is a command override so one revision serves every run |
| `deploy/gcp/cloudrun-job.yaml` | Cloud Run job; service account instead of a key |
| `deploy/k8s/` | works unchanged on EKS and GKE |

One difference worth knowing before you choose: **ECS injects secrets as environment
variables only.** There is no file projection, so on ECS the credential lands in the
environment, which the agent's children inherit. Kubernetes and Cloud Run can do better, and
the manifests here do.

## What confinement means once you leave macOS

This is the part that does not survive the port unexamined.

On macOS `bash` runs inside a `sandbox-exec` profile: writes outside the workspace are
denied by the kernel, and so is the network unless `--allow-net`. **That profile does not
exist on Linux.** A flag called `--no-confine` whose absence silently guarantees nothing
would be worse than no flag at all, so the agent now names the mechanism actually in force
and prints it at startup:

| | |
|---|---|
| `confined by sandbox-exec` | macOS. The agent enforces it. |
| `confined by the container runtime` | The image, its mounts and its network are the jail — fixed before the process started, and usually *stronger* than the seatbelt. |
| `NOT confined` | A bare Linux process, or `--no-confine`. Only the workspace cwd and a timeout apply. Batch mode warns on stderr. |

Under a container the jail is only as good as the flags, so these are the flags. Every one
of them is in `compose.yaml` and in `deploy/k8s/job.yaml`:

- `readOnlyRootFilesystem` / `read_only` — writable mounts are the workspace, `/tmp` and
  `$HOME`, and the last two are ephemeral
- `runAsNonRoot`, uid 1000
- `capabilities: drop: ALL`
- `allowPrivilegeEscalation: false` / `no-new-privileges`
- `seccompProfile: RuntimeDefault`

### `--allow-net` does not work in a container, and says so

The agent and the commands it runs share one network namespace. Anything nadia can reach in
order to talk to the model, `bash` can reach too. There is no version of this the *agent*
can fix from inside: by the time it is running, the namespace exists, and it cannot switch
on a network the runtime withheld nor switch off one the runtime granted.

So the separation moves up, to whoever owns the network. Two layers can do it, and they are
not exclusive:

**The cluster.** `deploy/k8s/networkpolicy.yaml` — DNS and the model endpoint, nothing else,
enforced by the CNI rather than by asking the process nicely.

**The container, before the agent starts.** An entrypoint holding `NET_ADMIN` can install an
egress allowlist and then `exec` the agent, which inherits the filter and not the capability.
rozum already does exactly this for the agents it launches under Docker —
`rozum:docker/rozum-entrypoint.sh` sets `OUTPUT DROP`, permits loopback, established flows
and the host gateway, and refuses to start if it was asked for strict egress and `iptables`
is not there. That last part is the bit worth copying: a filter that silently did not install
is indistinguishable from one that did.

**Verify it once per cluster.** Several CNIs accept NetworkPolicy objects and do not enforce
them, and an unenforced policy looks exactly like an enforced one:

```bash
kubectl run probe --rm -it --restart=Never --labels app.kubernetes.io/name=nadia \
  --image=curlimages/curl -- curl -sS -m 5 https://example.com
```

That must fail.

## What has actually been verified

Written down because a deployment document that does not distinguish "tested" from "should
work" is how an outage happens.

**Verified here:** the image builds, and a real task runs to completion inside it against a
live rozum gateway on Qwen3.5-4B — read a file, `wc -l`, write the answer, verify it with
`bash`, exit 0 — under `--read-only`, `--cap-drop ALL` and `--security-opt
no-new-privileges`, with the result landing on the host through the bind mount. The agent
reports `confined by the container runtime`. The manifests render (`kubectl kustomize`), and
the ECS definition and the compose file parse.

That run is also what found the argument-coercion bug in
[tools.md](tools.md#writing-a-tool-that-a-small-model-can-use): the first attempt failed, in
the agent rather than in the container. Which is the argument for verifying a deployment by
running work through it rather than by checking that it starts.

**Not verified here:** no AWS or Google account was involved. The Bedrock and Vertex URL
shapes come from each vendor's current documentation and the construction is unit-tested,
but no request has been made to either, and no manifest here has been applied to a real
cluster, ECS or Cloud Run. Treat them as reviewed templates, not as tested deployments.
