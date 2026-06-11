# Exec plan: desktop responsive app layout

## Goal
Keep the existing mobile app layout intact while adding a natural tablet and desktop layout for `/home` and `/save/result/:analysis_request_id`.

## Source specs
- `CLAUDE.md`
- `SECURITY.md`
- `docs/quality-gates.md`
- `docs/screens/s04.md`
- `docs/screens/s07.md`

## Files expected to change
- `src/app/(app)/home/page.tsx`
- `src/app/(app)/save/result/[analysis_request_id]/page.tsx`
- `src/app/(app)/app.css`

## Acceptance criteria
- 320px: no horizontal scroll; content remains readable.
- 390px: mobile layout stays close to the current phone reference.
- 768px and above: the app no longer renders as a narrow 430px phone column.
- 1280px: app content uses available space naturally within a max width around 960px to 1120px.
- `/home` desktop layout uses two columns: main coach/mic area and side review/bookshelf/recent area.
- `/save/result/:analysis_request_id` desktop layout uses two columns: summary/actions left and expressions/culture tip right.

## Test plan
- Run lint.
- Run typecheck.
- Run production build.
- Verify responsive behavior at 320, 390, 768, and 1280 widths with browser-visible checks if tooling launches; otherwise use build plus HTTP and screenshot fallback.

## Risk areas
- Avoid changing the mobile phone layout while widening desktop.
- Avoid changing product behavior; this is layout-only.
- Existing Korean mock copy must stay untouched.

## Decision log
- Use CSS media queries at `768px` to switch from mobile stacking to desktop grid.
- Keep one shared app shell and add page-level classes for the two affected routes.

## Final outcome
Implemented. `/home` and `/save/result/:analysis_request_id` keep the mobile
stack below 768px and switch to wider two-column layouts at 768px and above.
The app shell now centers at desktop widths with a 1080px maximum width, and
the home bottom nav becomes a static wide nav at desktop.

## Verification
- `bun run lint`
- `bun run typecheck`
- `bun run build`
- Local Chrome headless screenshots at 320, 390, 768, and 1280 widths.
- Chrome DevTools metrics confirmed no horizontal overflow at all requested
  widths for `/home` and `/save/result/mock-analysis`.
