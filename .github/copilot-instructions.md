# GitHub Copilot Instructions

This repository is the `gateway` Java/Spring Cloud Gateway service. Before suggesting or changing code, read `AGENTS.md` and `docs/ai-coding/README.md`.

Follow these project rules:

- Follow `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md` before adding, moving, or deleting directories.
- Keep Java code under `src/main/java/com/kellen`; tests belong under `src/test/java/com/kellen`.
- Do not implement business authorization, user parsing, tenant isolation, or data permission logic in the gateway.
- Keep Dubbo RPC interfaces and DTOs in sibling `rpc-api`; gateway must not implement provider or consumer business orchestration.
- Do not change existing secrets, Nacos addresses, internal addresses, or production configuration values. Report file paths and line numbers only.
- Do not nest sibling repositories such as `user`, `message`, `utils`, `admin-web`, or `ai` inside this repository.
