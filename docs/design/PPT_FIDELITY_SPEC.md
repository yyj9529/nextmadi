## Summary

Adds `docs/design/PPT_FIDELITY_SPEC.md` to define how PhraseLog UI should be implemented from the existing storyboard PPT without redesigning the visual direction.

This spec establishes the storyboard screenshots as the visual reference for the first UI pass and separates visual fidelity work from feature/API integration.

## Why

Claude Design produced an unreliable redesigned direction. For the next implementation pass, the goal is not to create a new design system, but to reproduce the existing PPT screens as closely as possible first.

## Scope

- Adds a PPT fidelity implementation spec
- Defines “no redesign” rules
- Prioritizes S04 Home and S07 Analysis Result as the first screens
- Clarifies that the first pass should be static UI only
- Sets up a future path for screenshot comparison and component extraction

## Not included

- No React/Next.js implementation yet
- No API integration
- No design token extraction yet
- No visual regression test yet

## Follow-up

After this spec is accepted:

1. Export storyboard PPT screens to PNG
2. Save reference images under `docs/design/reference/ppt/`
3. Implement S04 and S07 as static UI
4. Compare implementation screenshots against PPT references
5. Extract shared components after visual fidelity is acceptable
