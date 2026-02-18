# UNRESOLVED

## Display Configuration Controls (Deferred)

Status: Deferred on 2026-02-18

### Scope

The display configuration control operations are currently out of active library scope:

- `dp_get_current_edid_dsp` (`magic=0x275e`)
- `dp_set_current_edid_dsp` (`magic=0x275f`)

Related public API and demo surfaces are removed for now.

### Why Deferred

- This feature is not critical for current project goals.
- Firmware behavior on the target device does not match the currently known enum mapping.
- Stable cross-firmware semantics are not yet confirmed.

### Reference Notes

Current `xr-tools` references:

- `references/xr-tools/packages/xreal_one_driver/src/proto/net/dp_get_current_edid_dsp.rs`
  - enum currently modeled as `2,3,4,5`
- `references/xr-tools/packages/xreal_one_driver/src/proto/net/dp_set_current_edid_dsp.rs`
- `references/xr-tools/packages/xreal_one_driver/src/proto/net/control.rs`
  - `get_display_configuration` is commented out in the high-level control wrapper

### Observed Device Behavior

From controls demo runtime logs on user device/firmware:

- `get_display_configuration` returns `raw=9` (unknown under current enum)
- `set_display_configuration_1080p60` returns
  - `Property command rejected status=10001 (0x2711) bodyHex=08914e`
- Unsolicited control frames are observed while connected
  - `magic=0x278a` (frequent)
  - `magic=0x2766` (during some display mode transitions)

### Investigation Work Already Done

- Added unknown-safe display read path (`getDisplayConfigurationInfo`) during investigation.
- Added typed command rejection parsing for non-empty set-property responses.
- Confirmed failures are firmware command rejections, not transport/parser crashes.

### Cleanup Status

Completed on 2026-02-18:

- Removed display configuration public APIs and models from `oneproxr`.
- Removed display configuration controls from `ControlsDemoActivity` and layout.
- Removed display configuration references from README/docs and control parity tests.
- Kept this document as the historical record for deferred re-introduction.

### Revisit Criteria

Re-open display configuration controls only when at least one of the following is available:

- Verified mapping for raw display mode values on target firmware families.
- Verified meaning of rejection status codes (including `10001`).
- A tested, deterministic behavior contract suitable for public API guarantees.
