# ADR-0008 — License

- **Status:** **Pending owner decision**
- **Date:** 2026-05-03

## Context

Owner stated this will be a production app and they retain all ownership rights. Owner also asked to be advised before choosing.

## Background — what a license actually does

A license file in your repository governs **what other people may do with your source code**. It is independent of:

- **Copyright** — you own that automatically the moment code is written; no license required.
- **Trademark** — the product name "The-Dogs" is not protected by any code license.
- **Compliance with your dependencies' licenses** — that is governed by *their* licenses, not yours. You must still respect MIT/Apache-2.0/BSD/ISC notices when distributing built artifacts (`license-scan` skill enforces this).

If the **repository is private and never published**, a license file is technically optional — no one outside your org has access. Best practice is to add one anyway so that the moment a contractor, employee, or auditor sees the code, the rules are unambiguous.

## Options

### A. No license file (private repo only)
- **Effect:** by default, "all rights reserved." No one may copy, use, or modify without your written permission.
- **Risk:** if the repo ever becomes public (accidental push to a public org, leak), there is no notice in-tree to warn copiers.
- **Recommended only if** you are 100% sure the repo stays private and you have other access controls.

### B. Proprietary "All Rights Reserved" notice (recommended for closed-source production app)
- A short `LICENSE` file stating: copyright holder, year, "All rights reserved. No permission is granted to use, copy, modify, merge, publish, distribute, sublicense, or sell copies of this software without the express prior written consent of the copyright holder."
- **Effect:** identical default, but explicit. Safer if the repo is ever exposed.
- **Compatible with:** using MIT/Apache-2.0/BSD/ISC dependencies (which is what we use). You cannot use GPL/AGPL deps without making your own code GPL/AGPL.
- **Best for:** a commercial product you own outright.

### C. Permissive open source (MIT / Apache-2.0)
- Anyone may use, modify, distribute. You retain copyright; they must keep the notice.
- Apache-2.0 additionally grants explicit patent rights and requires NOTICE preservation.
- **Effect:** competitors can fork and run their own SaaS from your code.
- **Best for:** projects where ecosystem adoption matters more than exclusivity.

### D. Source-available / non-commercial (BUSL, Elastic License, SSPL, PolyForm)
- Source visible but commercial use restricted; common for SaaS companies (HashiCorp, Elastic, MongoDB).
- Complex; usually reserved for products where the source-being-visible has marketing or trust value but you want to block competitors.
- **Best for:** later-stage products with named competitors, not greenfield MVPs.

## Recommendation

**Option B — Proprietary "All Rights Reserved"** for an owner-retained commercial product. Lowest friction, no obligations, safest default, fully compatible with our chosen dependencies (Apache-2.0, MIT, ISC, BSD).

## Action required

Owner to confirm one of A / B / C / D. Default to B unless told otherwise; commit `LICENSE` file at that point.

## Notes for the AI team

- `license-scan` skill must fail the build on any GPL/AGPL dependency, regardless of which option above is chosen — copyleft licenses are incompatible with all four options.
