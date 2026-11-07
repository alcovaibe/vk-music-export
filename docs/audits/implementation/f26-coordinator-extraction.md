# F26: coordinator extraction

Scope: incremental extraction on `fix/audit-stage0-release-delivery-20261104`; no whole-app rewrite.

## Production ownership

- `MusicAuthCoordinator`: pairing request, browser dispatch, code channel, foreground waits, music verification and credential replacement. It cannot schedule work or persist credentials without injected host capabilities.
- `MusicMaintenanceCoordinator`: metadata and artwork batches, recovery, progress, account continuation checks and checkpointing.
- `MusicSavedFilesCoordinator`: file inspection, recovery preview/import and removal of an index record.
- `MusicOperationCoordinator`: generation/cancellation, process-wide admission, durable operation journal, retained maintenance launching and final busy-state publication.
- Existing catalog/artists/search/availability/friend/download/saved-library controllers remain production-wired. `NativeMusicViewModel` composes them and routes screen actions.

All file mutations still enter `AudioExportRuntime.editSaved`; extracting a coordinator does not create a second writer. Background maintenance still belongs to `MaintenanceRuntime`, survives the Activity and pauses/checkpoints through the same service. Independent search, artist and availability readers do not acquire `NativeMusicGate`.

## Contracts

The new auth unit suite covers verified commit ordering, rejection preserving previous access, a late non-cooperative response after cancellation, foreground polling, browser dispatch without treating it as login proof, request-local code lifetime and invalid manual access.

Existing device suites exercise ViewModel production wiring: auth lifecycle, account/session replacement, cancellation, screen transitions, retained maintenance and queue/file exclusion. Full CI is required before claiming this extraction verified.

## Deliberate boundary

The shared admission lock remains for operations admitted by the host. It protects journal/SQLite cleanup and retained maintenance; removing it wholesale would introduce a different concurrency change. Further splitting individual lookup/removal screens and catalog refresh policy is optional follow-up, not a claim that all 1,100 remaining ViewModel lines disappeared. The F26 solution explicitly calls for gradual extraction rather than a one-PR rewrite.
